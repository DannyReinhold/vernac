// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.persistence;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.vernac.runtime.persistence.SchemaDescription.*;

class TenantSchemaDescriptionTest {
    private Table root(String name) {
        String aggregate = "example." + name;
        return new Table("example", name, aggregate,
                List.of(new Column("id", "UUID", false, "UUID", aggregate + ".id"),
                        new Column("@tenantId", "UUID", false, "tenant-id", aggregate)),
                List.of("id"), List.of(new Constraint("@unique:tenant", "UNIQUE (\"@tenantId\", \"id\")")),
                new TenantIsolation(aggregate, true));
    }

    @Test void tenantBoundaryRequiresFormatTwoAndRemainsImmutable() {
        var tables = new ArrayList<>(List.of(root("Root")));
        var description = new SchemaDescription(2, 1, tables, Map.of()); tables.clear();
        assertEquals("example.Root", description.tables().getFirst().tenantIsolation().aggregate());
        assertThrows(UnsupportedOperationException.class, () -> description.tables().clear());
        assertThrows(IllegalArgumentException.class, () -> new SchemaDescription(1, 1, description.tables(), Map.of()));
    }

    @Test void incompleteDiscriminatorsAndAmbiguousRootsAreRejected() {
        var root = root("Root");
        assertThrows(IllegalArgumentException.class, () -> new Table(root.namespace(), root.name(), root.origin(),
                List.of(root.columns().stream().filter(c -> c.name().equals("id")).findFirst().orElseThrow()),
                root.primaryKey(), root.constraints(), root.tenantIsolation()));
        var second = new Table(root.namespace(), "Another", root.origin(), root.columns(),
                root.primaryKey(), root.constraints(), root.tenantIsolation());
        assertThrows(IllegalArgumentException.class, () -> new SchemaDescription(2, 1, List.of(root, second), Map.of()));
    }

    @Test void ownedForeignKeysCannotCrossTenantAggregateGraphs() {
        var root = root("Root"); var other = root("Other");
        var link = new Table("example", "Link", "link", root.columns(), List.of("@tenantId", "id"),
                List.of(new Constraint("@fk", "FOREIGN KEY (\"@tenantId\", \"id\") REFERENCES \"example\".\"Other\" (\"@tenantId\", \"id\")")),
                new TenantIsolation("example.Root", false));
        assertThrows(IllegalArgumentException.class, () -> new SchemaDescription(2, 1, List.of(root, other, link), Map.of()));
    }
}
