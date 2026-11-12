// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.persistence;

import java.util.*;
import static org.vernac.runtime.persistence.SchemaDescription.*;
import static org.vernac.runtime.persistence.SqlIdentifiers.name;

/** Structural event-origin checks shared with compiler metadata; does not enable event delivery. */
final class EventSchemaValidation {
    private EventSchemaValidation() {}

    static void table(List<Column> columns, List<String> key, List<Constraint> constraints, EventIsolation scope) {
        requireColumn(columns, "@tenantId", "UUID", true, "event-tenant-id", scope.event());
        requireColumn(columns, "@scopeKey", "TEXT", false, "event-scope-key", scope.event());
        String check = "CHECK (\"@scopeKey\" = CASE WHEN \"@tenantId\" IS NULL THEN 'GLOBAL' ELSE 'TENANT:' || \"@tenantId\"::text END)";
        if (constraints.stream().noneMatch(c -> c.clause().equals(check)))
            throw new IllegalArgumentException("Event scope key must match its nullable tenant discriminator");
        if (scope.root()) requireColumn(columns, "@eventId", "UUID", false, "EventId", scope.event());
        if (scope.root() && (!key.equals(List.of("@eventId")) || constraints.stream().noneMatch(c ->
                c.clause().equals("UNIQUE (\"@scopeKey\", \"@eventId\")"))))
            throw new IllegalArgumentException("Event root retains its global primary key and scope-qualified unique key");
        long parentLinks = constraints.stream().filter(c -> c.clause().startsWith("FOREIGN KEY")).count();
        if (scope.root() ? parentLinks != 0 : parentLinks != 1)
            throw new IllegalArgumentException("Only event children have exactly one scope-qualified parent relationship");
        if (!scope.root()) {
            boolean rowIdentity = columns.stream().anyMatch(c -> c.name().equals("@rowId") && c.sqlType().equals("UUID") && !c.nullable());
            boolean uniqueRow = key.equals(List.of("@rowId")) || constraints.stream().anyMatch(c -> c.clause().equals("UNIQUE (\"@rowId\")"));
            if (!rowIdentity || !uniqueRow || key.isEmpty())
                throw new IllegalArgumentException("Event children retain their global collection-row identity");
        }
        for (var c : constraints) if (c.clause().startsWith("FOREIGN KEY")) {
            int reference = c.clause().indexOf(" REFERENCES ");
            String prefix = "(" + name("@scopeKey") + ", ";
            if (!c.clause().startsWith("FOREIGN KEY " + prefix) || reference < 0
                    || !c.clause().substring(reference).contains(" " + prefix))
                throw new IllegalArgumentException("Event foreign key must include non-null scope on both sides");
        }
    }

    private static void requireColumn(List<Column> columns, String name, String sql, boolean nullable, String codec, String event) {
        if (columns.stream().noneMatch(c -> c.name().equals(name) && c.sqlType().equals(sql)
                && c.nullable() == nullable && c.codec().equals(codec) && c.origin().equals(event)))
            throw new IllegalArgumentException("Invalid event origin column: " + name);
    }

    static void graph(List<Table> tables) {
        Map<String,Set<String>> parents = new HashMap<>();
        for (var source : tables) {
            var scope = source.eventIsolation();
            if (scope != null) {
                long roots = tables.stream().filter(t -> t.eventIsolation() != null && t.eventIsolation().root()
                        && t.eventIsolation().event().equals(scope.event()) && t.origin().equals(scope.event())).count();
                if (roots != 1) throw new IllegalArgumentException("Event graph requires exactly one matching root: " + scope.event());
            }
            for (var constraint : source.constraints()) if (constraint.clause().startsWith("FOREIGN KEY")) {
                var targets = tables.stream().filter(t -> constraint.clause().contains("REFERENCES " + t.sqlName() + " (")).toList();
                if (scope != null && targets.size() != 1)
                    throw new IllegalArgumentException("Event relationship requires a known owned target");
                for (var target : targets) {
                    var to = target.eventIsolation();
                    if ((scope != null || to != null) && (scope == null || to == null || !scope.event().equals(to.event())))
                        throw new IllegalArgumentException("Foreign key crosses event graph boundary");
                    if (scope != null) parents.computeIfAbsent(source.identity(), k -> new HashSet<>()).add(target.identity());
                }
            }
        }
        for (var table : tables) if (table.eventIsolation() != null && !table.eventIsolation().root()) {
            String root = tables.stream().filter(t -> t.eventIsolation() != null && t.eventIsolation().root()
                    && t.eventIsolation().event().equals(table.eventIsolation().event())).findFirst().orElseThrow().identity();
            if (!reaches(table.identity(), root, parents, new HashSet<>()))
                throw new IllegalArgumentException("Event child is not connected to its event root");
        }
    }

    private static boolean reaches(String node, String root, Map<String,Set<String>> parents, Set<String> visited) {
        if (node.equals(root)) return true;
        if (!visited.add(node)) return false;
        return parents.getOrDefault(node, Set.of()).stream().anyMatch(parent -> reaches(parent, root, parents, visited));
    }
}
