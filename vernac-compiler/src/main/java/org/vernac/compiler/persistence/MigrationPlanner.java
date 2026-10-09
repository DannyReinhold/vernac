// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.persistence;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import static org.vernac.compiler.persistence.SchemaModel.*;
import static org.vernac.compiler.persistence.SqlNames.*;

/** Generates reviewable forward-only SQL. Never executes SQL or guesses data transformations. */
public final class MigrationPlanner {
    public static final String NOTICE = """
            -- Generated migration candidate. Review before applying.
            -- You are solely responsible for reviewing, testing, and approving this
            -- migration before applying it to any database. Review operation ordering,
            -- existing data, application compatibility, locking, execution time,
            -- backups and recovery procedures. Generation and automated verification
            -- do not guarantee correctness, safety, or suitability for your deployment.
            """;
    public record Options(boolean force, Map<String, String> backfills, Map<String, String> conversions) {
        public Options { backfills = Map.copyOf(backfills); conversions = Map.copyOf(conversions); }
        public static Options safe() { return new Options(false, Map.of(), Map.of()); }
    }
    public record Plan(String sql, List<String> risks, boolean changed) { }
    public Plan plan(SchemaModel before, SchemaModel after, Options options) {
        Map<String, Table> oldTables = index(before.tables(), Table::identity);
        Map<String, Table> newTables = index(after.tables(), Table::identity);
        List<String> risks = new ArrayList<>(), sql = new ArrayList<>(), drops = new ArrayList<>(), adds = new ArrayList<>();
        Set<String> usedBackfills = new HashSet<>(), usedConversions = new HashSet<>();
        // Retain unchanged constraints unless a dependent column changes its SQL
        // type/codec or disappears. Include incoming foreign keys, not only local checks.
        Set<String> rebuild = new HashSet<>();
        for (Table old : before.tables()) {
            Table next = newTables.get(old.identity());
            if (next != null && !old.primaryKey().equals(next.primaryKey()))
                throw new IllegalArgumentException("Primary-key change requires an explicit migration: " + old.identity());
            Map<String, Constraint> nextConstraints = next == null ? Map.of() : index(next.constraints(), Constraint::name);
            for (Constraint c : old.constraints()) {
                if (next == null || !c.equals(nextConstraints.get(c.name()))
                        || dependsOnChangedColumn(old, c, before, newTables)) {
                    rebuild.add(old.identity() + "/" + c.name());
                    String drop = "ALTER TABLE " + old.sqlName() + " DROP CONSTRAINT " + name(c.name()) + ";";
                    // Referencing FKs must disappear before referenced UNIQUE constraints.
                    if (c.clause().startsWith("FOREIGN KEY")) drops.addFirst(drop); else drops.add(drop);
                }
            }
        }
        sql.addAll(drops);
        after.tables().stream().map(Table::namespace).distinct().sorted().forEach(ns -> {
            if (before.tables().stream().noneMatch(t -> t.namespace().equals(ns))) sql.add("CREATE SCHEMA IF NOT EXISTS " + name(ns) + ";");
        });
        for (Table old : before.tables()) if (!newTables.containsKey(old.identity())) {
            risks.add("DROP TABLE " + old.sqlName() + " removes stored data.");
            sql.add("DROP TABLE " + old.sqlName() + ";");
        }
        for (Table next : after.tables()) {
            Table old = oldTables.get(next.identity());
            if (old == null) {
                List<String> items = next.columns().stream().map(c -> "    " + column(c)).collect(Collectors.toCollection(ArrayList::new));
                items.add("    CONSTRAINT " + name("@pk:" + next.name()) + " PRIMARY KEY (" + names(next.primaryKey()) + ")");
                sql.add("CREATE TABLE " + next.sqlName() + " (\n" + String.join(",\n", items) + "\n);");
            } else if (!old.equals(next)) {
                Map<String, Column> oldColumns = index(old.columns(), Column::name), newColumns = index(next.columns(), Column::name);
                for (Column c : old.columns()) if (!newColumns.containsKey(c.name())) {
                    risks.add("DROP COLUMN " + next.sqlName() + "." + name(c.name()) + " removes stored data.");
                    sql.add("ALTER TABLE " + next.sqlName() + " DROP COLUMN " + name(c.name()) + ";");
                }
                for (Column c : next.columns()) {
                    Column previous = oldColumns.get(c.name());
                    String key = next.identity() + "/" + c.name();
                    String alter = "ALTER TABLE " + next.sqlName() + " ALTER COLUMN " + name(c.name());
                    if (previous == null) {
                        sql.add("ALTER TABLE " + next.sqlName() + " ADD COLUMN " + name(c.name()) + " " + c.sqlType() + ";");
                        if (!c.nullable() || options.backfills().containsKey(key)) backfill(sql, next, c, key, options, usedBackfills);
                    } else {
                        if (!previous.sqlType().equals(c.sqlType()) || !previous.codec().equals(c.codec())) {
                            String expression = options.conversions().get(key);
                            if (expression == null && losslessWidening(previous, c)) {
                                if (!previous.sqlType().equals(c.sqlType())) sql.add(alter + " TYPE " + c.sqlType() + ";");
                            } else {
                                if (expression == null || expression.isBlank()) throw new IllegalArgumentException("Explicit SQL conversion required for " + key + "; force cannot invent it.");
                                usedConversions.add(key);
                                risks.add("Conversion of " + key + " may lose data.");
                                sql.add(alter + " TYPE " + c.sqlType() + " USING (" + expression + ");");
                            }
                        }
                        if ((previous.nullable() && !c.nullable()) || options.backfills().containsKey(key)) backfill(sql, next, c, key, options, usedBackfills);
                        if (!previous.nullable() && c.nullable()) sql.add(alter + " DROP NOT NULL;");
                    }
                }
            }
            Map<String, Constraint> oldConstraints = old == null ? Map.of() : index(old.constraints(), Constraint::name);
            for (Constraint c : next.constraints()) if (!c.equals(oldConstraints.get(c.name()))
                    || rebuild.contains(next.identity() + "/" + c.name()))
                adds.add("ALTER TABLE " + next.sqlName() + " ADD CONSTRAINT " + name(c.name()) + " " + c.clause() + ";");
        }
        for (var entry : before.enumCodes().entrySet()) {
            var current = after.enumCodes().getOrDefault(entry.getKey(), Map.of());
            if (!current.values().containsAll(entry.getValue().values()))
                risks.add("Enum storage codes removed from " + entry.getKey() + "; existing rows may require manual transformation.");
        }
        if (!usedBackfills.equals(options.backfills().keySet()) || !usedConversions.equals(options.conversions().keySet()))
            throw new IllegalArgumentException("Unused backfill/conversion keys: check their table-identity/column spelling.");
        // UNIQUE targets first, foreign keys last; all tables/columns now exist.
        adds.sort(Comparator.comparingInt(statement -> statement.contains(" FOREIGN KEY (") ? 1 : 0));
        sql.addAll(adds);
        if (!risks.isEmpty() && !options.force()) throw new IllegalArgumentException("Potential data loss. Review these operations and use force only to continue candidate generation:\n" + String.join("\n", risks));
        String warnings = risks.isEmpty() ? "" : "-- WARNING: force bypasses generation safeguards only; it is no approval or guarantee.\n"
                + risks.stream().map(r -> "-- " + r.replace('\n', ' ').replace('\r', ' ') + "\n").collect(Collectors.joining());
        return new Plan(NOTICE + warnings + "\n" + String.join("\n\n", sql) + "\n", List.copyOf(risks), !before.equals(after));
    }
    private static boolean dependsOnChangedColumn(Table owner, Constraint constraint,
                                                   SchemaModel before, Map<String, Table> after) {
        for (Table previous : before.tables()) {
            boolean local = previous.identity().equals(owner.identity());
            boolean referenced = constraint.clause().startsWith("FOREIGN KEY")
                    && constraint.clause().contains("REFERENCES " + previous.sqlName() + " (");
            if (!local && !referenced) continue;
            Table next = after.get(previous.identity());
            if (next == null) return true;
            Map<String, Column> columns = index(next.columns(), Column::name);
            for (Column old : previous.columns()) {
                Column current = columns.get(old.name());
                if ((current == null || !old.sqlType().equals(current.sqlType()) || !old.codec().equals(current.codec()))
                        && constraint.clause().contains(name(old.name()))) return true;
            }
        }
        return false;
    }
    private static void backfill(List<String> sql, Table table, Column c, String key, Options options, Set<String> used) {
        String expression = options.backfills().get(key);
        if (expression == null || expression.isBlank()) throw new IllegalArgumentException("Required column needs an explicit SQL backfill expression: " + key);
        used.add(key);
        sql.add("UPDATE " + table.sqlName() + " SET " + name(c.name()) + " = (" + expression + ") WHERE " + name(c.name()) + " IS NULL;");
        if (!c.nullable()) sql.add("ALTER TABLE " + table.sqlName() + " ALTER COLUMN " + name(c.name()) + " SET NOT NULL;");
    }
    private static boolean losslessWidening(Column old, Column next) {
        Map<String,Integer> ranks = Map.of("byte:",0,"Byte:",0,"short:",1,"Short:",1,
                "int:",2,"Integer:",2,"long:",3,"Long:",3);
        Integer from = ranks.get(old.codec()), to = ranks.get(next.codec());
        if (from != null && to != null && to > from) return true;
        return Set.of("float:", "Float:").contains(old.codec()) && Set.of("double:", "Double:").contains(next.codec());
    }
    private static String column(Column c) { return name(c.name()) + " " + c.sqlType() + (c.nullable() ? "" : " NOT NULL"); }
    private static String names(List<String> names) { return names.stream().map(SqlNames::name).collect(Collectors.joining(", ")); }
    private static <T> Map<String,T> index(List<T> values, Function<T,String> key) { return values.stream().collect(Collectors.toMap(key, Function.identity(), (a,b) -> { throw new IllegalArgumentException("Duplicate identity"); }, TreeMap::new)); }
}
