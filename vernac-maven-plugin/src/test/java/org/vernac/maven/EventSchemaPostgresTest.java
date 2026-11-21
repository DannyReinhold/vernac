// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.vernac.compiler.persistence.*;
import static org.vernac.compiler.persistence.SchemaModel.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real generated DDL and catalog checks, not generated event adapter/worker acceptance. */
class EventSchemaPostgresTest {
    private static final UUID A = UUID.randomUUID(), B = UUID.randomUUID();
    private static final String ROOT = "\"event_scope\".\"@event:Raised\"";
    private static final String CHILD = "\"event_scope\".\"@event:Raised.tags\"";
    private static final String NESTED = "\"event_scope\".\"@event:Raised.tags.parts\"";
    private static final Set<String> OWNED = Set.of("event_scope/@event:Raised", "event_scope/@event:Raised.tags", "event_scope/@event:Raised.tags.parts");

    private static SchemaModel legacy() {
        var root = new Table("event_scope", "@event:Raised", "event_scope.Raised", List.of(
                new Column("@eventId", "UUID", false, "EventId", "event_scope.Raised"),
                new Column("text", "TEXT", false, "String:", "text")), List.of("@eventId"), List.of());
        var child = child("@event:Raised.tags", "@event:Raised", "@eventId");
        var nested = child("@event:Raised.tags.parts", "@event:Raised.tags", "@rowId");
        return new SchemaModel(1, 1, List.of(root, child, nested), Map.of());
    }
    private static Table child(String name, String parent, String ownerKey) {
        return new Table("event_scope", name, "event_scope.Values", List.of(
                new Column("@rowId", "UUID", false, "collection-row", name),
                new Column("@ownerId", "UUID", false, "collection-owner", name),
                new Column("@position", "INTEGER", false, "list-position", name),
                new Column("value", "TEXT", false, "String:", name)), List.of("@ownerId", "@position"),
                List.of(new Constraint("@unique:row:" + name, "UNIQUE (\"@rowId\")"),
                        new Constraint("@fk:owner", "FOREIGN KEY (\"@ownerId\") REFERENCES \"event_scope\".\"" + parent + "\" (\"" + ownerKey + "\")")));
    }
    private static SchemaModel scoped() {
        var tables = new ArrayList<Table>();
        for (var table : legacy().tables()) {
            boolean root = table.name().equals("@event:Raised");
            var constraints = new ArrayList<Constraint>();
            table.constraints().stream().filter(c -> !c.clause().startsWith("FOREIGN KEY")).forEach(constraints::add);
            if (!root) {
                boolean nested = table.name().endsWith(".parts");
                constraints.add(EventSchema.foreignKey("@fk:owner", List.of("@ownerId"), "event_scope",
                        nested ? "@event:Raised.tags" : "@event:Raised", List.of(nested ? "@rowId" : "@eventId"), false));
            }
            tables.add(EventSchema.table(table, "event_scope.Raised", root, constraints));
        }
        return new SchemaModel(3, 1, tables, Map.of());
    }
    private static MigrationPlanner.Options upgrade() {
        Map<String,String> backfills = new HashMap<>(); legacy().tables().forEach(t -> backfills.put(t.identity() + "/@scopeKey", "'GLOBAL'"));
        return new MigrationPlanner.Options(false, backfills, Map.of());
    }

    @FunctionalInterface private interface DatabaseWork { void run(Connection connection) throws Exception; }
    private void database(DatabaseWork action) throws Exception {
        String url = System.getenv("VERNAC_PG_TEST_URL"), database = "vernac_event_schema_" + UUID.randomUUID().toString().replace("-", "");
        var credentials = new Properties();
        for (String key : List.of("user", "password")) {
            String value = System.getenv("VERNAC_PG_TEST_" + key.toUpperCase(Locale.ROOT));
            if (value != null) credentials.setProperty(key, value);
        }
        try (var admin = DriverManager.getConnection(url, credentials); var statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
            try (var connection = DriverManager.getConnection(SchemaCheckMojo.databaseUrl(url, database), credentials)) {
                action.run(connection);
            } finally { statement.execute("DROP DATABASE " + database + " WITH (FORCE)"); }
        }
    }
    private static void execute(Connection c, String sql) throws SQLException { try (var s = c.createStatement()) { s.execute(sql); } }
    private static String scope(UUID tenant) { return tenant == null ? "GLOBAL" : "TENANT:" + tenant; }
    private static void transaction(Connection c, UUID tenant, DatabaseWork body) throws Exception {
        c.setAutoCommit(false);
        try {
            try (var setting = c.prepareStatement("SELECT set_config('vernac.tenant_id', ?, true)")) {
                setting.setString(1, tenant == null ? "" : tenant.toString()); setting.execute();
            }
            body.run(c); c.commit();
        } catch (Exception | Error failed) { c.rollback(); throw failed; }
        finally { c.setAutoCommit(true); }
    }
    private static void root(Connection c, UUID id, UUID tenant) throws SQLException {
        try (var s = c.prepareStatement("INSERT INTO " + ROOT + " (\"@eventId\",text,\"@tenantId\",\"@scopeKey\") VALUES (?,'payload',?,?)")) {
            s.setObject(1, id); s.setObject(2, tenant); s.setString(3, scope(tenant)); s.executeUpdate();
        }
    }
    private static void child(Connection c, String table, UUID row, UUID owner, UUID tenant, int position) throws SQLException {
        try (var s = c.prepareStatement("INSERT INTO " + table + " (\"@rowId\",\"@ownerId\",\"@position\",value,\"@tenantId\",\"@scopeKey\") VALUES (?,?,?,'entry',?,?)")) {
            s.setObject(1, row); s.setObject(2, owner); s.setInt(3, position); s.setObject(4, tenant); s.setString(5, scope(tenant)); s.executeUpdate();
        }
    }
    private static long count(Connection c, String table) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM " + table)) { r.next(); return r.getLong(1); }
    }
    private static void denied(String state, org.junit.jupiter.api.function.Executable action) {
        assertEquals(state, assertThrows(SQLException.class, action).getSQLState());
    }

    @Test void moduleMetadataPreservesFormatOneTwoAndThreeFingerprints() throws Exception {
        var aggregate = new Table("tenant_model", "Invoice", "tenant_model.Invoice",
                List.of(new Column("id", "UUID", false, "UUID", "tenant_model.Invoice.id")), List.of("id"), List.of());
        var tenant = TenantSchema.table(aggregate, aggregate.origin(), true, List.of());
        var formatTwo = new SchemaModel(2, 1, List.of(tenant), Map.of());
        for (var model : List.of(legacy(), formatTwo, scoped())) {
            var runtime = SchemaModel.JSON.readValue(model.json(), org.vernac.runtime.persistence.SchemaDescription.class);
            assertEquals(model.fingerprint(), org.vernac.runtime.jdbc.modules.ModuleMigrationStrategy.fingerprint(runtime));
        }
    }

    @Test @EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL", matches=".+")
    void freshMixedOriginGraphEnforcesRlsAndScopeQualifiedNestedRelationships() throws Exception {
        database(c -> {
            execute(c, new MigrationPlanner().plan(SchemaModel.empty(), scoped(), MigrationPlanner.Options.safe()).sql());
            String role = "event_runtime_" + UUID.randomUUID().toString().replace("-", "");
            execute(c, "CREATE ROLE " + role + " NOLOGIN NOSUPERUSER NOBYPASSRLS");
            try {
                execute(c, "GRANT USAGE ON SCHEMA event_scope TO " + role);
                execute(c, "GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA event_scope TO " + role);
                execute(c, "SET ROLE " + role);
                UUID global = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID(), aRow = UUID.randomUUID();
                transaction(c, null, db -> {root(db, global, null); child(db, CHILD, UUID.randomUUID(), global, null, 0);});
                transaction(c, A, db -> {root(db, a, A); child(db, CHILD, aRow, a, A, 0); child(db, NESTED, UUID.randomUUID(), aRow, A, 0);});
                transaction(c, B, db -> root(db, b, B));
                transaction(c, null, db -> {assertEquals(1, count(db, ROOT)); assertEquals(0, count(db, NESTED));});
                transaction(c, A, db -> {assertEquals(2, count(db, ROOT)); assertEquals(1, count(db, NESTED));});
                denied("23503", () -> transaction(c, null, db -> child(db, CHILD, UUID.randomUUID(), a, null, 1)));
                denied("23503", () -> transaction(c, A, db -> child(db, CHILD, UUID.randomUUID(), b, A, 1)));
                denied("23503", () -> transaction(c, null, db -> child(db, NESTED, UUID.randomUUID(), aRow, null, 1)));
                denied("42501", () -> transaction(c, A, db -> root(db, UUID.randomUUID(), null)));
                denied("42501", () -> transaction(c, A, db -> root(db, UUID.randomUUID(), B)));
                assertEquals(1, count(c, ROOT), "Transaction-local tenant settings must not leak");
            } finally {
                execute(c, "RESET ROLE"); execute(c, "DROP OWNED BY " + role); execute(c, "DROP ROLE " + role);
            }
        });
    }

    @Test @EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL", matches=".+")
    void reviewedLegacyUpgradePreservesGlobalRowsAndMatchesFreshSchemaCheckCatalog() throws Exception {
        var fresh = new java.util.concurrent.atomic.AtomicReference<SortedSet<String>>();
        database(c -> {
            execute(c, new MigrationPlanner().plan(SchemaModel.empty(), scoped(), MigrationPlanner.Options.safe()).sql());
            fresh.set(SchemaCheckMojo.catalog(c, OWNED));
        });
        database(c -> {
            var planner = new MigrationPlanner(); execute(c, planner.plan(SchemaModel.empty(), legacy(), MigrationPlanner.Options.safe()).sql());
            UUID event = UUID.randomUUID(), row = UUID.randomUUID();
            execute(c, "INSERT INTO " + ROOT + " VALUES ('" + event + "','old payload')");
            execute(c, "INSERT INTO " + CHILD + " (\"@rowId\",\"@ownerId\",\"@position\",value) VALUES ('" + row + "','" + event + "',7,'old entry')");
            execute(c, "INSERT INTO " + NESTED + " (\"@rowId\",\"@ownerId\",\"@position\",value) VALUES ('" + UUID.randomUUID() + "','" + row + "',3,'nested')");
            String upgrade = planner.plan(legacy(), scoped(), upgrade()).sql();
            assertTrue(upgrade.contains("You are solely responsible")); assertTrue(upgrade.contains("EVENT ORIGIN UPGRADE"));
            execute(c, upgrade);
            assertEquals(fresh.get(), SchemaCheckMojo.catalog(c, OWNED));
            for (String table : List.of(ROOT, CHILD, NESTED)) try (var s = c.createStatement(); var r = s.executeQuery(
                    "SELECT count(*) FROM " + table + " WHERE \"@tenantId\" IS NULL AND \"@scopeKey\"='GLOBAL'")) {r.next(); assertEquals(1, r.getInt(1));}
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT \"@position\",value FROM " + CHILD)) {
                assertTrue(r.next()); assertEquals(7, r.getInt(1)); assertEquals("old entry", r.getString(2));
            }
            execute(c, "DROP POLICY \"@event-origin\" ON " + NESTED);
            assertNotEquals(fresh.get(), SchemaCheckMojo.catalog(c, OWNED), "The schema-check catalog must detect missing event protection");
        });
    }
}
