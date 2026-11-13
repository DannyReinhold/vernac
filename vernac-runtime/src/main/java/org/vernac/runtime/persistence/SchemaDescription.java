// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.persistence;
import java.util.*;
/** Shared immutable schema data; serialization belongs to compiler/JDBC infrastructure. */
public record SchemaDescription(int formatVersion,int mappingVersion,List<Table> tables,Map<String,Map<String,String>> enumCodes) {
    public SchemaDescription {
        if ((formatVersion != 1 && formatVersion != 2 && formatVersion != 3 && formatVersion != 4) || mappingVersion != 1) throw new IllegalArgumentException("Unsupported schema/mapping version");
        tables = tables.stream().sorted(Comparator.comparing(Table::identity)).toList();
        Map<String, Map<String, String>> copy = new TreeMap<>();
        enumCodes.forEach((k,v) -> copy.put(k, Collections.unmodifiableMap(new TreeMap<>(v))));
        enumCodes = Collections.unmodifiableMap(copy);
        Set<String> identities = new HashSet<>();
        Map<String, String> schemas = new HashMap<>();
        Set<String> relations = new HashSet<>();
        for (Table table : tables) {
            if (formatVersion < 4 && table.columns().stream().anyMatch(c -> c.collation() != null))
                throw new IllegalArgumentException("Column collation requires schema format 4");
            if (formatVersion == 1 && table.tenantIsolation() != null)
                throw new IllegalArgumentException("Tenant classification requires schema format 2");
            if (formatVersion < 3 && table.eventIsolation() != null)
                throw new IllegalArgumentException("Event origin classification requires schema format 3");
            if (!identities.add(table.identity())) throw new IllegalArgumentException("Duplicate table " + table.identity());
            String physical = SqlIdentifiers.physical(table.namespace());
            String old = schemas.putIfAbsent(physical, table.namespace());
            if (old != null && !old.equals(table.namespace())) throw new IllegalArgumentException("SQL schema name collision");
            if (!relations.add(physical + "/" + SqlIdentifiers.physical(table.name()))) throw new IllegalArgumentException("SQL table name collision");
            if (!relations.add(physical + "/" + SqlIdentifiers.physical("@pk:" + table.name()))) throw new IllegalArgumentException("SQL index name collision");
            for (Constraint c : table.constraints()) if (c.clause().startsWith("UNIQUE ") && !relations.add(physical + "/" + SqlIdentifiers.physical(c.name())))
                throw new IllegalArgumentException("SQL unique-index name collision");
        }
        TenantSchemaValidation.graph(tables);
        EventSchemaValidation.graph(tables);
    }
    public record Column(String name, String sqlType, boolean nullable, String codec, String origin,
                         @org.jspecify.annotations.Nullable String collation) {
        public Column(String name, String sqlType, boolean nullable, String codec, String origin) {
            this(name, sqlType, nullable, codec, origin, null);
        }
        public Column {
            if (collation != null && (!collation.equals("C") || !sqlType.equals("TEXT")))
                throw new IllegalArgumentException("Only explicit C collation on TEXT is supported");
        }
    }
    public record Constraint(String name, String clause) { }
    public record TenantIsolation(String aggregate, boolean root) {
        public TenantIsolation {
            if (aggregate == null || aggregate.isBlank())
                throw new IllegalArgumentException("Tenant aggregate identity is required");
        }
    }
    public record EventIsolation(String event, boolean root) {
        public EventIsolation {
            if (event == null || event.isBlank()) throw new IllegalArgumentException("Event identity is required");
        }
    }
    public record Table(String namespace, String name, String origin, List<Column> columns,
                        List<String> primaryKey, List<Constraint> constraints, TenantIsolation tenantIsolation, EventIsolation eventIsolation) {
        public Table(String namespace, String name, String origin, List<Column> columns,
                     List<String> primaryKey, List<Constraint> constraints, TenantIsolation tenantIsolation) {
            this(namespace, name, origin, columns, primaryKey, constraints, tenantIsolation, null);
        }
        public Table(String namespace, String name, String origin, List<Column> columns,
                     List<String> primaryKey, List<Constraint> constraints) {
            this(namespace, name, origin, columns, primaryKey, constraints, null);
        }
        public Table {
            columns = columns.stream().sorted(Comparator.comparing(Column::name)).toList();
            primaryKey = List.copyOf(primaryKey);
            constraints = constraints.stream().sorted(Comparator.comparing(Constraint::name)).toList();
            Set<String> names = new HashSet<>();
            for (Column column : columns) if (!names.add(SqlIdentifiers.physical(column.name())))
                throw new IllegalArgumentException("SQL column collision: " + origin + "." + column.name());
            if (tenantIsolation != null && eventIsolation != null)
                throw new IllegalArgumentException("A table cannot be both an aggregate and event graph");
            if (tenantIsolation != null) TenantSchemaValidation.table(columns, primaryKey, constraints, tenantIsolation);
            if (eventIsolation != null) EventSchemaValidation.table(columns, primaryKey, constraints, eventIsolation);
            names.clear();
            for (Constraint c : constraints) if (!names.add(SqlIdentifiers.physical(c.name())))
                throw new IllegalArgumentException("SQL constraint collision: " + c.name());
        }
        public String identity() { return namespace + "/" + name; }
        public String sqlName() { return SqlIdentifiers.table(namespace, name); }
    }
}
