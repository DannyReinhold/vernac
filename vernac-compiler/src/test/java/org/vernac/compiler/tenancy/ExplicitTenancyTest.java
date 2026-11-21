// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.tenancy;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.application.ApplicationDefinition;
import org.vernac.compiler.modules.ModuleDefinition;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.persistence.*;
import org.vernac.compiler.analyzer.SemanticValidationException;
import static org.junit.jupiter.api.Assertions.*;

class ExplicitTenancyTest {
    @TempDir Path root;
    private Path source(String modifier, boolean repository) throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        var src = root.resolve("src/main/vernac"); Files.createDirectories(src.resolve("example"));
        Files.writeString(src.resolve("example/model.vernac"), "namespace example; id InvoiceId; "
                + modifier + "aggregate Invoice[InvoiceId]();"
                + (repository ? " repository InvoiceRepository for Invoice {}" : ""));
        return src;
    }
    @Test void applicationSettingIsOrderIndependentAndDoesNotChangeBindings() {
        var parsed = ApplicationDefinition.parse(root.resolve(ApplicationDefinition.FILE),
                "application example.app { activate example.billing; tenancy explicit; bind example.Payment to example.Noop; }");
        assertTrue(parsed.explicitTenancy()); assertEquals(1, parsed.activations().size()); assertEquals(1, parsed.bindings().size());
        assertFalse(new ApplicationDefinition("example.app", List.of(), List.of()).explicitTenancy());
        assertThrows(SemanticValidationException.class, () -> ApplicationDefinition.parse(root.resolve("x"),
                "application example.app { tenancy explicit; tenancy explicit; }"));
    }
    @Test void moduleSettingIsOrderIndependentAndRejectsDuplicatesAndUnknownModes() {
        var parsed = ModuleDefinition.parse(root.resolve(ModuleDefinition.FILE),
                "module example.billing { export example.InvoiceId; tenancy explicit; export example.Invoice; }");
        assertTrue(parsed.explicitTenancy()); assertEquals(2, parsed.exports().size());
        assertFalse(new ModuleDefinition("example.billing", List.of()).explicitTenancy());
        assertThrows(SemanticValidationException.class, () -> ModuleDefinition.parse(root.resolve("x"),
                "module example.billing { tenancy explicit; tenancy explicit; }"));
        assertThrows(SemanticValidationException.class, () -> ApplicationDefinition.parse(root.resolve("x"),
                "application example.app { tenancy automatic; }"));
    }
    @Test void globalDeclarationKeepsExistingGeneratedJavaAndSchema() throws Exception {
        var compiler = new VernacCompiler();
        var src = source("", true);
        var ordinary = compiler.compileProject(src).generatedFiles().toString();
        var oldSchema = new SchemaBuilder(compiler.analyzeProject(src), SchemaModel.empty()).build();
        source("global ", true);
        var explicit = compiler.compileProject(src).generatedFiles().toString();
        var globalSchema = new SchemaBuilder(compiler.analyzeProject(src), SchemaModel.empty()).build();
        assertEquals(ordinary, explicit);
        assertEquals(oldSchema, globalSchema);
        assertTrue(compiler.readProject(src).sources().getFirst().unit().definitions().stream()
                .anyMatch(d -> d instanceof org.vernac.compiler.ast.AggregateNode a && a.global()));
    }
    @Test void applicationRuleRejectsBarePersistedAggregateAndAcceptsExplicitGlobal() throws Exception {
        var src = source("", true);
        Files.writeString(root.resolve(ApplicationDefinition.FILE), "application example.app { tenancy explicit; }");
        var compiler = new VernacCompiler();
        var error = assertThrows(SemanticValidationException.class, () -> compiler.compileProject(src));
        assertTrue(error.getMessage().contains("example.Invoice"));
        assertTrue(error.getMessage().contains("global aggregate"));
        source("global ", true);
        assertDoesNotThrow(() -> compiler.compileProject(src));
        Files.delete(root.resolve(ApplicationDefinition.FILE));
        source("", true);
        assertDoesNotThrow(() -> compiler.compileProject(src));
    }
    @Test void moduleRuleCoversPrivatePersistedTypesButDoesNotRequireClassificationWithoutPersistence() throws Exception {
        var src = source("", true);
        Files.writeString(root.resolve(ModuleDefinition.FILE), "module example.billing { tenancy explicit; }");
        assertThrows(SemanticValidationException.class, () -> new VernacCompiler().compileProject(src));
        source("", false);
        assertDoesNotThrow(() -> new VernacCompiler().compileProject(src));
        Files.delete(root.resolve(ModuleDefinition.FILE));
        Files.createDirectory(root.resolve(ModuleDefinition.FILE));
        var invalid=assertThrows(SemanticValidationException.class, () -> new VernacCompiler().compileProject(src));
        assertTrue(invalid.getMessage().contains("Cannot read Vernac tenancy configuration"));
    }
    @Test void applicationRuleDoesNotReclassifyExternalSources() throws Exception {
        var src = source("", true);
        Files.writeString(root.resolve(ApplicationDefinition.FILE), "application example.app { tenancy explicit; }");
        var local = new VernacCompiler().readProject(src);
        var external = new VernacProject(local.sourceRoot(), local.sources().stream()
                .map(s -> new VernacSourceFile(s.path(), s.unit(), true)).toList(), local.symbols());
        var resolved = new ProjectTypeResolver().resolve(external);
        assertTrue(resolved.diagnostics().stream().noneMatch(d -> d.message().contains("tenancy explicit")));
    }
}
