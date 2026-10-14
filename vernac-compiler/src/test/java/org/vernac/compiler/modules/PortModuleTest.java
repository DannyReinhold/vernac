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
class PortModuleTest {
    @TempDir Path root;
    private ModuleDescriptor publish(String extra,String exports) throws Exception {
        Path source=root.resolve("producer/src/main/vernac/example/billing/model.vernac");Files.createDirectories(source.getParent());
        Files.writeString(source,"namespace example.billing; id PaymentId; value Amount(BigDecimal); port PaymentProvider {PaymentId charge(Amount);"+extra+"}");
        var compiler=new VernacCompiler();var resolved=compiler.analyzeProject(root.resolve("producer/src/main/vernac"),List.of());
        var generated=compiler.generate(resolved,SchemaModel.empty(),Map.of());
        var manifest=ModuleDefinition.parse(root.resolve("producer/vernac-module.vmodule"),"module example.billing {export example.billing.PaymentProvider;"+exports+"}");
        return ModuleContracts.publish(manifest,resolved,generated.generatedFiles(),"example","billing","1",List.of());
    }
    private static final String EXPORTS="export example.billing.PaymentId;export example.billing.Amount;";
    @Test void publicPortTypesMustBeExplicitlyExported() {
        var error=assertThrows(SemanticValidationException.class,()->publish("",""));
        assertTrue(error.getMessage().contains("explicit export example.billing.Amount"));
        assertTrue(error.getMessage().contains("explicit export example.billing.PaymentId"));
    }
    @Test void consumerImplementsImportedPortWithoutRegeneratingItsInterface() throws Exception {
        var descriptor=publish("",EXPORTS);ModuleContracts.checkDependencies(List.of(descriptor));
        Path source=root.resolve("consumer/src/main/vernac/example/mollie/model.vernac");Files.createDirectories(source.getParent());
        Files.writeString(source,"namespace example.mollie; import example.billing.PaymentProvider; import example.billing.PaymentId; import example.billing.Amount; adapter MolliePaymentProvider implements PaymentProvider {behavior {public PaymentId charge(Amount amount) {return PaymentId.create();}}}");
        var compiler=new VernacCompiler();var resolved=compiler.analyzeProject(root.resolve("consumer/src/main/vernac"),List.of(descriptor));
        var generated=compiler.generate(resolved,SchemaModel.empty(),Map.of());
        assertTrue(generated.generatedFiles().stream().anyMatch(f->f.typeSpec().name().equals("MolliePaymentProvider")));
        assertTrue(generated.generatedFiles().stream().noneMatch(f->f.packageName().startsWith("example.billing")));
        String view=ModuleArtifacts.view(descriptor,"example.billing");assertTrue(view.contains("port PaymentProvider {"));assertTrue(view.contains("charge(example.billing.Amount amount)"));
        Files.writeString(source,"namespace example.mollie; import example.billing.PaymentProvider; adapter Broken implements PaymentProvider {behavior {}}");
        assertThrows(SemanticValidationException.class,()->compiler.analyzeProject(root.resolve("consumer/src/main/vernac"),List.of(descriptor)));
    }
    @Test void addingAbstractOperationRequiresProviderRebuild() throws Exception {
        var old=publish("",EXPORTS).exports().stream().filter(d->d.kind().equals("PORT")).findFirst().orElseThrow();
        var changed=publish("void cancel(PaymentId);",EXPORTS).exports().stream().filter(d->d.kind().equals("PORT")).findFirst().orElseThrow();
        assertTrue(ModuleContracts.difference(old,changed).orElseThrow().contains("implementation rebuild"));
    }
}
