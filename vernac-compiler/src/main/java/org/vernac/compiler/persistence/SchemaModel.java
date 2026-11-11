// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import java.io.IOException;
import java.util.*;

/** Versioned, deterministic source artifact. Lists are ordered by logical identity. */
public record SchemaModel(int formatVersion, int mappingVersion, List<Table> tables,
                          Map<String, Map<String, String>> enumCodes) {
    public static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            // Fingerprints must not depend on the producing JVM's operating system.
            // Keep the existing LF representation, including inline array indentation.
            .setDefaultPrettyPrinter(new DefaultPrettyPrinter()
                    .withObjectIndenter(new DefaultIndenter("  ", "\n")));
    public SchemaModel {
        if ((formatVersion != 1 && formatVersion != 2 && formatVersion != 3) || mappingVersion != 1) throw new IllegalArgumentException("Unsupported schema/mapping version");
        tables = tables.stream().sorted(Comparator.comparing(Table::identity)).toList();
        Map<String, Map<String, String>> copy = new TreeMap<>();
        enumCodes.forEach((k,v) -> copy.put(k, Collections.unmodifiableMap(new TreeMap<>(v))));
        enumCodes = Collections.unmodifiableMap(copy);
        Set<String> identities = new HashSet<>();
        Map<String, String> schemas = new HashMap<>();
        Set<String> relations = new HashSet<>();
        for (Table table : tables) {
            if (formatVersion == 1 && table.tenantIsolation() != null)
                throw new IllegalArgumentException("Tenant classification requires schema format 2");
            if (formatVersion < 3 && table.eventIsolation() != null)
                throw new IllegalArgumentException("Event origin classification requires schema format 3");
            if (!identities.add(table.identity())) throw new IllegalArgumentException("Duplicate table " + table.identity());
            String physical = SqlNames.physical(table.namespace());
            String old = schemas.putIfAbsent(physical, table.namespace());
            if (old != null && !old.equals(table.namespace())) throw new IllegalArgumentException("SQL schema name collision");
            if (!relations.add(physical + "/" + SqlNames.physical(table.name()))) throw new IllegalArgumentException("SQL table name collision");
            if (!relations.add(physical + "/" + SqlNames.physical("@pk:" + table.name()))) throw new IllegalArgumentException("SQL index name collision");
            for (Constraint c : table.constraints()) if (c.clause().startsWith("UNIQUE ") && !relations.add(physical + "/" + SqlNames.physical(c.name())))
                throw new IllegalArgumentException("SQL unique-index name collision");
        }
        for (Table source : tables) if (source.tenantIsolation() != null) {
            var isolation = source.tenantIsolation();
            long roots = tables.stream().filter(t -> t.tenantIsolation() != null
                    && t.tenantIsolation().root() && t.tenantIsolation().aggregate().equals(isolation.aggregate())
                    && t.origin().equals(isolation.aggregate())).count();
            if (roots != 1) throw new IllegalArgumentException("Tenant graph requires exactly one matching root: " + isolation.aggregate());
        }
        for (Table source : tables) for (Constraint c : source.constraints()) if (c.clause().startsWith("FOREIGN KEY")) {
            List<Table> targets = tables.stream().filter(t -> c.clause().contains("REFERENCES " + t.sqlName() + " (")).toList();
            if (source.tenantIsolation() != null && targets.size() != 1)
                throw new IllegalArgumentException("Tenant foreign key requires a known owned target: " + source.identity());
            for (Table target : targets) {
                var from = source.tenantIsolation(); var to = target.tenantIsolation();
                if ((from != null || to != null) && (from == null || to == null || !from.aggregate().equals(to.aggregate())))
                    throw new IllegalArgumentException("Foreign key crosses tenant graph boundary: " + source.identity());
            }
        }
        EventSchema.validateGraph(tables);
    }
    public static SchemaModel empty() { return new SchemaModel(1, 1, List.of(), Map.of()); }
    public String json() {
        try { return JSON.writeValueAsString(this) + "\n"; }
        catch (IOException e) { throw new IllegalStateException(e); }
    }
    public static SchemaModel parse(String json) throws IOException { return JSON.readValue(json, SchemaModel.class); }
    public String fingerprint() { return SqlNames.hash(json()); }
    public record Column(String name, String sqlType, boolean nullable, String codec, String origin) { }
    public record Constraint(String name, String clause) { }
    public record TenantIsolation(String aggregate, boolean root) {
        public TenantIsolation {
            if (aggregate == null || aggregate.isBlank()) throw new IllegalArgumentException("Tenant aggregate identity is required");
        }
    }
    public record EventIsolation(String event, boolean root) {
        public EventIsolation {
            if (event == null || event.isBlank()) throw new IllegalArgumentException("Event identity is required");
        }
    }
    public record Table(String namespace, String name, String origin, List<Column> columns,
                        List<String> primaryKey, List<Constraint> constraints,
                        @JsonInclude(JsonInclude.Include.NON_NULL) TenantIsolation tenantIsolation,
                        @JsonInclude(JsonInclude.Include.NON_NULL) EventIsolation eventIsolation) {
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
            for (Column column : columns) if (!names.add(SqlNames.physical(column.name())))
                throw new IllegalArgumentException("SQL column collision: " + origin + "." + column.name());
            if (tenantIsolation != null && eventIsolation != null)
                throw new IllegalArgumentException("A table cannot be both an aggregate and event graph");
            if (tenantIsolation != null) TenantSchema.validate(columns, primaryKey, constraints, tenantIsolation);
            if (eventIsolation != null) EventSchema.validateTable(namespace, name, origin, columns, primaryKey, constraints, eventIsolation);
            names.clear();
            for (Constraint c : constraints) if (!names.add(SqlNames.physical(c.name())))
                throw new IllegalArgumentException("SQL constraint collision: " + c.name());
        }
        public String identity() { return namespace + "/" + name; }
        public String sqlName() { return SqlNames.table(namespace, name); }
    }
}
