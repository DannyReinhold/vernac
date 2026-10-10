// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.vernac.compiler.persistence.MigrationPlanner;
import org.vernac.compiler.persistence.SchemaBuilder;
import org.vernac.compiler.persistence.SchemaModel;
import org.vernac.compiler.pipeline.VernacCompiler;

import javax.sql.DataSource;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Compiles both model versions; the opt-in test also migrates actual V1 data. */
class RequiredFieldBackfillTest {
    @TempDir Path directory;

    @Test
    void requiredFieldNeedsAnExplicitBackfillAndBothRepositoriesCompile() throws Exception {
        var fixture = prepare();
        try (var oldCode = compile(fixture.v1(), "old", false);
             var newCode = compile(fixture.v2(), "new", true)) {
            assertNotSame(oldCode.loadClass("backfill.domain.Tour"),
                    newCode.loadClass("backfill.domain.Tour"));
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VERNAC_PG_TEST_URL", matches = ".+")
    void flywayBackfillsExistingDataAndNewRepositoryLoadsTheAggregate() throws Exception {
        var fixture = prepare();
        try (var oldCode = compile(fixture.v1(), "old", false);
             var newCode = compile(fixture.v2(), "new", true)) {
            String adminUrl = System.getenv("VERNAC_PG_TEST_URL");
            Properties credentials = new Properties();
            for (String key : List.of("user", "password")) {
                String value = System.getenv("VERNAC_PG_TEST_" + key.toUpperCase(java.util.Locale.ROOT));
                if (value != null) credentials.setProperty(key, value);
            }
            String name = "vernac_backfill_" + UUID.randomUUID().toString().replace("-", "");
            try (Connection admin = DriverManager.getConnection(adminUrl, credentials)) {
                boolean created = false;
                try {
                    try (var statement = admin.createStatement()) {
                        statement.execute("CREATE DATABASE " + name);
                    }
                    created = true;
                    var source = new DriverManagerDataSource(SchemaCheckMojo.databaseUrl(adminUrl, name));
                    source.setConnectionProperties(credentials);
                    String location = "filesystem:" + fixture.sql().toAbsolutePath();
                    assertEquals(1, Flyway.configure().dataSource(source).locations(location)
                            .target("1").load().migrate().migrationsExecuted);
                    UUID id = (UUID) invoke(oldCode, "seed", new Class<?>[]{DataSource.class}, source);
                    invoke(oldCode, "verify", new Class<?>[]{DataSource.class, UUID.class}, source, id);

                    assertEquals(1, Flyway.configure().dataSource(source).locations(location)
                            .load().migrate().migrationsExecuted);
                    try (var connection = source.getConnection();
                         var statement = connection.prepareStatement(
                                 "SELECT \"reference\" FROM \"backfill\".\"Tour\" WHERE \"id\" = ?")) {
                        statement.setObject(1, id);
                        try (var rows = statement.executeQuery()) {
                            assertTrue(rows.next());
                            assertEquals("LEGACY-" + id, rows.getString(1));
                            assertFalse(rows.next());
                        }
                        try (var columns = connection.getMetaData().getColumns(null, "backfill", "Tour", "reference")) {
                            assertTrue(columns.next());
                            assertEquals(java.sql.DatabaseMetaData.columnNoNulls, columns.getInt("NULLABLE"));
                        }
                    }
                    invoke(newCode, "verify", new Class<?>[]{DataSource.class, UUID.class}, source, id);
                    assertEquals(0, Flyway.configure().dataSource(source).locations(location)
                            .load().migrate().migrationsExecuted);
                } finally {
                    if (created) try (var statement = admin.createStatement()) {
                        statement.execute("DROP DATABASE " + name);
                    }
                }
            }
        }
    }

    private Fixture prepare() throws Exception {
        Path v1 = model("v1");
        Path v2 = model("v2");
        var compiler = new VernacCompiler();
        var before = new SchemaBuilder(compiler.analyzeProject(v1), SchemaModel.empty()).build();
        var after = new SchemaBuilder(compiler.analyzeProject(v2), before).build();
        var planner = new MigrationPlanner();
        var error = assertThrows(IllegalArgumentException.class,
                () -> planner.plan(before, after, MigrationPlanner.Options.safe()));
        assertTrue(error.getMessage().contains("backfill/Tour/reference"));
        var migration = planner.plan(before, after, new MigrationPlanner.Options(false,
                Map.of("backfill/Tour/reference", "'LEGACY-' || \"id\"::text"), Map.of()));
        assertTrue(migration.sql().indexOf("ADD COLUMN") < migration.sql().indexOf("UPDATE"));
        assertTrue(migration.sql().indexOf("UPDATE") < migration.sql().indexOf("SET NOT NULL"));
        Path sql = Files.createDirectories(directory.resolve("migrations"));
        Files.writeString(sql.resolve("V1__initial.sql"),
                planner.plan(SchemaModel.empty(), before, MigrationPlanner.Options.safe()).sql());
        Files.writeString(sql.resolve("V2__required_reference.sql"), migration.sql());
        return new Fixture(v1, v2, sql);
    }

    private Path model(String version) throws Exception {
        Path root = Files.createDirectories(directory.resolve(version));
        Path namespace = Files.createDirectories(root.resolve("backfill"));
        Files.writeString(namespace.resolve("model.vernac"), resource(version + ".vernac"));
        return root;
    }

    private URLClassLoader compile(Path model, String version, boolean withReference) throws Exception {
        Path sources = Files.createDirectories(directory.resolve(version + "-java"));
        Path classes = Files.createDirectories(directory.resolve(version + "-classes"));
        new VernacCompiler().compileProject(model).writeTo(sources);
        String probe = resource("RepositoryProbe.java.txt")
                .replace("/*REFERENCE_ARGUMENT*/", withReference ? ", DispatchReference.of(\"NEW\")" : "")
                .replace("/*REFERENCE_ASSERTION*/", withReference
                        ? "assertEquals(\"LEGACY-\" + id, tour.reference().string());" : "");
        Files.writeString(sources.resolve("backfill/RepositoryProbe.java"), probe);
        var compiler = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler(), "A JDK is required");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
             var paths = Files.walk(sources)) {
            var files = paths.filter(path -> path.toString().endsWith(".java")).toList();
            var options = List.of("--release", "21", "-encoding", "UTF-8", "-proc:none",
                    "-classpath", System.getProperty("java.class.path"), "-d", classes.toString());
            assertTrue(compiler.getTask(null, manager, diagnostics, options, null,
                    manager.getJavaFileObjectsFromPaths(files)).call(), () -> diagnostics.getDiagnostics().toString());
        }
        return new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader());
    }

    private static Object invoke(ClassLoader code, String method, Class<?>[] types, Object... arguments)
            throws Exception {
        try {
            return code.loadClass("backfill.RepositoryProbe").getMethod(method, types).invoke(null, arguments);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw failure;
        }
    }

    private static String resource(String name) throws Exception {
        try (var stream = RequiredFieldBackfillTest.class.getResourceAsStream("/persistence/backfill/" + name)) {
            return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record Fixture(Path v1, Path v2, Path sql) { }
}
