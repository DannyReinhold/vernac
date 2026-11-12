// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import static org.junit.jupiter.api.Assertions.*;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.vernac.compiler.application.*;
import org.vernac.compiler.modules.*;
import org.vernac.compiler.persistence.SchemaModel;
import org.vernac.compiler.pipeline.VernacCompiler;

import java.net.URLClassLoader;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

import javax.tools.ToolProvider;

/** Actual producer-JAR consumer, explicit module activation and restricted PostgreSQL runtime. */
final class IdentityJdbcTestEnvironment {
    static final String SCHEMA = "org.vernac.saas.identity.persistence";
    private final Path root;

    IdentityJdbcTestEnvironment(Path root) {
        this.root = root;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions {}

    @FunctionalInterface
    interface Scenario {
        void run(Database database) throws Exception;
    }

    static final class Database {
        final ApplicationContextRunner boot;
        final URLClassLoader code;
        final String url, role, password;
        final Properties credentials;

        Database(
                ApplicationContextRunner boot,
                URLClassLoader code,
                String url,
                Properties credentials,
                String role,
                String password) {
            this.boot = boot;
            this.code = code;
            this.url = url;
            this.credentials = credentials;
            this.role = role;
            this.password = password;
        }

        long migrationCount() throws Exception {
            try (var c = DriverManager.getConnection(url, credentials);
                    var sql = c.createStatement();
                    var rows =
                            sql.executeQuery(
                                    "SELECT count(*) FROM "
                                            + org.vernac.compiler.persistence.SqlNames.quote(
                                                    ModulePersistence.historySchema(
                                                            "vernac.saas.identity.jdbc"))
                                            + ".flyway_schema_history WHERE success AND"
                                            + " type='SQL'")) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    List<ModuleDescriptor> modules() throws Exception {
        return ModuleArtifacts.read(
                List.of(
                        Path.of(System.getProperty("identity.types.jar")),
                        Path.of(System.getProperty("identity.jdbc.jar"))));
    }

    URLClassLoader consumer(List<ModuleDescriptor> modules) throws Exception {
        Path source = Files.createDirectories(root.resolve("consumer")),
                classes = Files.createDirectories(root.resolve("classes"));
        Files.createDirectories(source.resolve("consumer/identity"));
        Files.writeString(
                source.resolve("consumer/identity/model.vernac"),
                "namespace consumer.identity; import org.vernac.saas.identity.UserId; value"
                        + " Owner(UserId);");
        var compiler = new VernacCompiler();
        var resolved = compiler.analyzeProject(source, modules);
        var generated = compiler.generate(resolved, SchemaModel.empty(), Map.of());
        var document = Files.readString(Path.of("../../docs/contracts/saas-identity-jdbc.md"));
        var example =
                java.util.regex.Pattern.compile(
                                "```vernac-application\\n(.*?)```", java.util.regex.Pattern.DOTALL)
                        .matcher(document);
        assertTrue(example.find(), "The provider contract must include its actual composition");
        var app =
                ApplicationDefinition.parse(
                        root.resolve("vernac-application.vapplication"), example.group(1));
        var application =
                ApplicationBindings.generate(
                        app, resolved, modules, generated.generatedFiles(), false);
        Path java = Files.createDirectories(root.resolve("java"));
        for (var file : application.files()) file.writeTo(java);
        ModuleDescriptor.write(
                classes.resolve(ApplicationBindings.schemaResource(app.name())),
                Map.of("schema", SchemaModel.empty(), "ownedTables", List.of()));
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
            files.filter(p -> p.toString().endsWith(".java"))
                    .map(Path::toString)
                    .forEach(args::add);
        }
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler()
                        .run(null, null, null, args.toArray(String[]::new)));
        return new URLClassLoader(
                new java.net.URL[] {
                    classes.toUri().toURL(),
                    Path.of(System.getProperty("identity.jdbc.jar")).toUri().toURL()
                },
                getClass().getClassLoader()) {
            @Override
            public Enumeration<java.net.URL> getResources(String name) throws java.io.IOException {
                // The producer test classpath also contains target/classes. Read its
                // persistence resources from the packaged artifact exactly once.
                return name.startsWith(ModulePersistence.directory("vernac.saas.identity.jdbc"))
                        ? findResources(name)
                        : super.getResources(name);
            }
        };
    }

    void withDatabase(Scenario scenario) throws Exception {
        try (var code = consumer(modules())) {
            String adminUrl = System.getenv("VERNAC_PG_TEST_URL");
            var credentials = new Properties();
            credentials.setProperty("user", System.getenv("VERNAC_PG_TEST_USER"));
            credentials.setProperty("password", System.getenv("VERNAC_PG_TEST_PASSWORD"));
            String suffix = UUID.randomUUID().toString().replace("-", ""),
                    database = "identity_" + suffix,
                    role = "identity_runtime_" + suffix,
                    password = UUID.randomUUID().toString();
            String url = adminUrl.replaceFirst("/[^/?]+(\\?.*)?$", "/" + database);
            try (var admin = DriverManager.getConnection(adminUrl, credentials);
                    var statement = admin.createStatement()) {
                statement.execute("CREATE DATABASE " + database);
                try {
                    statement.execute(
                            "CREATE ROLE "
                                    + role
                                    + " LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '"
                                    + password
                                    + "'");
                    try (var migration = DriverManager.getConnection(url, credentials);
                            var sql = migration.createStatement()) {
                        sql.execute("CREATE SCHEMA \"" + SCHEMA + "\"");
                        sql.execute("GRANT USAGE ON SCHEMA \"" + SCHEMA + "\" TO " + role);
                        sql.execute(
                                "ALTER DEFAULT PRIVILEGES IN SCHEMA \""
                                        + SCHEMA
                                        + "\" GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO "
                                        + role);
                    }
                    var boot =
                            new ApplicationContextRunner()
                                    .withClassLoader(code)
                                    .withUserConfiguration(Transactions.class)
                                    .withConfiguration(
                                            AutoConfigurations.of(
                                                    DataSourceAutoConfiguration.class,
                                                    DataSourceTransactionManagerAutoConfiguration
                                                            .class,
                                                    JdbcTemplateAutoConfiguration.class,
                                                    FlywayAutoConfiguration.class))
                                    .withPropertyValues(
                                            "spring.datasource.url=" + url,
                                            "spring.datasource.username=" + role,
                                            "spring.datasource.password=" + password,
                                            "spring.datasource.hikari.maximum-pool-size=2",
                                            "spring.datasource.hikari.minimum-idle=0",
                                            "spring.flyway.url=" + url,
                                            "spring.flyway.user=" + credentials.getProperty("user"),
                                            "spring.flyway.password="
                                                    + credentials.getProperty("password"))
                                    .withInitializer(context -> {
                                        try {
                                            new AnnotatedBeanDefinitionReader(
                                                            (org.springframework.beans.factory
                                                                            .support
                                                                            .BeanDefinitionRegistry)
                                                                    context)
                                                    .register(
                                                            code.loadClass(
                                                                    "consumer.identity.configuration.__VernacApplication"));
                                        } catch (Exception e) {
                                            throw new IllegalStateException(e);
                                        }
                                    });
                    scenario.run(new Database(boot, code, url, credentials, role, password));
                } finally {
                    statement.execute("DROP DATABASE " + database + " WITH (FORCE)");
                    statement.execute("DROP ROLE IF EXISTS " + role);
                }
            }
        }
    }
}
