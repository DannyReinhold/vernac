// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.vernac.compiler.persistence.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.vernac.compiler.persistence.SchemaModel.*;

@EnabledIfEnvironmentVariable(named = "VERNAC_PG_TEST_URL", matches = ".+")
class TenantSchemaPostgresTest {
    private static final String GRAPH = "example.billing.Invoice";
    private static SchemaModel model() {
        var root = TenantSchema.table(new Table("example.billing", "Invoice", GRAPH,
                List.of(new Column("id", "UUID", false, "UUID", GRAPH)), List.of("id"), List.of()),
                GRAPH, true, List.of());
        var lines = TenantSchema.table(new Table("example.billing", "Invoice.lines", GRAPH + ".lines",
                List.of(new Column("@ownerId", "UUID", false, "aggregate-owner", GRAPH),
                        new Column("@position", "INTEGER", false, "list-position", GRAPH)),
                List.of("@ownerId", "@position"), List.of()), GRAPH, false,
                List.of(TenantSchema.foreignKey("@fk:owner", List.of("@ownerId"),
                        "example.billing", "Invoice", List.of("id"), true)));
        return new SchemaModel(2, 1, List.of(root, lines), Map.of());
    }
    @FunctionalInterface interface Exercise { void run(Connection c, String runtimeRole) throws Exception; }
    private void database(Exercise exercise) throws Exception {
        String url = System.getenv("VERNAC_PG_TEST_URL");
        var credentials = new Properties();
        credentials.setProperty("user", System.getenv("VERNAC_PG_TEST_USER"));
        credentials.setProperty("password", System.getenv("VERNAC_PG_TEST_PASSWORD"));
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String database = "vts_schema_" + suffix, role = "vts_schema_role_" + suffix;
        try (var admin = DriverManager.getConnection(url, credentials)) {
            boolean createdDatabase = false, createdRole = false;
            try {
                try (var s = admin.createStatement()) {
                    s.execute("CREATE ROLE " + role + " NOLOGIN NOSUPERUSER NOBYPASSRLS"); createdRole = true;
                    s.execute("CREATE DATABASE " + database); createdDatabase = true;
                }
                try (var c = DriverManager.getConnection(SchemaCheckMojo.databaseUrl(url, database), credentials)) {
                    try (var s = c.createStatement()) {
                        s.execute(new MigrationPlanner().plan(SchemaModel.empty(), model(), MigrationPlanner.Options.safe()).sql());
                        s.execute("GRANT USAGE ON SCHEMA \"example.billing\" TO " + role);
                        s.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA \"example.billing\" TO " + role);
                    }
                    exercise.run(c, role);
                }
            } finally {
                try (var s = admin.createStatement()) {
                    if (createdDatabase) s.execute("DROP DATABASE " + database + " WITH (FORCE)");
                    if (createdRole) s.execute("DROP ROLE " + role);
                }
            }
        }
    }
    private static long count(Connection c, String table) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM \"example.billing\".\"" + table + "\"")) {
            r.next(); return r.getLong(1);
        }
    }
    private static void setting(Connection c, UUID tenant) throws SQLException {
        try (var s = c.prepareStatement("SELECT set_config('vernac.tenant_id', ?, true)")) {
            s.setString(1, tenant.toString()); s.execute();
        }
    }
    @Test void generatedCandidateEnforcesTenantRowsAndQualifiedChildReferences() throws Exception {
        database((c, role) -> {
            UUID a = UUID.randomUUID(), b = UUID.randomUUID(), invoice = UUID.randomUUID();
            c.setAutoCommit(false);
            try (var s = c.createStatement()) { s.execute("SET LOCAL ROLE " + role); }
            assertEquals(0, count(c, "Invoice")); // no implicit tenant
            setting(c, a);
            try (var s = c.prepareStatement("INSERT INTO \"example.billing\".\"Invoice\" (id,\"@tenantId\") VALUES (?,?)")) {
                s.setObject(1, invoice); s.setObject(2, a); s.executeUpdate();
            }
            try (var s = c.prepareStatement("INSERT INTO \"example.billing\".\"Invoice.lines\" VALUES (?,?,?)")) {
                // Columns are deterministically sorted: ownerId, position, tenantId.
                s.setObject(1, invoice); s.setInt(2, 0); s.setObject(3, a); s.executeUpdate();
            }
            c.commit();
            try (var s = c.createStatement()) { s.execute("SET LOCAL ROLE " + role); }
            setting(c, b);
            assertEquals(0, count(c, "Invoice"));
            assertEquals(0, count(c, "Invoice.lines"));
            try (var s = c.prepareStatement("INSERT INTO \"example.billing\".\"Invoice.lines\" VALUES (?,?,?)")) {
                s.setObject(1, invoice); s.setInt(2, 1); s.setObject(3, b);
                s.executeUpdate();
            }
            assertThrows(SQLException.class, c::commit); // deferred tenant-qualified FK
            c.rollback();
            try (var s = c.createStatement()) { s.execute("SET LOCAL ROLE " + role); }
            assertEquals(0, count(c, "Invoice")); // transaction-local context did not leak
            c.rollback();
        });
    }

    @Test void schemaCheckCatalogDetectsPolicyAndRlsDriftButIgnoresForeignTables() throws Exception {
        database((c, role) -> {
            Set<String> owned = new HashSet<>();
            model().tables().forEach(t -> owned.add(SqlNames.physical(t.namespace()) + "/" + SqlNames.physical(t.name())));
            var expected = SchemaCheckMojo.catalog(c, owned);
            try (var s = c.createStatement()) {
                s.execute("CREATE TABLE public.foreign_data (id integer)");
                s.execute("ALTER TABLE public.foreign_data ENABLE ROW LEVEL SECURITY");
                s.execute("CREATE POLICY app_policy ON public.foreign_data USING (true)");
            }
            assertEquals(expected, SchemaCheckMojo.catalog(c, owned));
            c.setAutoCommit(false);
            try (var s = c.createStatement()) {
                s.execute("ALTER POLICY \"@tenant\" ON \"example.billing\".\"Invoice\" USING (true) WITH CHECK (true)");
            }
            assertNotEquals(expected, SchemaCheckMojo.catalog(c, owned));
            c.rollback();
            try (var s = c.createStatement()) { s.execute("DROP POLICY \"@tenant\" ON \"example.billing\".\"Invoice\""); }
            assertNotEquals(expected, SchemaCheckMojo.catalog(c, owned));
            c.rollback();
            try (var s = c.createStatement()) { s.execute("ALTER TABLE \"example.billing\".\"Invoice\" NO FORCE ROW LEVEL SECURITY"); }
            assertNotEquals(expected, SchemaCheckMojo.catalog(c, owned));
            c.rollback();
            assertEquals(expected, SchemaCheckMojo.catalog(c, owned));
        });
    }
}
