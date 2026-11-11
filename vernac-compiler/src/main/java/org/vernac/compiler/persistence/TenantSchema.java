// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.persistence;

import java.util.ArrayList;
import java.util.List;
import static org.vernac.compiler.persistence.SchemaModel.*;
import static org.vernac.compiler.persistence.SqlNames.*;

/** Shared technical schema vocabulary. Not yet selected by source-level aggregate generation. */
public final class TenantSchema {
    public static final String COLUMN = "@tenantId";
    public static final String POLICY = "@tenant";
    private TenantSchema() {}

    /** Constraints must already refer to tenant-qualified targets in the same owned graph. */
    public static Table table(Table unscoped, String aggregate, boolean root, List<Constraint> constraints) {
        if (unscoped.tenantIsolation() != null || unscoped.columns().stream().anyMatch(c -> c.name().equals(COLUMN))) {
            throw new IllegalArgumentException("Table is already tenant-classified");
        }
        var columns = new ArrayList<>(unscoped.columns());
        columns.add(new Column(COLUMN, "UUID", false, "tenant-id", aggregate));
        var key = new ArrayList<>(unscoped.primaryKey());
        var qualified = new ArrayList<>(constraints);
        if (root) {
            qualified.add(new Constraint("@unique:tenant:" + unscoped.name(),
                    "UNIQUE (" + name(COLUMN) + ", " + name("id") + ")"));
        } else key.addFirst(COLUMN);
        return new Table(unscoped.namespace(), unscoped.name(), unscoped.origin(),
                columns, key, qualified, new TenantIsolation(aggregate, root));
    }

    /** Constructs an owned FK without parsing/replacing an existing SQL clause. */
    public static Constraint foreignKey(String constraint, List<String> from, String namespace,
                                        String table, List<String> to, boolean deferred) {
        if (from.isEmpty() || from.size() != to.size() || from.contains(COLUMN) || to.contains(COLUMN)) {
            throw new IllegalArgumentException("Invalid tenant foreign-key columns");
        }
        var source = new ArrayList<>(from); source.addFirst(COLUMN);
        var target = new ArrayList<>(to); target.addFirst(COLUMN);
        return new Constraint(constraint, "FOREIGN KEY (" + names(source) + ") REFERENCES "
                + SqlNames.table(namespace, table) + " (" + names(target) + ")"
                + (deferred ? " DEFERRABLE INITIALLY DEFERRED" : ""));
    }

    static void validate(List<Column> columns, List<String> key, List<Constraint> constraints, TenantIsolation isolation) {
        if (columns.stream().noneMatch(c -> c.name().equals(COLUMN) && c.sqlType().equals("UUID")
                && !c.nullable() && c.codec().equals("tenant-id") && c.origin().equals(isolation.aggregate()))) {
            throw new IllegalArgumentException("Tenant table requires a non-null technical UUID discriminator");
        }
        if (isolation.root()) {
            if (!key.equals(List.of("id")) || constraints.stream().noneMatch(c ->
                    c.clause().equals("UNIQUE (" + name(COLUMN) + ", " + name("id") + ")"))) {
                throw new IllegalArgumentException("Tenant root retains global id primary key and tenant-qualified unique key");
            }
        } else if (key.isEmpty() || !key.getFirst().equals(COLUMN)) {
            throw new IllegalArgumentException("Tenant owned-table primary key must start with tenant discriminator");
        }
        for (Constraint c : constraints) if (c.clause().startsWith("FOREIGN KEY")) {
            int reference = c.clause().indexOf(" REFERENCES ");
            String prefix = "(" + name(COLUMN) + ", ";
            if (!c.clause().startsWith("FOREIGN KEY " + prefix) || reference < 0
                    || !c.clause().substring(reference).contains(" " + prefix)) {
                throw new IllegalArgumentException("Owned foreign key must include tenant on both sides: " + c.name());
            }
        }
    }

    public static List<String> protectionSql(Table table) {
        if (table.tenantIsolation() == null) return List.of();
        String predicate = name(COLUMN) + " = NULLIF(current_setting('vernac.tenant_id', true), '')::uuid";
        return List.of("ALTER TABLE " + table.sqlName() + " ENABLE ROW LEVEL SECURITY;",
                "ALTER TABLE " + table.sqlName() + " FORCE ROW LEVEL SECURITY;",
                "CREATE POLICY " + name(POLICY) + " ON " + table.sqlName()
                        + " USING (" + predicate + ") WITH CHECK (" + predicate + ");");
    }

    private static String names(List<String> columns) {
        return String.join(", ", columns.stream().map(SqlNames::name).toList());
    }
}
