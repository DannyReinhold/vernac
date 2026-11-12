// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.jdbc.*;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.vernac.compiler.application.*;
import org.vernac.compiler.modules.*;
import org.vernac.compiler.pipeline.VernacCompiler;
import org.vernac.runtime.authorization.AuthorizationException;
import org.vernac.runtime.tenancy.TenantContext;
import org.vernac.runtime.tenancy.TenantId;
import org.vernac.saas.identity.domain.*;
import org.vernac.saas.identity.persistence.domain.*;
import org.vernac.saas.identity.security.*;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

class IdentityJdbcModuleIT {
    private static final String SCHEMA = "org.vernac.saas.identity.persistence";
    private static final String TABLE = "\"" + SCHEMA + "\".\"Identity\"";
    @TempDir Path root;

    @Test
    void packagedProviderExportsOnlyAdapterAndConsumerActivatesExplicitly() throws Exception {
        var environment = new IdentityJdbcTestEnvironment(root);
        var modules = environment.modules();
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
        try (var code = environment.consumer(modules)) {
            assertNotNull(code.loadClass("consumer.identity.configuration.__VernacApplication"));
        }
        Path source = Files.createDirectories(root.resolve("forbidden"));
        Files.createDirectories(source.resolve("forbidden"));
        Path file = source.resolve("forbidden/model.vernac");
        Files.writeString(
                file,
                "namespace forbidden; import org.vernac.saas.identity.UserId; value"
                    + " Allowed(String);");
        assertDoesNotThrow(() -> new VernacCompiler().analyzeProject(source, modules));
        for (String internal :
                List.of(
                        "Identity",
                        "IdentityCapacity",
                        "IdentityCapacityId",
                        "IdentityCapacityQuantity",
                        "IdentityCapacityRepository")) {
            String type = "org.vernac.saas.identity.persistence." + internal;
            Files.writeString(
                    file, "namespace forbidden; import " + type + "; value Allowed(String);");
            var error =
                    assertThrows(
                            org.vernac.compiler.analyzer.SemanticValidationException.class,
                            () -> new VernacCompiler().analyzeProject(source, modules));
            assertTrue(
                    error.getMessage().contains(type),
                    "The import itself must fail, not a later invalid field");
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VERNAC_PG_TEST_URL", matches = ".+")
    void realModuleMigrationTransactionsRevocationAndRestrictedRuntime() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(database -> {
                    UserId[] committed = new UserId[1];
                    database.boot.run(context ->
                            committed[0] =
                                    exerciseDirectory(
                                            context,
                                            database.url,
                                            database.credentials,
                                            database.role,
                                            database.password));
                    database.boot.run(context -> {
                        assertNull(context.getStartupFailure());
                        assertEquals(
                                IdentityStatus.CLOSED,
                                context.getBean(IdentityDirectory.class)
                                        .status(committed[0])
                                        .orElseThrow());
                    });
                    assertEquals(2, database.migrationCount());
                });
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
            tx.executeWithoutResult(status -> {
                assertTrue(directory.status(UserId.create()).isEmpty());
                assertEquals(selected, TenantContext.requireTenant());
            });
        }
        assertTrue(TenantContext.currentTenant().isEmpty());
        var rolledBack = Identity.create(IdentityStatus.PENDING_VERIFICATION);
        tx.executeWithoutResult(status -> {
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
            tx.executeWithoutResult(status -> {
                var stored = repository.byId(identity.id());
                stored.activate();
                repository.save(stored);
            });
            assertTrue(resolver.resolve().isPresent());
            tx.executeWithoutResult(status -> {
                var stored = repository.byId(identity.id());
                stored.disable();
                repository.save(stored);
            });
            assertTrue(resolver.resolve().isEmpty());
            tx.executeWithoutResult(status -> {
                var stored = repository.byId(identity.id());
                assertThrows(org.vernac.runtime.DomainValidationException.class, stored::activate);
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
