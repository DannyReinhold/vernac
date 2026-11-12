// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.persistence;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.vernac.compiler.persistence.SchemaModel.*;

class EventOriginSchemaTest {
    @TempDir Path directory;
    private Table root() {
        return new Table("events", "@event:Raised", "events.Raised",
                List.of(new Column("@eventId", "UUID", false, "EventId", "events.Raised")), List.of("@eventId"), List.of());
    }
    private Table child() {
        return new Table("events", "@event:Raised.tags", "events.Tags", List.of(
                new Column("@rowId", "UUID", false, "collection-row", "tags"),
                new Column("@ownerId", "UUID", false, "collection-owner", "tags"),
                new Column("@position", "INTEGER", false, "list-position", "tags")),
                List.of("@ownerId", "@position"), List.of(new Constraint("@unique:row", "UNIQUE (\"@rowId\")"), new Constraint("@fk:owner",
                "FOREIGN KEY (\"@ownerId\") REFERENCES \"events\".\"@event:Raised\" (\"@eventId\")")));
    }
    private SchemaModel legacy() { return new SchemaModel(1, 1, List.of(root(), child()), Map.of()); }
    private SchemaModel scoped() {
        return new SchemaModel(3, 1, List.of(EventSchema.table(root(), "events.Raised", true, List.of()),
                EventSchema.table(child(), "events.Raised", false, List.of(new Constraint("@unique:row", "UNIQUE (\"@rowId\")"), EventSchema.foreignKey("@fk:owner",
                        List.of("@ownerId"), "events", "@event:Raised", List.of("@eventId"), false)))), Map.of());
    }
    private MigrationPlanner.Options upgradeOptions() {
        return new MigrationPlanner.Options(false, Map.of("events/@event:Raised/@scopeKey", "'GLOBAL'",
                "events/@event:Raised.tags/@scopeKey", "'GLOBAL'"), Map.of());
    }

    @Test void formatThreeRoundTripsAndDoesNotPolluteLegacySnapshotBytes() throws Exception {
        var legacy = legacy(); var scoped = scoped();
        assertFalse(legacy.json().contains("eventIsolation"));
        assertEquals(legacy.fingerprint(), SchemaModel.parse(legacy.json()).fingerprint());
        assertEquals(scoped, SchemaModel.parse(scoped.json()));
        var runtime = SchemaModel.JSON.readValue(scoped.json(), org.vernac.runtime.persistence.SchemaDescription.class);
        assertEquals(3, runtime.formatVersion());
        assertEquals(2, runtime.tables().stream().filter(t -> t.eventIsolation() != null).count());
        for (int format : List.of(1, 2))
            assertThrows(IllegalArgumentException.class, () -> new SchemaModel(format, 1, scoped.tables(), Map.of()));
    }

    @Test void newGraphKeepsIdentitiesButQualifiesRelationshipsAndInstallsMixedOriginPolicies() {
        var target = scoped();
        assertEquals(root().primaryKey(), target.tables().getFirst().primaryKey());
        var plan = new MigrationPlanner().plan(SchemaModel.empty(), target, MigrationPlanner.Options.safe());
        assertTrue(plan.sql().contains("FOREIGN KEY (\"@scopeKey\", \"@ownerId\")"));
        assertTrue(plan.sql().contains("IS NOT DISTINCT FROM NULLIF(current_setting"));
        assertEquals(2, plan.sql().split("FORCE ROW LEVEL SECURITY", -1).length - 1);
        assertTrue(plan.risks().isEmpty());
        var unchanged = new MigrationPlanner().plan(target, target, MigrationPlanner.Options.safe());
        assertFalse(unchanged.changed()); assertFalse(unchanged.sql().contains("CREATE POLICY"));
    }

    @Test void legacyUpgradeRequiresReviewedGlobalBackfillsAndPreservesSourceHistory() throws Exception {
        var planner = new MigrationPlanner();
        var error = assertThrows(IllegalArgumentException.class, () -> planner.plan(legacy(), scoped(), MigrationPlanner.Options.safe()));
        assertTrue(error.getMessage().contains("Historical tenant attribution cannot be inferred"));
        assertThrows(IllegalArgumentException.class, () -> planner.plan(legacy(), scoped(), new MigrationPlanner.Options(true, Map.of(), Map.of())));
        var plan = planner.plan(legacy(), scoped(), upgradeOptions());
        assertTrue(plan.sql().contains("EVENT ORIGIN UPGRADE"));
        assertTrue(plan.sql().contains("SET \"@scopeKey\" = ('GLOBAL')"));
        assertFalse(plan.sql().contains("DROP TABLE")); assertFalse(plan.sql().contains("DROP COLUMN"));
        assertTrue(plan.sql().indexOf("UNIQUE (\"@scopeKey\", \"@eventId\")") < plan.sql().indexOf("ADD CONSTRAINT \"@fk:owner\""));
        var history = new SchemaHistory(directory.resolve("history"), directory.resolve("sql"));
        history.append("20261111090000000", "initial", SchemaModel.empty(), legacy(), planner.plan(SchemaModel.empty(), legacy(), MigrationPlanner.Options.safe()));
        var first = directory.resolve("history/V20261111090000000__initial.json"); String original = Files.readString(first);
        history.append("20261111100000000", "event_origins", legacy(), scoped(), plan);
        assertEquals(original, Files.readString(first)); assertEquals(scoped(), history.read());
    }

    @Test void originRemovalAndArbitraryReclassificationCannotBeForced() {
        var force = new MigrationPlanner.Options(true, Map.of(), Map.of());
        var planner = new MigrationPlanner();
        assertThrows(IllegalArgumentException.class, () -> planner.plan(scoped(), legacy(), force));
        var unclassified = new Table("events", "NotAnEvent", "events.Raised", root().columns(), root().primaryKey(), List.of());
        var classified = EventSchema.table(unclassified, "events.Raised", true, List.of());
        assertThrows(IllegalArgumentException.class, () -> planner.plan(new SchemaModel(1, 1, List.of(unclassified), Map.of()),
                new SchemaModel(3, 1, List.of(classified), Map.of()), upgradeOptions()));
    }

    @Test void nullableScopeOrUnqualifiedLinksAndCrossGraphRelationshipsAreRejected() {
        var root = scoped().tables().getFirst(); var child = scoped().tables().getLast();
        var nullable = root.columns().stream().map(c -> c.name().equals("@scopeKey")
                ? new Column(c.name(), c.sqlType(), true, c.codec(), c.origin()) : c).toList();
        assertThrows(IllegalArgumentException.class, () -> new Table(root.namespace(), root.name(), root.origin(), nullable,
                root.primaryKey(), root.constraints(), null, root.eventIsolation()));
        assertThrows(IllegalArgumentException.class, () -> EventSchema.table(child(), "events.Raised", false, child().constraints()));
        var foreignRoot = EventSchema.table(new Table("events", "@event:Other", "events.Other", List.of(new Column("@eventId", "UUID", false, "EventId", "events.Other")), root().primaryKey(), List.of()), "events.Other", true, List.of());
        var crossed = EventSchema.table(child(), "events.Raised", false, List.of(new Constraint("@unique:row", "UNIQUE (\"@rowId\")"), EventSchema.foreignKey("@fk:owner",
                List.of("@ownerId"), "events", "@event:Other", List.of("@eventId"), false)));
        assertThrows(IllegalArgumentException.class, () -> new SchemaModel(3, 1, List.of(root, foreignRoot, crossed), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new SchemaModel(3, 1, List.of(child), Map.of()));
    }
    @Test void queuedDeliveryOriginCannotBeRemovedEvenWithForce() {
        var before = new Table("vernac", "@delivery", "@event-delivery", List.of(
                new Column("id", "UUID", false, "UUID", "@event-delivery")), List.of("id"), List.of());
        var columns = new ArrayList<>(before.columns());
        columns.add(new Column("tenantId", "UUID", true, "delivery-tenant-id", "@event-delivery"));
        var after = new Table(before.namespace(), before.name(), before.origin(), columns, before.primaryKey(), before.constraints());
        var old = new SchemaModel(1, 1, List.of(before), Map.of()); var current = new SchemaModel(1, 1, List.of(after), Map.of());
        var planner = new MigrationPlanner(); var upgrade = planner.plan(old, current, MigrationPlanner.Options.safe());
        assertTrue(upgrade.sql().contains("DELIVERY ORIGIN UPGRADE"));
        assertTrue(upgrade.sql().contains("Stop older workers"));
        assertFalse(upgrade.sql().contains("UPDATE "));
        var error = assertThrows(IllegalArgumentException.class, () -> planner.plan(current, old,
                new MigrationPlanner.Options(true, Map.of(), Map.of())));
        assertTrue(error.getMessage().contains("EVENT DELIVERY ORIGIN REMOVAL"));
    }

}
