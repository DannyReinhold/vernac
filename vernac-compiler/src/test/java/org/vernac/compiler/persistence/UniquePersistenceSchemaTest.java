// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.persistence;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.pipeline.VernacCompiler;

import java.nio.file.*;
import java.util.*;

class UniquePersistenceSchemaTest {
    @TempDir Path root;

    SchemaModel schema(String fields, String repository, SchemaModel previous, String scope)
            throws Exception {
        var file = root.resolve("demo/model.vernac");
        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                "namespace demo; id ClaimId; id OwnerId; value Key(String encoded); value State ="
                    + " OPEN | CLOSED; value Number(int); "
                        + scope
                        + " aggregate Claim[ClaimId]("
                        + fields
                        + "); repository "
                        + repository
                        + " for Claim {}");
        return new SchemaBuilder(new VernacCompiler().analyzeProject(root), previous).build();
    }

    SchemaModel schema(String fields) throws Exception {
        return schema(fields, "Claims", SchemaModel.empty(), "global");
    }

    @Test
    void stableDerivedNamesAndScopeUseResolvedStorageColumns() throws Exception {
        var global = schema("unique Key, Key note, unique OwnerId, unique State, unique Number");
        var table = global.tables().getFirst();
        assertEquals(4, global.formatVersion());
        assertEquals(
                4,
                table.constraints().stream().filter(c -> c.name().startsWith("@unique:")).count());
        assertEquals(
                "C",
                table.columns().stream()
                        .filter(c -> c.name().equals("key"))
                        .findFirst()
                        .orElseThrow()
                        .collation());
        assertNull(
                table.columns().stream()
                        .filter(c -> c.name().equals("note"))
                        .findFirst()
                        .orElseThrow()
                        .collation());
        assertEquals(
                global.fingerprint(),
                schema(
                                "unique Number, unique State, unique OwnerId, Key note, unique"
                                    + " demo.Key",
                                "Renamed",
                                SchemaModel.empty(),
                                "global")
                        .fingerprint());
        var tenant = schema("unique Key", "Claims", SchemaModel.empty(), "tenant");
        assertEquals(
                "UNIQUE (\"@tenantId\", \"key\")",
                tenant.tables().getFirst().constraints().stream()
                        .filter(c -> c.name().equals("@unique:Claim:key"))
                        .findFirst()
                        .orElseThrow()
                        .clause());
    }

    @Test
    void explicitCollationRoundTripsWithoutChangingLegacySnapshotBytes() throws Exception {
        var old = schema("Key");
        String bytes = old.json();
        assertEquals(1, old.formatVersion());
        assertFalse(bytes.contains("collation"));
        assertEquals(bytes, SchemaModel.parse(bytes).json());
        var next = schema("unique Key", "Claims", old, "global");
        assertEquals(4, next.formatVersion());
        assertTrue(next.json().contains("\"collation\" : \"C\""));
        assertEquals(next, SchemaModel.parse(next.json()));
        assertNotEquals(old.fingerprint(), next.fingerprint());
        assertThrows(
                IllegalArgumentException.class,
                () -> new SchemaModel(3, 1, next.tables(), next.enumCodes()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SchemaModel.Column("bad", "UUID", false, "UUID", "demo", "C"));
    }

    @Test
    void migrationAddRemoveAndFieldChangeKeepConstraintAndCollationTogether() throws Exception {
        var old = schema("Key");
        var next = schema("unique Key", "Claims", old, "global");
        var planner = new MigrationPlanner();
        var add = planner.plan(old, next, MigrationPlanner.Options.safe()).sql();
        assertTrue(add.contains("TYPE TEXT COLLATE \"pg_catalog\".\"C\""), add);
        assertTrue(add.contains("ADD CONSTRAINT \"@unique:Claim:key\" UNIQUE (\"key\")"), add);
        var removed = schema("Key", "Claims", next, "global");
        assertEquals(4, removed.formatVersion());
        var drop = planner.plan(next, removed, MigrationPlanner.Options.safe()).sql();
        assertTrue(
                drop.indexOf("DROP CONSTRAINT")
                        < drop.indexOf("TYPE TEXT COLLATE \"pg_catalog\".\"default\""),
                drop);
        assertFalse(planner.plan(removed, removed, MigrationPlanner.Options.safe()).changed());
        var replaced = schema("Key, unique Key other", "Claims", next, "global");
        var failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> planner.plan(next, replaced, MigrationPlanner.Options.safe()));
        assertTrue(failure.getMessage().contains("backfill"), failure.getMessage());
    }
}
