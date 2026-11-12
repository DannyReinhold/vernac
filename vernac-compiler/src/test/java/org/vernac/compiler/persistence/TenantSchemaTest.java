// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.persistence;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.vernac.compiler.persistence.SchemaModel.*;

class TenantSchemaTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    private static final String AGGREGATE = "example.billing.Invoice";
    static Table plainRoot() {
        return new Table("example.billing", "Invoice", AGGREGATE,
                List.of(new Column("id", "UUID", false, "UUID", AGGREGATE)), List.of("id"), List.of());
    }
    static Table root() { return TenantSchema.table(plainRoot(), AGGREGATE, true, List.of()); }
    static Table child() {
        var plain = new Table("example.billing", "Invoice.lines", AGGREGATE + ".lines",
                List.of(new Column("@ownerId", "UUID", false, "collection-owner", AGGREGATE),
                        new Column("@position", "INTEGER", false, "list-position", AGGREGATE)),
                List.of("@ownerId", "@position"), List.of());
        return TenantSchema.table(plain, AGGREGATE, false, List.of(TenantSchema.foreignKey(
                "@fk:owner", List.of("@ownerId"), "example.billing", "Invoice", List.of("id"), true)));
    }
    static SchemaModel model(Table... tables) { return new SchemaModel(2, 1, List.of(tables), Map.of()); }

    @Test void classificationRoundTripsWithoutChangingLegacySnapshotBytes() throws Exception {
        var legacy = new SchemaModel(1, 1, List.of(plainRoot()), Map.of());
        assertFalse(legacy.json().contains("tenantIsolation"));
        assertEquals(legacy, SchemaModel.parse(legacy.json()));
        assertEquals(legacy.fingerprint(), SchemaModel.parse(legacy.json()).fingerprint());
        var scoped = model(root(), child());
        assertTrue(scoped.json().contains("tenantIsolation"));
        assertEquals(scoped, SchemaModel.parse(scoped.json()));
        assertEquals(scoped.fingerprint(), SchemaModel.parse(scoped.json()).fingerprint());
        assertThrows(IllegalArgumentException.class, () -> new SchemaModel(1, 1, List.of(root()), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new SchemaModel(4, 1, List.of(), Map.of()));
    }

    @Test void newTenantGraphKeepsRootIdentityAndOrderedChildKeysAndAddsRls() {
        assertEquals(List.of("id"), root().primaryKey());
        assertEquals(List.of("@tenantId", "@ownerId", "@position"), child().primaryKey());
        var plan = new MigrationPlanner().plan(SchemaModel.empty(), model(root(), child()), MigrationPlanner.Options.safe());
        assertTrue(plan.changed());
        assertTrue(plan.risks().isEmpty());
        assertTrue(plan.sql().contains("FOREIGN KEY (\"@tenantId\", \"@ownerId\")"));
        assertTrue(plan.sql().contains("DEFERRABLE INITIALLY DEFERRED"));
        assertEquals(2, plan.sql().split("ENABLE ROW LEVEL SECURITY", -1).length - 1);
        assertEquals(2, plan.sql().split("FORCE ROW LEVEL SECURITY", -1).length - 1);
        assertEquals(2, plan.sql().split("CREATE POLICY", -1).length - 1);
        assertTrue(plan.sql().contains("WITH CHECK"));
        assertTrue(plan.sql().contains("current_setting('vernac.tenant_id', true)"));
        var unchanged = new MigrationPlanner().plan(model(root(), child()), model(root(), child()), MigrationPlanner.Options.safe());
        assertFalse(unchanged.changed());
        assertFalse(unchanged.sql().contains("CREATE POLICY"));
    }

    @Test void historyCanAddNewTenantTablesAfterLegacySnapshotsWithoutRewritingHistory() throws Exception {
        var legacyTable = new Table("legacy", "Global", "legacy.Global", plainRoot().columns(), List.of("id"), List.of());
        var legacy = new SchemaModel(1, 1, List.of(legacyTable), Map.of());
        var next = model(legacyTable, root(), child());
        var planner = new MigrationPlanner();
        var history = new SchemaHistory(directory.resolve("history"), directory.resolve("sql"));
        history.append("20261111090000000", "initial", SchemaModel.empty(), legacy,
                planner.plan(SchemaModel.empty(), legacy, MigrationPlanner.Options.safe()));
        var first = directory.resolve("history/V20261111090000000__initial.json");
        String original = java.nio.file.Files.readString(first);
        history.append("20261111100000000", "new_tenant_graph", legacy, next,
                planner.plan(legacy, next, MigrationPlanner.Options.safe()));
        assertEquals(original, java.nio.file.Files.readString(first));
        assertEquals(next, history.read());
    }

    @Test void checkedInExampleHistoryRetainsPublishedFingerprints() throws Exception {
        var example = java.nio.file.Path.of("..", "examples", "persistence-demo");
        var history = new SchemaHistory(example.resolve("src/main/vernac-schema/history"),
                example.resolve("src/main/resources/db/migration"));
        var model = history.read();
        assertFalse(model.tables().isEmpty());
        assertEquals(3, model.formatVersion());
        assertTrue(model.tables().stream().allMatch(t -> t.tenantIsolation() == null));
        assertTrue(model.tables().stream().anyMatch(t -> t.eventIsolation() != null));
        var published = java.util.Map.of(
                "V20261009090000000__initial_delivery.json", "3ad6cac985d551da8b7001f112fe4c894996abc1b659f53e257feaa1acc9370c",
                "V20261011100000000__event_walkthrough.json", "860d8ee0c8c39b02a396df4a0f7b63e4a467d13a17ef47a99874e9bcb3053bbf");
        for (var entry : published.entrySet()) {
            var snapshot = SchemaModel.JSON.readValue(java.nio.file.Files.readString(
                    example.resolve("src/main/vernac-schema/history").resolve(entry.getKey())), SchemaHistory.Snapshot.class);
            assertEquals(1, snapshot.model().formatVersion());
            assertEquals(entry.getValue(), snapshot.modelFingerprint());
            assertEquals(entry.getValue(), snapshot.model().fingerprint());
            assertTrue(snapshot.model().tables().stream().allMatch(t -> t.eventIsolation() == null));
        }
    }

    @Test void classificationChangesFailClosedEvenWhenForceIsRequested() {
        var unscoped = new SchemaModel(1, 1, List.of(plainRoot()), Map.of());
        var scoped = model(root());
        for (boolean force : List.of(false, true)) {
            var options = new MigrationPlanner.Options(force, Map.of(), Map.of());
            var removal = assertThrows(IllegalArgumentException.class, () -> new MigrationPlanner().plan(scoped, unscoped, options));
            assertTrue(removal.getMessage().contains("TENANT ISOLATION REMOVAL"));
            assertTrue(removal.getMessage().contains("example.billing/Invoice"));
            assertTrue(removal.getMessage().contains("formerly isolated tenants"));
            var addition = assertThrows(IllegalArgumentException.class, () -> new MigrationPlanner().plan(unscoped, scoped, options));
            assertTrue(addition.getMessage().contains("TENANT ASSIGNMENT REQUIRED"));
        }
    }

    @Test void incompleteAndCrossBoundaryGraphsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> model(child()));
        assertThrows(IllegalArgumentException.class, () -> model(plainRoot(), child()));
        var differentRoot = TenantSchema.table(new Table("other", "Root", "other.Root",
                plainRoot().columns(), List.of("id"), List.of()), "other.Root", true, List.of());
        var cross = TenantSchema.table(new Table("other", "Child", "other.Child",
                child().columns().stream().filter(c -> !c.name().equals("@tenantId")).toList(),
                List.of("@ownerId", "@position"), List.of()), "other.Root", false, child().constraints());
        assertThrows(IllegalArgumentException.class, () -> model(root(), differentRoot, cross));
        assertThrows(IllegalArgumentException.class, () -> TenantSchema.table(plainRoot(), AGGREGATE, true,
                List.of(new Constraint("@fk:bad", "FOREIGN KEY (\"id\") REFERENCES \"example.billing\".\"Invoice\" (\"id\")"))));
        assertThrows(IllegalArgumentException.class, () -> new Table(root().namespace(), root().name(),
                root().origin(), plainRoot().columns(), root().primaryKey(), root().constraints(), root().tenantIsolation()));
    }
}
