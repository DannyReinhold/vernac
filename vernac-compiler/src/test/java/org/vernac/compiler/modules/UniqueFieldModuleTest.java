// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.modules;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.ast.AggregateNode;
import org.vernac.compiler.persistence.SchemaModel;
import org.vernac.compiler.pipeline.*;

import java.nio.file.*;
import java.util.*;

class UniqueFieldModuleTest {
    @TempDir Path root;

    ModuleDescriptor publish(String modifier) throws Exception {
        var source = root.resolve("producer/src/main/vernac/lib/claims/model.vernac");
        Files.createDirectories(source.getParent());
        Files.writeString(
                source,
                "namespace lib.claims; id ClaimId; value LoginKey(String); global aggregate"
                    + " Claim[ClaimId]("
                        + modifier
                        + "LoginKey);");
        var compiler = new VernacCompiler();
        var p = compiler.analyzeProject(root.resolve("producer/src/main/vernac"));
        var generated = compiler.generate(p, SchemaModel.empty(), Map.of());
        return ModuleContracts.publish(
                ModuleDefinition.parse(
                        root.resolve("producer/vernac-module.vmodule"),
                        "module lib.claims {export lib.claims.ClaimId;export"
                            + " lib.claims.LoginKey;export lib.claims.Claim;}"),
                p,
                generated.generatedFiles(),
                "test",
                "claims",
                "1",
                List.of());
    }

    ModuleDescriptor.Definition claim(ModuleDescriptor d) {
        return d.exports().stream()
                .filter(x -> x.identity().equals("lib.claims.Claim"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void publicationRoundTripAndContractViewsPreserveTheFieldRequirement() throws Exception {
        var d = publish("unique ");
        var restored =
                ModuleDescriptor.JSON.readValue(
                        ModuleDescriptor.JSON.writeValueAsString(d), ModuleDescriptor.class);
        assertEquals("module-contracts-12", restored.contractVersion());
        assertTrue(claim(restored).fields().getFirst().unique());
        assertEquals("loginKey", claim(restored).fields().getFirst().name());
        String view = ModuleArtifacts.view(restored, "lib.claims");
        assertTrue(view.contains("unique lib.claims.LoginKey loginKey"), view);
        assertDoesNotThrow(() -> new VernacSourceParser().parse("dependency.vernac", view));
        var consumer = root.resolve("consumer/src/main/vernac/app/model.vernac");
        Files.createDirectories(consumer.getParent());
        Files.writeString(
                consumer, "namespace app; import lib.claims.ClaimId; value Reference(ClaimId);");
        var p =
                new VernacCompiler()
                        .analyzeProject(
                                root.resolve("consumer/src/main/vernac"), List.of(restored));
        var imported =
                p.project().sources().stream()
                        .filter(VernacSourceFile::external)
                        .flatMap(s -> s.unit().definitions().stream())
                        .filter(AggregateNode.class::isInstance)
                        .map(AggregateNode.class::cast)
                        .findFirst()
                        .orElseThrow();
        assertTrue(imported.fields().getFirst().isUnique());
        assertEquals("loginKey", imported.fields().getFirst().name());
    }

    @Test
    void uniquenessAndMutabilityParticipateInCompatibilityFingerprints() throws Exception {
        var ordinary = claim(publish(""));
        var unique = claim(publish("unique "));
        var mutable = claim(publish("unique mut "));
        var reverse = claim(publish("mut unique "));
        assertNotEquals(ordinary.fingerprint(), unique.fingerprint());
        assertTrue(
                ModuleContracts.difference(ordinary, unique).orElseThrow().contains("unique=true"));
        assertNotEquals(unique.fingerprint(), mutable.fingerprint());
        assertEquals(mutable.fingerprint(), reverse.fingerprint());
    }

    @Test
    void oldContractVersionsAreRejectedInsteadOfDroppingUniqueMetadata() throws Exception {
        var d = publish("unique ");
        String old =
                ModuleDescriptor.JSON
                        .writeValueAsString(d)
                        .replace("module-contracts-12", "module-contracts-11");
        assertThrows(
                Exception.class,
                () -> ModuleDescriptor.JSON.readValue(old, ModuleDescriptor.class));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ModuleDescriptor.Definition(
                                "lib.Bad",
                                "VALUE_OBJECT",
                                "lib.domain.Bad",
                                claim(d).fields(),
                                List.of(),
                                "",
                                "",
                                List.of(),
                                Map.of(),
                                "",
                                1,
                                1,
                                ""));
    }
}
