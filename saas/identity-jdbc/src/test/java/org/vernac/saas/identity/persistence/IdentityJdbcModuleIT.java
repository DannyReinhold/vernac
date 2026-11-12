// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;
import org.vernac.compiler.application.*;
import org.vernac.compiler.modules.*;
import org.vernac.compiler.persistence.SchemaModel;
import org.vernac.compiler.pipeline.VernacCompiler;
import org.vernac.runtime.authorization.AuthorizationException;
import org.vernac.runtime.tenancy.TenantContext;
import org.vernac.runtime.tenancy.TenantId;
import org.vernac.saas.identity.domain.*;
import org.vernac.saas.identity.persistence.domain.*;
import org.vernac.saas.identity.security.*;

import java.net.URLClassLoader;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

import javax.tools.ToolProvider;

class IdentityJdbcModuleIT {
    private static final String SCHEMA = "org.vernac.saas.identity.persistence";
    private static final String TABLE = "\"" + SCHEMA + "\".\"Identity\"";
    @TempDir Path root;

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    public static class Transactions {}

    private List<ModuleDescriptor> modules() throws Exception {
        return ModuleArtifacts.read(
                List.of(
                        Path.of(System.getProperty("identity.types.jar")),
                        Path.of(System.getProperty("identity.jdbc.jar"))));
    }

    @Test
    void packagedProviderExportsOnlyAdapterAndConsumerActivatesExplicitly() throws Exception {
        var modules = modules();
        ModuleContracts.checkDependencies(modules);
        var provider =
                modules.stream()
                        .filter(m -> m.module().equals("vernac.saas.identity.jdbc"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(provider.activatable());
        assertTrue(provider.persistent());
        assertEquals(
                List.of("org.vernac.saas.identity.persistence.StoredIdentityDirectory"),
                provider.exports().stream().map(ModuleDescriptor.Definition::identity).toList());
        try (var code = consumer(modules)) {
            assertNotNull(code.loadClass("consumer.identity.configuration.__VernacApplication"));
        }
        Path source = Files.createDirectories(root.resolve("forbidden"));
        Files.createDirectories(source.resolve("forbidden"));
        Files.writeString(
                source.resolve("forbidden/model.vernac"),
                "namespace forbidden; import org.vernac.saas.identity.persistence.Identity; value"
                        + " Leak(Identity);");
        assertThrows(
                org.vernac.compiler.analyzer.SemanticValidationException.class,
                () -> new VernacCompiler().analyzeProject(source, modules));
    }

    private URLClassLoader consumer(List<ModuleDescriptor> modules) throws Exception {
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

    @Test
    @EnabledIfEnvironmentVariable(named = "VERNAC_PG_TEST_URL", matches = ".+")
    void realModuleMigrationTransactionsRevocationAndRestrictedRuntime() throws Exception {
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
                                    .withInitializer(
                                            context -> {
                                                try {
                                                    new AnnotatedBeanDefinitionReader(
                                                                    (org.springframework.beans
                                                                                    .factory.support
                                                                                    .BeanDefinitionRegistry)
                                                                            context)
                                                            .register(
                                                                    code.loadClass(
                                                                            "consumer.identity.configuration.__VernacApplication"));
                                                } catch (Exception e) {
                                                    throw new IllegalStateException(e);
                                                }
                                            });
                    UserId[] committed = new UserId[1];
                    boot.run(
                            context ->
                                    committed[0] =
                                            exerciseDirectory(
                                                    context, url, credentials, role, password));
                    boot.run(
                            context -> {
                                if (context.getStartupFailure() != null)
                                    throw new AssertionError(
                                            "Consumer startup failed", context.getStartupFailure());
                                assertEquals(
                                        IdentityStatus.CLOSED,
                                        context.getBean(IdentityDirectory.class)
                                                .status(committed[0])
                                                .orElseThrow());
                            });
                    try (var migration = DriverManager.getConnection(url, credentials);
                            var sql = migration.createStatement();
                            var rows =
                                    sql.executeQuery(
                                            "SELECT count(*) FROM \""
                                                    + ModulePersistence.historySchema(
                                                            "vernac.saas.identity.jdbc")
                                                    + "\".flyway_schema_history WHERE success AND"
                                                    + " type = 'SQL'")) {
                        assertTrue(rows.next());
                        assertEquals(1, rows.getInt(1));
                    }
                } finally {
                    statement.execute("DROP DATABASE " + database + " WITH (FORCE)");
                    statement.execute("DROP ROLE IF EXISTS " + role);
                }
            }
        }
    }

    private UserId exerciseDirectory(
            org.springframework.boot.test.context.assertj.AssertableApplicationContext context,
            String url,
            Properties credentials,
            String role,
            String password)
            throws Exception {
        if (context.getStartupFailure() != null)
            throw new AssertionError("Consumer startup failed", context.getStartupFailure());
        var directory = context.getBean(IdentityDirectory.class);
        var repository = context.getBean(IdentityRepository.class);
        var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertTrue(directory.status(UserId.create()).isEmpty());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertTrue(TenantContext.currentTenant().isEmpty());
        var selected = TenantId.of(UUID.randomUUID());
        try (var scope = TenantContext.openVerified(selected)) {
            assertTrue(directory.status(UserId.create()).isEmpty());
            assertEquals(selected, TenantContext.requireTenant());
            tx.executeWithoutResult(
                    status -> {
                        assertTrue(directory.status(UserId.create()).isEmpty());
                        assertEquals(selected, TenantContext.requireTenant());
                    });
        }
        assertTrue(TenantContext.currentTenant().isEmpty());
        var rolledBack = Identity.create(IdentityStatus.PENDING_VERIFICATION);
        tx.executeWithoutResult(
                status -> {
                    repository.save(rolledBack);
                    assertEquals(
                            IdentityStatus.PENDING_VERIFICATION,
                            directory.status(rolledBack.id()).orElseThrow());
                    status.setRollbackOnly();
                });
        assertTrue(directory.status(rolledBack.id()).isEmpty());
        var identity = Identity.create(IdentityStatus.PENDING_VERIFICATION);
        tx.executeWithoutResult(status -> repository.save(identity));
        var resolver = new IdentityActorResolver(directory);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                new IdentityPrincipal(identity.id()), "unused", List.of()));
        try {
            assertTrue(resolver.resolve().isEmpty());
            tx.executeWithoutResult(
                    status -> {
                        var stored = repository.byId(identity.id());
                        stored.activate();
                        repository.save(stored);
                    });
            assertTrue(resolver.resolve().isPresent());
            tx.executeWithoutResult(
                    status -> {
                        var stored = repository.byId(identity.id());
                        stored.disable();
                        repository.save(stored);
                    });
            assertTrue(resolver.resolve().isEmpty());
            tx.executeWithoutResult(
                    status -> {
                        var stored = repository.byId(identity.id());
                        assertThrows(
                                org.vernac.runtime.DomainValidationException.class,
                                stored::activate);
                        stored.close();
                        repository.save(stored);
                    });
            assertEquals(IdentityStatus.CLOSED, directory.status(identity.id()).orElseThrow());
            try (var migration = DriverManager.getConnection(url, credentials);
                    var sql = migration.createStatement()) {
                sql.execute("REVOKE SELECT ON " + TABLE + " FROM " + role);
                try {
                    var failure = assertThrows(AuthorizationException.class, resolver::resolve);
                    assertEquals(
                            AuthorizationException.Code.AUTHORIZATION_UNAVAILABLE, failure.code());
                    assertNull(failure.getCause());
                } finally {
                    sql.execute("GRANT SELECT ON " + TABLE + " TO " + role);
                }
            }
            assertEquals(IdentityStatus.CLOSED, directory.status(identity.id()).orElseThrow());
            assertTrue(resolver.resolve().isEmpty());
        } finally {
            SecurityContextHolder.clearContext();
        }
        var stale = tx.execute(status -> repository.byId(identity.id()));
        tx.executeWithoutResult(status -> repository.save(repository.byId(identity.id())));
        assertThrows(
                org.springframework.dao.OptimisticLockingFailureException.class,
                () -> tx.executeWithoutResult(status -> repository.save(stale)));
        try (var runtime = DriverManager.getConnection(url, role, password);
                var sql = runtime.createStatement()) {
            assertThrows(
                    SQLException.class,
                    () -> sql.execute("CREATE TABLE \"" + SCHEMA + "\".forbidden(id int)"));
        }
        return identity.id();
    }
}
