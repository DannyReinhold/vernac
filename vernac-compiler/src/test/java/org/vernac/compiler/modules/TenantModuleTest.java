// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.modules;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.ast.AggregateNode;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.persistence.*;
import static org.junit.jupiter.api.Assertions.*;

class TenantModuleTest {
    @TempDir Path root;
    private ModuleDescriptor publish(String scope) throws Exception {
        Path source = root.resolve("producer/src/main/vernac/shared/model.vernac");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                namespace shared;
                id RootId; id ChildId;
                value Label(String);
                entity Child[ChildId](Label) list Children;
                SCOPE aggregate Root[RootId](Children) list Roots;
                """.replace("SCOPE", scope));
        Path manifest = root.resolve("producer/vernac-module.vmodule");
        String exports = "module example.shared {"
                + "export shared.RootId;export shared.ChildId;export shared.Label;export shared.Child;"
                + "export shared.Children;export shared.Root;export shared.Roots;}";
        Files.writeString(manifest, exports);
        var compiler = new VernacCompiler();
        var resolved = compiler.analyzeProject(root.resolve("producer/src/main/vernac"));
        var generated = compiler.generate(resolved, SchemaModel.empty(), Map.of());
        return ModuleContracts.publish(ModuleDefinition.parse(manifest, exports), resolved,
                generated.generatedFiles(), "example", "shared", "1", List.of());
    }

    private ModuleDescriptor.Definition definition(ModuleDescriptor descriptor, String name) {
        return descriptor.exports().stream().filter(d -> d.identity().equals(name)).findFirst().orElseThrow();
    }

    @Test void classificationAndOwnershipSurviveJsonAndReadOnlyContractViews() throws Exception {
        var published = publish("tenant");
        var restored = ModuleDescriptor.JSON.readValue(ModuleDescriptor.JSON.writeValueAsBytes(published), ModuleDescriptor.class);
        ModuleContracts.checkDependencies(List.of(restored));
        assertEquals("TENANT", definition(restored, "shared.Root").aggregateScope());
        assertEquals(1, definition(restored, "shared.Child").entityOwnershipVersion());
        assertTrue(ModuleArtifacts.view(restored, "shared").contains("tenant aggregate Root"));
        assertFalse(restored.activatable(), "Type imports must not activate a runtime context");
        Path consumer = Files.createDirectories(root.resolve("consumer/src/main/vernac"));
        var merged = ModuleArtifacts.merge(new VernacProjectLoader().load(consumer), List.of(restored));
        var aggregate = merged.sources().stream().flatMap(s -> s.unit().definitions().stream())
                .filter(d -> d instanceof AggregateNode).map(d -> (AggregateNode) d).findFirst().orElseThrow();
        assertTrue(aggregate.tenant());
    }

    @Test void scopeChangesAreIncompatibleAndOrdinaryAndGlobalRemainEquivalent() throws Exception {
        var tenant = definition(publish("tenant"), "shared.Root");
        var global = definition(publish("global"), "shared.Root");
        assertNotEquals(tenant.fingerprint(), global.fingerprint());
        assertTrue(ModuleContracts.difference(tenant, global).orElseThrow().contains("TENANT to GLOBAL"));
        assertTrue(ModuleContracts.difference(global, tenant).orElseThrow().contains("GLOBAL to TENANT"));
        var expected = publish("tenant");
        var consumer = new ModuleDescriptor(1, ModuleDescriptor.CONTRACT, "example.consumer", "example", "consumer", "1",
                List.of(), List.of(new ModuleDescriptor.Requirement(expected.module(), expected.groupId(),
                        expected.artifactId(), "1", expected.exports())));
        var error = assertThrows(IllegalArgumentException.class,
                () -> ModuleContracts.checkDependencies(List.of(consumer, publish("global"))));
        assertTrue(error.getMessage().contains("Changed aggregate scope from TENANT to GLOBAL"));
        var ordinary = definition(publish(""), "shared.Root");
        assertEquals(global.fingerprint(), ordinary.fingerprint());
    }

    @Test void importedEntitiesParticipateInLocalTenantGraphsWithoutRegeneration() throws Exception {
        var descriptor = publish("tenant");
        Path source = root.resolve("consumer/src/main/vernac/app/model.vernac");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "namespace app; import shared.Children; id BoardId; "
                + "tenant aggregate Board[BoardId](Children); repository BoardRepository for Board {}");
        var compiler = new VernacCompiler();
        var resolved = compiler.analyzeProject(root.resolve("consumer/src/main/vernac"), List.of(descriptor));
        var generated = compiler.generate(resolved, SchemaModel.empty(), Map.of());
        assertTrue(generated.generatedFiles().stream().noneMatch(f -> f.packageName().startsWith("shared.")));
        String java = generated.generatedFiles().toString();
        assertTrue(java.contains("Child.__vernacRestore"));
        var schema = new SchemaBuilder(resolved, SchemaModel.empty()).build();
        assertTrue(schema.tables().stream().allMatch(t -> t.tenantIsolation() != null));
    }

    @Test void unsupportedAndMissingMetadataFailBeforeConsumerGeneration() throws Exception {
        String json = ModuleDescriptor.JSON.writeValueAsString(publish("tenant"));
        assertThrows(Exception.class, () -> ModuleDescriptor.JSON.readValue(
                json.replace(ModuleDescriptor.CONTRACT, "module-contracts-9"), ModuleDescriptor.class));
        assertThrows(Exception.class, () -> ModuleDescriptor.JSON.readValue(
                json.replace("\"aggregateScope\":\"TENANT\"", "\"aggregateScope\":null"), ModuleDescriptor.class));
        var unsupported = ModuleDescriptor.JSON.readValue(
                json.replace("\"entityOwnershipVersion\":1", "\"entityOwnershipVersion\":0"), ModuleDescriptor.class);
        var error = assertThrows(IllegalArgumentException.class,
                () -> ModuleContracts.checkDependencies(List.of(unsupported)));
        assertTrue(error.getMessage().contains("entity ownership/restoration contract"));
    }
}
