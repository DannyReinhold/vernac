// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.persistence;

import java.util.List;
import static org.vernac.runtime.persistence.SchemaDescription.*;
import static org.vernac.runtime.persistence.SqlIdentifiers.name;

/** Validate the data-only runtime copy of compiler-owned tenant schema metadata. */
final class TenantSchemaValidation {
    private TenantSchemaValidation() { }

    static void table(List<Column> columns, List<String> key, List<Constraint> constraints, TenantIsolation isolation) {
        if (columns.stream().noneMatch(c -> c.name().equals("@tenantId") && c.sqlType().equals("UUID")
                && !c.nullable() && c.codec().equals("tenant-id") && c.origin().equals(isolation.aggregate())))
            throw new IllegalArgumentException("Tenant table requires a non-null technical UUID discriminator");
        if (isolation.root()) {
            if (!key.equals(List.of("id")) || constraints.stream().noneMatch(c ->
                    c.clause().equals("UNIQUE (" + name("@tenantId") + ", " + name("id") + ")")))
                throw new IllegalArgumentException("Tenant root retains global id primary key and tenant-qualified unique key");
        } else if (key.isEmpty() || !key.getFirst().equals("@tenantId")) {
            throw new IllegalArgumentException("Tenant owned-table primary key must start with tenant discriminator");
        }
        for (var constraint : constraints) if (constraint.clause().startsWith("FOREIGN KEY")) {
            int reference = constraint.clause().indexOf(" REFERENCES ");
            String prefix = "(" + name("@tenantId") + ", ";
            if (!constraint.clause().startsWith("FOREIGN KEY " + prefix) || reference < 0
                    || !constraint.clause().substring(reference).contains(" " + prefix))
                throw new IllegalArgumentException("Owned foreign key must include tenant on both sides: " + constraint.name());
        }
    }

    static void graph(List<Table> tables) {
        for (var table : tables) {
            var isolation = table.tenantIsolation();
            if (isolation == null) continue;
            long roots = tables.stream().filter(t -> t.tenantIsolation() != null && t.tenantIsolation().root()
                    && t.tenantIsolation().aggregate().equals(isolation.aggregate())
                    && t.origin().equals(isolation.aggregate())).count();
            if (roots != 1) throw new IllegalArgumentException("Tenant graph requires exactly one matching root: " + isolation.aggregate());
        }
        for (var source : tables) for (var constraint : source.constraints()) {
            if (!constraint.clause().startsWith("FOREIGN KEY")) continue;
            var targets = tables.stream().filter(t -> constraint.clause().contains("REFERENCES " + t.sqlName() + " (")).toList();
            if (source.tenantIsolation() != null && targets.size() != 1)
                throw new IllegalArgumentException("Tenant foreign key requires a known owned target: " + source.identity());
            for (var target : targets) {
                var from = source.tenantIsolation(); var to = target.tenantIsolation();
                if ((from != null || to != null) && (from == null || to == null || !from.aggregate().equals(to.aggregate())))
                    throw new IllegalArgumentException("Foreign key crosses tenant graph boundary: " + source.identity());
            }
        }
    }
}
