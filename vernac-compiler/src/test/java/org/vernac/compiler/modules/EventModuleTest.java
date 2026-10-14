// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.modules;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.persistence.SchemaModel;
import org.vernac.compiler.analyzer.SemanticValidationException;
import static org.junit.jupiter.api.Assertions.*;

class EventModuleTest {
    @TempDir Path root;
    private ModuleDescriptor publish(String text,String exports) throws Exception {
        Path sources=root.resolve("producer/src/main/vernac"),file=sources.resolve("library/events/model.vernac");
        Files.createDirectories(file.getParent());Files.writeString(file,text);
        var compiler=new VernacCompiler();var resolved=compiler.analyzeProject(sources);
        var generated=compiler.generate(resolved,SchemaModel.empty(),Map.of(),new org.vernac.compiler.persistence.SchemaBuilder.Options(true,false));
        return ModuleContracts.publish(ModuleDefinition.parse(root.resolve("producer/vernac-module.vmodule"),
            "module library.events {"+exports+"}"),resolved,generated.generatedFiles(),"example","events","1",List.of());
    }
    @Test void exportsPayloadClosureAndImportsAnActualEventContract() throws Exception {
        String source="namespace library.events; value Name(String); event Changed(Name); synchronous listener Internal on Changed behavior {execute {}}";
        assertTrue(assertThrows(SemanticValidationException.class,()->publish(source,"export library.events.Changed;")).getMessage().contains("requires explicit export library.events.Name"));
        var module=publish(source,"export library.events.Name;export library.events.Changed;");
        ModuleContracts.checkDependencies(List.of(module));
        assertEquals("EVENT",module.exports().stream().filter(d->d.identity().endsWith("Changed")).findFirst().orElseThrow().kind());
        assertTrue(module.internalTypes().contains("library.events.Internal"));
        assertTrue(module.components().stream().anyMatch(c->c.kind().equals("LISTENER")));
        assertTrue(ModuleArtifacts.view(module,"library.events").contains("event Changed(library.events.Name name)"));
        Path sources=root.resolve("consumer/src/main/vernac"),file=sources.resolve("app/model.vernac");Files.createDirectories(file.getParent());
        Files.writeString(file,"namespace app; import library.events.Changed; synchronous listener Observe on Changed behavior {execute {}} ");
        var compiler=new VernacCompiler();var result=compiler.generate(compiler.analyzeProject(sources,List.of(module)),SchemaModel.empty(),Map.of());
        assertTrue(result.generatedFiles().stream().anyMatch(f->f.packageName().equals("app.adapter.inbound.event")));
        assertTrue(result.generatedFiles().stream().noneMatch(f->f.packageName().startsWith("library.events")));
        Files.writeString(file,"namespace app; import library.events.Changed; deferred listener Observe on Changed behavior {execute {}} ");
        var deferred=compiler.analyzeProject(sources,List.of(module));
        var localSchema=new org.vernac.compiler.persistence.SchemaBuilder(deferred,SchemaModel.empty()).build();
        assertEquals(List.of("vernac/@delivery"),localSchema.tables().stream().map(t->t.identity()).toList());
    }
    @Test void publishesRecordedAndDeferredStorageButKeepsReceiversInternal() throws Exception {
        String exports="export library.events.Name;export library.events.Changed;";
        var recorded=publish("namespace library.events; value Name(String); recorded event Changed(Name);",exports);
        assertTrue(recorded.persistent());assertTrue(ModuleArtifacts.view(recorded,"library.events").contains("recorded event Changed"));
        ModuleContracts.checkDependencies(List.of(recorded));
        var deferred=publish("namespace library.events; value Name(String); event Changed(Name); deferred listener Later on Changed behavior {execute {}}",exports);
        assertTrue(deferred.components().stream().anyMatch(c->c.kind().equals("DEFERRED_LISTENER")));ModuleContracts.checkDependencies(List.of(deferred));
        assertTrue(assertThrows(SemanticValidationException.class,()->publish("namespace library.events; value Name(String); event Changed(Name); synchronous listener Internal on Changed behavior {execute {}}",exports+"export library.events.Internal;")).getMessage().contains("internal runtime receivers"));
    }
}
