// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.modules;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.persistence.*;
import org.vernac.compiler.analyzer.SemanticValidationException;
import static org.junit.jupiter.api.Assertions.*;
class PersistentModuleTest {
    @TempDir Path root;
    private static final String MODEL="""
        namespace billing;
        id InvoiceId; id LineId;
        value Reference(String); value Amount(BigDecimal);
        entity Line[LineId](Reference, mut Amount) behavior {
            modify void changeAmount(Amount amount){self.amount(amount);}
            read Amount total(){return self.amount();}
        } list Lines;
        aggregate Invoice[InvoiceId](Reference, mut Lines) list Invoices;
        repository InvoiceRepository for Invoice {
            find offset Invoices byReference(Reference reference)
                where reference = :reference order by createdAt desc, id counting;
            find Invoice? maybe(Reference reference) where reference = :reference;
        }
        """;
    private static final String EXPORTS="InvoiceId LineId Reference Amount Line Lines Invoice Invoices InvoiceRepository";
    private ModuleDescriptor publish(String text,String exports) throws Exception {
        Path sources=root.resolve("producer/src/main/vernac"),source=sources.resolve("billing/model.vernac");Files.createDirectories(source.getParent());Files.writeString(source,text);
        var compiler=new VernacCompiler();var project=compiler.analyzeProject(sources,List.of());var generated=compiler.generate(project,SchemaModel.empty(),Map.of());
        String selected=Arrays.stream(exports.split(" ")).map(n->"export billing."+n+";").collect(java.util.stream.Collectors.joining());
        return ModuleContracts.publish(ModuleDefinition.parse(root.resolve("producer/vernac-module.vmodule"),"module example.billing {"+selected+"}"),project,generated.generatedFiles(),"example","billing","1",List.of());
    }
    @Test void publishesCompleteMutableAndRepositoryContractsWithoutChangingConsumerOwnership() throws Exception {
        var descriptor=publish(MODEL,EXPORTS);ModuleContracts.checkDependencies(List.of(descriptor));assertTrue(descriptor.persistent());
        var line=descriptor.exports().stream().filter(d->d.identity().equals("billing.Line")).findFirst().orElseThrow();
        assertEquals("billing.LineId",line.element());assertTrue(line.fields().stream().filter(f->f.name().equals("amount")).findFirst().orElseThrow().mutable());
        assertEquals(Set.of("READ","MODIFY"),line.methods().stream().map(ModuleDescriptor.Method::mode).collect(java.util.stream.Collectors.toSet()));
        var repository=descriptor.exports().stream().filter(d->d.kind().equals("REPOSITORY")).findFirst().orElseThrow();
        assertEquals("billing.Invoice",repository.element());assertEquals("COUNTED_OFFSET_QUERY",repository.methods().stream().filter(m->m.name().equals("byReference")).findFirst().orElseThrow().mode());
        Path consumer=root.resolve("consumer/src/main/vernac"),source=consumer.resolve("app/model.vernac");Files.createDirectories(source.getParent());
        Files.writeString(source,"namespace app; import billing.Reference; value Local(Reference);");
        var compiler=new VernacCompiler();var resolved=compiler.analyzeProject(consumer,List.of(descriptor));
        assertTrue(new SchemaBuilder(resolved,SchemaModel.empty()).build().tables().isEmpty());
        assertTrue(compiler.generate(resolved,SchemaModel.empty(),Map.of()).generatedFiles().stream().noneMatch(f->f.packageName().startsWith("billing")));
        Files.writeString(source,"namespace app; import billing.Invoice; repository Duplicate for Invoice {}");
        assertTrue(assertThrows(SemanticValidationException.class,()->compiler.analyzeProject(consumer,List.of(descriptor))).getMessage().contains("module-owned aggregate"));
        String view=ModuleArtifacts.view(descriptor,"billing");assertTrue(view.contains("mut billing.Amount amount"));assertTrue(view.contains("read billing.Amount total"));assertTrue(view.contains("find offset billing.Invoices byReference"));
    }
    @Test void requiresExplicitPublicClosureAndDetectsMutabilityOrModeChanges() throws Exception {
        assertThrows(SemanticValidationException.class,()->publish(MODEL,EXPORTS.replace("Lines ","")));
        var first=publish(MODEL,EXPORTS);
        var changed=publish(MODEL.replace("read Amount total()","modify Amount total()"),EXPORTS);
        var before=first.exports().stream().filter(d->d.identity().equals("billing.Line")).findFirst().orElseThrow();
        var after=changed.exports().stream().filter(d->d.identity().equals("billing.Line")).findFirst().orElseThrow();
        assertTrue(ModuleContracts.difference(before,after).isPresent());
        var immutable=publish(MODEL.replace("mut Lines","Lines"),EXPORTS);
        assertTrue(ModuleContracts.difference(first.exports().stream().filter(d->d.kind().equals("AGGREGATE")).findFirst().orElseThrow(),immutable.exports().stream().filter(d->d.kind().equals("AGGREGATE")).findFirst().orElseThrow()).isPresent());
    }
}
