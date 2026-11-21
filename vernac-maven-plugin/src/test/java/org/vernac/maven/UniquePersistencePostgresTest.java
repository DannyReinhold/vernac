// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.vernac.compiler.persistence.*;
import org.vernac.compiler.pipeline.VernacCompiler;

import java.net.URLClassLoader;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

import javax.sql.DataSource;
import javax.tools.ToolProvider;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "VERNAC_PG_TEST_URL", matches = ".+")
class UniquePersistencePostgresTest {
    @TempDir static Path root;
    Path sources;
    SchemaModel schema;
    URLClassLoader code;

    @BeforeAll
    void compile() throws Exception {
        sources = Files.createDirectories(root.resolve("model"));
        var file = Files.createDirectories(sources.resolve("uniquedemo")).resolve("model.vernac");
        Files.writeString(file, resource("model.vernac"));
        var compiler = new VernacCompiler();
        var p = compiler.analyzeProject(sources);
        schema = new SchemaBuilder(p, SchemaModel.empty()).build();
        var java = Files.createDirectories(root.resolve("java"));
        compiler.generate(p, SchemaModel.empty(), Map.of()).writeTo(java);
        Files.writeString(
                Files.createDirectories(java.resolve("uniquedemo")).resolve("UniqueProbe.java"),
                resource("UniqueProbe.java.txt"));
        var classes = Files.createDirectories(root.resolve("classes"));
        var args =
                new ArrayList<>(
                        List.of(
                                "--release",
                                "21",
                                "-proc:none",
                                "-classpath",
                                System.getProperty("java.class.path"),
                                "-d",
                                classes.toString()));
        try (var files = Files.walk(java)) {
            files.filter(f -> f.toString().endsWith(".java"))
                    .map(Path::toString)
                    .forEach(args::add);
        }
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler()
                        .run(null, null, null, args.toArray(String[]::new)));
        code =
                new URLClassLoader(
                        new java.net.URL[] {classes.toUri().toURL()}, getClass().getClassLoader());
    }

    @AfterAll
    void close() throws Exception {
        if (code != null) code.close();
    }

    static String resource(String name) throws Exception {
        try (var input =
                Objects.requireNonNull(
                        UniquePersistencePostgresTest.class.getResourceAsStream(
                                "/persistence/unique/" + name))) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    interface Work {
        void run(
                DriverManagerDataSource one,
                DriverManagerDataSource two,
                DriverManagerDataSource owner)
                throws Exception;
    }

    void database(Work work) throws Exception {
        String url = System.getenv("VERNAC_PG_TEST_URL");
        var credentials = new Properties();
        credentials.setProperty("user", System.getenv("VERNAC_PG_TEST_USER"));
        credentials.setProperty("password", System.getenv("VERNAC_PG_TEST_PASSWORD"));
        String suffix = UUID.randomUUID().toString().replace("-", ""),
                db = "unique_" + suffix,
                role = "unique_role_" + suffix,
                password = UUID.randomUUID().toString();
        try (var admin = DriverManager.getConnection(url, credentials);
                var sql = admin.createStatement()) {
            sql.execute(
                    "CREATE DATABASE "
                            + db
                            + " TEMPLATE template0 LOCALE_PROVIDER icu ICU_LOCALE 'en-US'");
            try {
                sql.execute(
                        "CREATE ROLE "
                                + role
                                + " LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '"
                                + password
                                + "'");
                try {
                    String target = SchemaCheckMojo.databaseUrl(url, db);
                    var owner = new DriverManagerDataSource(target);
                    owner.setConnectionProperties(credentials);
                    try (var c = owner.getConnection();
                            var s = c.createStatement()) {
                        s.execute(
                                new MigrationPlanner()
                                        .plan(
                                                SchemaModel.empty(),
                                                schema,
                                                MigrationPlanner.Options.safe())
                                        .sql());
                        s.execute("GRANT USAGE ON SCHEMA uniquedemo TO " + role);
                        s.execute(
                                "GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA"
                                        + " uniquedemo TO "
                                        + role);
                    }
                    work.run(
                            new DriverManagerDataSource(target, role, password),
                            new DriverManagerDataSource(target, role, password),
                            owner);
                } finally {
                    sql.execute("DROP DATABASE " + db + " WITH (FORCE)");
                    sql.execute("DROP ROLE " + role);
                }
            } catch (Exception | Error failure) {
                sql.execute("DROP DATABASE IF EXISTS " + db + " WITH (FORCE)");
                throw failure;
            }
        }
    }

    void probe(String method) throws Exception {
        database(
                (one, two, owner) -> {
                    try {
                        code.loadClass("uniquedemo.UniqueProbe")
                                .getMethod(
                                        method,
                                        DataSource.class,
                                        DataSource.class,
                                        DataSource.class)
                                .invoke(null, one, two, owner);
                    } catch (java.lang.reflect.InvocationTargetException error) {
                        if (error.getCause() instanceof Error cause) throw cause;
                        if (error.getCause() instanceof Exception cause) throw cause;
                        throw error;
                    }
                });
    }

    @Test
    void sharedDatabaseRejectsConflictingInsertUpdateAndKeepsRollbackRestartSemantics()
            throws Exception {
        probe("lifecycle");
    }

    @Test
    void caughtConflictPoisonsItsWriterAndWrongOrReadOnlyBoundariesFailBeforeWrites()
            throws Exception {
        probe("rollbackAndBoundaries");
    }

    @Test
    void tenantKeysAreScopedAndExactTextSpellingsRemainDistinct() throws Exception {
        probe("tenantScopeAndExactText");
    }

    @Test
    void independentContextsHaveOneWinnerForInsertAndUpdateRaces() throws Exception {
        probe("races");
    }

    @Test
    void nonmatchingErrorsStayTechnicalAndOversizedIndexInputsAreSanitized() throws Exception {
        probe("nonmatchingAndOversizedFailures");
    }

    @Test
    void migrationReplayCatalogAndCollationDriftMatchTheSharedModel() throws Exception {
        database(
                (one, two, owner) -> {
                    var owned = new TreeSet<String>();
                    schema.tables().forEach(t -> owned.add(t.identity()));
                    SortedSet<String> wanted;
                    try (var c = owner.getConnection()) {
                        wanted = SchemaCheckMojo.catalog(c, owned);
                    }
                    String original = Files.readString(sources.resolve("uniquedemo/model.vernac"));
                    Files.writeString(
                            sources.resolve("uniquedemo/model.vernac"),
                            original.replace("unique ", ""));
                    var plain =
                            new SchemaBuilder(new VernacCompiler().analyzeProject(sources), schema)
                                    .build();
                    var planner = new MigrationPlanner();
                    String drop =
                            planner.plan(schema, plain, MigrationPlanner.Options.safe()).sql();
                    try (var c = owner.getConnection();
                            var s = c.createStatement()) {
                        s.execute(drop);
                        var changed = SchemaCheckMojo.catalog(c, owned);
                        assertNotEquals(wanted, changed);
                        s.execute(
                                "INSERT INTO uniquedemo.\"Claim\""
                                    + " (\"id\",\"key\",\"note\",\"@createdAt\",\"@updatedAt\",\"@nanoRemainder:@createdAt\",\"@nanoRemainder:@updatedAt\",\"@version\")"
                                    + " VALUES"
                                    + " (gen_random_uuid(),'duplicate','one',now(),now(),0,0,1),(gen_random_uuid(),'duplicate','two',now(),now(),0,0,1)");
                        String add =
                                planner.plan(plain, schema, MigrationPlanner.Options.safe()).sql();
                        c.setAutoCommit(false);
                        assertThrows(SQLException.class, () -> s.execute(add));
                        c.rollback();
                        assertEquals(
                                changed,
                                SchemaCheckMojo.catalog(
                                        c, owned)); // failed migration neither changes owners nor
                        // leaves a partial collation
                        try (var rows =
                                s.executeQuery("SELECT count(*) FROM uniquedemo.\"Claim\"")) {
                            rows.next();
                            assertEquals(2, rows.getInt(1));
                        }
                        s.execute("DELETE FROM uniquedemo.\"Claim\"");
                        c.commit();
                        s.execute(add);
                        c.commit();
                        assertEquals(wanted, SchemaCheckMojo.catalog(c, owned));
                        s.execute(
                                "ALTER TABLE uniquedemo.\"Claim\" ALTER COLUMN \"key\" TYPE TEXT"
                                        + " COLLATE \"pg_catalog\".\"default\"");
                        c.commit();
                        assertNotEquals(
                                wanted,
                                SchemaCheckMojo.catalog(
                                        c,
                                        owned)); // constraint still exists; collation itself must
                        // be checked
                    } finally {
                        Files.writeString(sources.resolve("uniquedemo/model.vernac"), original);
                    }
                });
    }

    @Test
    void schemaCheckGoalReplaysFormatFourHistoryAndDetectsCollationOnlyDrift() throws Exception {
        var modelFile = sources.resolve("uniquedemo/model.vernac");
        String original = Files.readString(modelFile);
        SchemaModel before;
        try {
            Files.writeString(modelFile, original.replace("unique ", ""));
            before =
                    new SchemaBuilder(
                                    new VernacCompiler().analyzeProject(sources),
                                    SchemaModel.empty())
                            .build();
        } finally {
            Files.writeString(modelFile, original);
        }
        var history = root.resolve("history");
        var migration = root.resolve("migration");
        var chain = new SchemaHistory(history, migration);
        var planner = new MigrationPlanner();
        chain.append(
                "20261113090000000",
                "initial",
                SchemaModel.empty(),
                before,
                planner.plan(SchemaModel.empty(), before, MigrationPlanner.Options.safe()));
        chain.append(
                "20261113090100000",
                "unique_fields",
                before,
                schema,
                planner.plan(before, schema, MigrationPlanner.Options.safe()));
        var goal = new SchemaCheckMojo();
        goal.sourceDirectory = sources.toFile();
        goal.historyDirectory = history.toFile();
        goal.migrationDirectory = migration.toFile();
        goal.schemaOutput = root.resolve("output").toFile();
        for (String name : List.of("adminUrl", "user", "password")) {
            var field = SchemaCheckMojo.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(
                    goal,
                    System.getenv(
                            name.equals("adminUrl")
                                    ? "VERNAC_PG_TEST_URL"
                                    : "VERNAC_PG_TEST_" + name.toUpperCase(Locale.ROOT)));
        }
        goal.execute();
        Files.writeString(
                migration.resolve("V20261113090200000__collation_drift.sql"),
                "ALTER TABLE uniquedemo.\"Claim\" ALTER COLUMN \"key\" TYPE TEXT COLLATE"
                    + " \"pg_catalog\".\"default\";");
        assertTrue(assertThrows(Exception.class, goal::execute).getMessage().contains("mismatch"));
    }
}
