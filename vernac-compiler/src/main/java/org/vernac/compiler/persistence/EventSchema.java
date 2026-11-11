// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.persistence;

import java.util.*;
import org.vernac.runtime.persistence.SchemaDescription;
import static org.vernac.compiler.persistence.SchemaModel.*;
import static org.vernac.compiler.persistence.SqlNames.*;

/** Version-3 mixed-origin payload schema vocabulary. Selection by code generation is a separate step. */
public final class EventSchema {
    public static final String TENANT = "@tenantId", SCOPE = "@scopeKey", POLICY = "@event-origin";
    private EventSchema() {}

    public static Table table(Table plain, String event, boolean root, List<Constraint> qualifiedConstraints) {
        if (plain.tenantIsolation() != null || plain.eventIsolation() != null
                || plain.columns().stream().anyMatch(c -> c.name().equals(TENANT) || c.name().equals(SCOPE)))
            throw new IllegalArgumentException("Table already has isolation metadata or reserved origin columns");
        var columns = new ArrayList<>(plain.columns());
        columns.add(new Column(TENANT, "UUID", true, "event-tenant-id", event));
        columns.add(new Column(SCOPE, "TEXT", false, "event-scope-key", event));
        var constraints = new ArrayList<>(qualifiedConstraints);
        constraints.add(new Constraint("@check:event-origin", "CHECK (\"@scopeKey\" = CASE WHEN \"@tenantId\" IS NULL THEN 'GLOBAL' ELSE 'TENANT:' || \"@tenantId\"::text END)"));
        if (root) constraints.add(new Constraint("@unique:event-origin:" + plain.name(), "UNIQUE (\"@scopeKey\", \"@eventId\")"));
        if (columns.stream().anyMatch(c -> c.name().equals("@rowId")))
            constraints.add(new Constraint("@unique:event-row:" + plain.name(), "UNIQUE (\"@scopeKey\", \"@rowId\")"));
        // Keep existing keys: event IDs and generated collection-row IDs are globally unique.
        // Scope-qualified FKs close the nullable-tenant gap without rewriting those identities.
        return new Table(plain.namespace(), plain.name(), plain.origin(), columns, plain.primaryKey(), constraints,
                null, new EventIsolation(event, root));
    }

    public static Constraint foreignKey(String constraint, List<String> from, String namespace,
                                        String table, List<String> to, boolean deferred) {
        if (from.isEmpty() || from.size() != to.size() || from.contains(SCOPE) || to.contains(SCOPE))
            throw new IllegalArgumentException("Invalid event relationship columns");
        var source = new ArrayList<>(from); source.addFirst(SCOPE);
        var target = new ArrayList<>(to); target.addFirst(SCOPE);
        return new Constraint(constraint, "FOREIGN KEY (" + names(source) + ") REFERENCES "
                + SqlNames.table(namespace, table) + " (" + names(target) + ")"
                + (deferred ? " DEFERRABLE INITIALLY DEFERRED" : ""));
    }

    public static List<String> protectionSql(Table table) {
        if (table.eventIsolation() == null) return List.of();
        String selected = "NULLIF(current_setting('vernac.tenant_id', true), '')::uuid";
        return List.of("ALTER TABLE " + table.sqlName() + " ENABLE ROW LEVEL SECURITY;",
                "ALTER TABLE " + table.sqlName() + " FORCE ROW LEVEL SECURITY;",
                "CREATE POLICY " + name(POLICY) + " ON " + table.sqlName()
                        + " USING (" + name(TENANT) + " IS NULL OR " + name(TENANT) + " = " + selected + ")"
                        + " WITH CHECK (" + name(TENANT) + " IS NOT DISTINCT FROM " + selected + ");");
    }

    static boolean legacyUpgrade(Table before, Table after) {
        if (Objects.equals(before.eventIsolation(), after.eventIsolation())) return false;
        var scope = after.eventIsolation();
        if (before.eventIsolation() != null || scope == null || before.tenantIsolation() != null)
            throw new IllegalArgumentException("Event origin removal/ownership changes require an explicit migration; force cannot bypass it: " + before.identity());
        String eventName = "@event:" + scope.event().substring(scope.event().lastIndexOf('.') + 1);
        String eventNamespace = scope.event().substring(0, Math.max(0, scope.event().lastIndexOf('.')));
        boolean nameMatches = scope.root() ? before.name().equals(eventName) : before.name().startsWith(eventName + ".");
        if (!before.namespace().equals(eventNamespace) || !nameMatches || !before.origin().equals(after.origin())
                || before.columns().stream().anyMatch(c -> c.name().equals(TENANT) || c.name().equals(SCOPE)))
            throw new IllegalArgumentException("Only an existing unscoped event graph can acquire event origin metadata: " + before.identity());
        return true;
    }

    static void validateTable(String namespace, String name, String origin, List<Column> columns,
                              List<String> key, List<Constraint> constraints, EventIsolation isolation) {
        runtime(new TableParts(namespace, name, origin, columns, key, constraints, isolation));
    }
    static void validateGraph(List<Table> tables) {
        if (tables.stream().noneMatch(t -> t.eventIsolation() != null)) return;
        new SchemaDescription(3, 1, tables.stream().map(t -> runtime(new TableParts(t.namespace(), t.name(), t.origin(),
                t.columns(), t.primaryKey(), t.constraints(), t.eventIsolation()), t.tenantIsolation())).toList(), Map.of());
    }
    private record TableParts(String namespace, String name, String origin, List<Column> columns,
                              List<String> key, List<Constraint> constraints, EventIsolation isolation) {}
    private static SchemaDescription.Table runtime(TableParts table) { return runtime(table, null); }
    private static SchemaDescription.Table runtime(TableParts t, TenantIsolation tenant) {
        return new SchemaDescription.Table(t.namespace(), t.name(), t.origin(), t.columns().stream()
                .map(c -> new SchemaDescription.Column(c.name(), c.sqlType(), c.nullable(), c.codec(), c.origin())).toList(),
                t.key(), t.constraints().stream().map(c -> new SchemaDescription.Constraint(c.name(), c.clause())).toList(),
                tenant == null ? null : new SchemaDescription.TenantIsolation(tenant.aggregate(), tenant.root()),
                t.isolation() == null ? null : new SchemaDescription.EventIsolation(t.isolation().event(), t.isolation().root()));
    }
    private static String names(List<String> columns) { return String.join(", ", columns.stream().map(SqlNames::name).toList()); }
}
