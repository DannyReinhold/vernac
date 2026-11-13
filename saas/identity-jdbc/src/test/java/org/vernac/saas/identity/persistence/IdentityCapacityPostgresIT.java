// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.vernac.saas.identity.persistence.JdbcIdentityCapacity.Resource.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.vernac.compiler.modules.ModulePersistence;
import org.vernac.runtime.DomainValidationException;
import org.vernac.runtime.tenancy.*;
import org.vernac.saas.identity.domain.*;
import org.vernac.saas.identity.persistence.domain.*;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;

@EnabledIfEnvironmentVariable(named = "VERNAC_PG_TEST_URL", matches = ".+")
class IdentityCapacityPostgresIT {
    @TempDir Path root;

    private static TransactionTemplate transactions(AssertableApplicationContext context) {
        assertNull(context.getStartupFailure());
        return new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    private static JdbcIdentityCapacity capacity(AssertableApplicationContext context) {
        assertNull(context.getStartupFailure());
        return new JdbcIdentityCapacity(context.getBean(NamedParameterJdbcTemplate.class));
    }

    private static Map<JdbcIdentityCapacity.Resource, Long> limits(long pending, long challenges) {
        var result =
                new EnumMap<JdbcIdentityCapacity.Resource, Long>(JdbcIdentityCapacity.defaults());
        result.put(PENDING_REGISTRATIONS, pending);
        result.put(ACTIONABLE_CHALLENGES, challenges);
        return result;
    }

    @Test
    void bundleReservationsRollbackLimitsAndAccountingRemainConsistent() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(database -> {
                    database.boot.run(context -> {
                        var tx = transactions(context);
                        var ledger = capacity(context);
                        var uninitialized =
                                assertThrows(
                                        JdbcIdentityCapacity.Failure.class,
                                        () ->
                                                tx.execute(status ->
                                                        ledger.reserve(
                                                                Map.of(
                                                                        PENDING_REGISTRATIONS,
                                                                        1L))));
                        assertEquals(
                                JdbcIdentityCapacity.Code.NOT_INITIALIZED, uninitialized.code());
                        tx.executeWithoutResult(status -> ledger.initialize(limits(3, 1)));
                        assertEquals(
                                Boolean.FALSE,
                                tx.execute(status ->
                                        ledger.reserve(
                                                Map.of(
                                                        PENDING_REGISTRATIONS,
                                                        1L,
                                                        ACTIONABLE_CHALLENGES,
                                                        2L))));
                        assertThrows(
                                DomainValidationException.class,
                                () ->
                                        tx.execute(status ->
                                                ledger.withBudgetLock(() -> {
                                                    throw new DomainValidationException(
                                                            "Safe fixture rejection");
                                                })));
                        var pending = Identity.create(IdentityStatus.PENDING_VERIFICATION, IdentitySecurityEpoch.of(0));
                        tx.executeWithoutResult(status -> {
                            assertEquals(0, ledger.usage(PENDING_REGISTRATIONS));
                            assertEquals(0, ledger.usage(ACTIONABLE_CHALLENGES));
                            assertTrue(
                                    ledger.reserve(
                                            Map.of(
                                                    PENDING_REGISTRATIONS,
                                                    2L,
                                                    ACTIONABLE_CHALLENGES,
                                                    1L)));
                            assertEquals(2, ledger.usage(PENDING_REGISTRATIONS));
                            ledger.withBudgetLock(() -> {
                                context.getBean(IdentityRepository.class).save(pending);
                                return null;
                            });
                            status.setRollbackOnly();
                        });
                        assertTrue(
                                context.getBean(IdentityDirectory.class)
                                        .status(pending.id())
                                        .isEmpty());
                        tx.executeWithoutResult(status -> {
                            assertEquals(0, ledger.usage(PENDING_REGISTRATIONS));
                            assertEquals(0, ledger.usage(ACTIONABLE_CHALLENGES));
                            assertTrue(
                                    ledger.reserve(
                                            Map.of(
                                                    PENDING_REGISTRATIONS,
                                                    2L,
                                                    ACTIONABLE_CHALLENGES,
                                                    1L)));
                            assertEquals(
                                    JdbcIdentityCapacity.Code.ACCOUNTING_MISMATCH,
                                    assertThrows(
                                                    JdbcIdentityCapacity.Failure.class,
                                                    () ->
                                                            ledger.release(
                                                                    Map.of(
                                                                            PENDING_REGISTRATIONS,
                                                                            1L,
                                                                            ACTIONABLE_CHALLENGES,
                                                                            2L)))
                                            .code());
                            assertEquals(2, ledger.usage(PENDING_REGISTRATIONS));
                            ledger.changeLimit(PENDING_REGISTRATIONS, 1L);
                            assertEquals(2, ledger.usage(PENDING_REGISTRATIONS));
                            assertFalse(ledger.reserve(Map.of(PENDING_REGISTRATIONS, 1L)));
                            ledger.release(Map.of(PENDING_REGISTRATIONS, 2L));
                            assertTrue(ledger.reserve(Map.of(PENDING_REGISTRATIONS, 1L)));
                        });
                        var mismatch =
                                assertThrows(
                                        JdbcIdentityCapacity.Failure.class,
                                        () ->
                                                tx.executeWithoutResult(
                                                        status -> ledger.initialize(limits(3, 1))));
                        assertEquals(
                                JdbcIdentityCapacity.Code.CONFIGURATION_MISMATCH, mismatch.code());
                        assertNull(mismatch.getCause());
                        assertThrows(
                                DomainValidationException.class,
                                () ->
                                        tx.execute(status ->
                                                ledger.reserve(Map.of(PENDING_REGISTRATIONS, 0L))));
                        tx.executeWithoutResult(status -> {
                            ledger.changeLimit(UNSENT_MAIL_BYTES, Long.MAX_VALUE);
                            assertTrue(ledger.reserve(Map.of(UNSENT_MAIL_BYTES, Long.MAX_VALUE)));
                            assertFalse(ledger.reserve(Map.of(UNSENT_MAIL_BYTES, 1L)));
                        });
                        assertEquals(
                                JdbcIdentityCapacity.Resource.values().length,
                                context.getBean(NamedParameterJdbcTemplate.class)
                                        .getJdbcTemplate()
                                        .queryForObject(
                                                "SELECT count(*) FROM"
                                                    + " \"org.vernac.saas.identity.persistence\".\"IdentityCapacity\"",
                                                Integer.class));
                        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    });
                    database.boot.run(context -> {
                        var tx = transactions(context);
                        var ledger = capacity(context);
                        tx.executeWithoutResult(status -> {
                            assertEquals(1, ledger.usage(PENDING_REGISTRATIONS));
                            assertEquals(1, ledger.usage(ACTIONABLE_CHALLENGES));
                            assertEquals(Long.MAX_VALUE, ledger.usage(UNSENT_MAIL_BYTES));
                        });
                    });
                    assertEquals(7, database.migrationCount());
                });
    }

    @Test
    void twoApplicationContextsCompeteForTheLastSlotAndObserveRollback() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(database ->
                        database.boot.run(first ->
                                database.boot.run(second -> {
                                    var a = capacity(first);
                                    var b = capacity(second);
                                    var ta = transactions(first);
                                    var tb = transactions(second);
                                    assertNotSame(
                                            first.getBean(PlatformTransactionManager.class),
                                            second.getBean(PlatformTransactionManager.class));
                                    ta.executeWithoutResult(status -> a.initialize(limits(1, 1)));
                                    try (var workers = Executors.newFixedThreadPool(8)) {
                                        var start = new CountDownLatch(1);
                                        var ready = new CountDownLatch(8);
                                        var results = new ArrayList<Future<Boolean>>();
                                        for (int i = 0; i < 8; i++) {
                                            boolean left = i % 2 == 0;
                                            results.add(
                                                    workers.submit(() -> {
                                                        ready.countDown();
                                                        assertTrue(
                                                                start.await(20, TimeUnit.SECONDS));
                                                        return (left ? ta : tb)
                                                                .execute(status ->
                                                                        (left ? a : b)
                                                                                .reserve(
                                                                                        Map.of(
                                                                                                PENDING_REGISTRATIONS,
                                                                                                1L,
                                                                                                ACTIONABLE_CHALLENGES,
                                                                                                1L)));
                                                    }));
                                        }
                                        assertTrue(ready.await(20, TimeUnit.SECONDS));
                                        start.countDown();
                                        int accepted = 0;
                                        for (var future : results)
                                            if (future.get(30, TimeUnit.SECONDS)) accepted++;
                                        assertEquals(1, accepted);
                                        tb.executeWithoutResult(status -> {
                                            assertEquals(1, b.usage(PENDING_REGISTRATIONS));
                                            assertEquals(1, b.usage(ACTIONABLE_CHALLENGES));
                                        });
                                        ta.executeWithoutResult(status ->
                                                a.release(
                                                        Map.of(
                                                                PENDING_REGISTRATIONS,
                                                                1L,
                                                                ACTIONABLE_CHALLENGES,
                                                                1L)));
                                        var reserved = new CountDownLatch(1);
                                        var finish = new CountDownLatch(1);
                                        var rollback =
                                                workers.submit(() ->
                                                        ta.execute(status -> {
                                                            assertTrue(
                                                                    a.reserve(
                                                                            Map.of(
                                                                                    PENDING_REGISTRATIONS,
                                                                                    1L)));
                                                            reserved.countDown();
                                                            try {
                                                                assertTrue(
                                                                        finish.await(
                                                                                20,
                                                                                TimeUnit.SECONDS));
                                                            } catch (InterruptedException e) {
                                                                Thread.currentThread().interrupt();
                                                                throw new AssertionError(e);
                                                            }
                                                            status.setRollbackOnly();
                                                            return true;
                                                        }));
                                        assertTrue(reserved.await(20, TimeUnit.SECONDS));
                                        var started = new CountDownLatch(1);
                                        var following =
                                                workers.submit(() ->
                                                        tb.execute(status -> {
                                                            started.countDown();
                                                            return b.reserve(
                                                                    Map.of(
                                                                            PENDING_REGISTRATIONS,
                                                                            1L));
                                                        }));
                                        assertTrue(started.await(20, TimeUnit.SECONDS));
                                        try {
                                            assertThrows(
                                                    TimeoutException.class,
                                                    () ->
                                                            following.get(
                                                                    150, TimeUnit.MILLISECONDS));
                                        } finally {
                                            finish.countDown();
                                        }
                                        assertTrue(rollback.get(30, TimeUnit.SECONDS));
                                        assertTrue(following.get(30, TimeUnit.SECONDS));
                                        tb.executeWithoutResult(status ->
                                                assertEquals(1, b.usage(PENDING_REGISTRATIONS)));
                                    }
                                })));
    }

    @Test
    void wrongOrReadOnlyTransactionsFailAndTenantScopeAndVersionsArePreserved() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(database ->
                        database.boot.run(context -> {
                            var tx = transactions(context);
                            var ledger = capacity(context);
                            assertThrows(
                                    IllegalTransactionStateException.class,
                                    () -> ledger.initialize(JdbcIdentityCapacity.defaults()));
                            tx.executeWithoutResult(
                                    status -> ledger.initialize(JdbcIdentityCapacity.defaults()));
                            var readOnly =
                                    new TransactionTemplate(
                                            context.getBean(PlatformTransactionManager.class));
                            readOnly.setReadOnly(true);
                            assertThrows(
                                    IllegalTransactionStateException.class,
                                    () ->
                                            readOnly.execute(status ->
                                                    ledger.reserve(
                                                            Map.of(PENDING_REGISTRATIONS, 1L))));
                            var wrongSource =
                                    new DriverManagerDataSource(
                                            database.url, database.role, database.password);
                            var wrong =
                                    new JdbcIdentityCapacity(
                                            new NamedParameterJdbcTemplate(wrongSource));
                            assertThrows(
                                    IllegalTransactionStateException.class,
                                    () ->
                                            tx.execute(status ->
                                                    wrong.reserve(
                                                            Map.of(PENDING_REGISTRATIONS, 1L))));
                            var otherTransaction =
                                    new TransactionTemplate(
                                            new org.springframework.jdbc.datasource
                                                    .DataSourceTransactionManager(wrongSource));
                            assertThrows(
                                    IllegalTransactionStateException.class,
                                    () ->
                                            tx.execute(status ->
                                                    otherTransaction.execute(other ->
                                                            ledger.reserve(
                                                                    Map.of(
                                                                            PENDING_REGISTRATIONS,
                                                                            1L)))));
                            var selected = TenantId.of(UUID.randomUUID());
                            try (var scope = TenantContext.openVerified(selected)) {
                                tx.executeWithoutResult(status -> {
                                    assertTrue(ledger.reserve(Map.of(PENDING_REGISTRATIONS, 1L)));
                                    assertEquals(selected, TenantContext.requireTenant());
                                });
                                assertEquals(selected, TenantContext.requireTenant());
                            }
                            assertTrue(TenantContext.currentTenant().isEmpty());
                            var repository = context.getBean(IdentityCapacityRepository.class);
                            var stale =
                                    tx.execute(
                                            status -> repository.byId(PENDING_REGISTRATIONS.id()));
                            tx.executeWithoutResult(status ->
                                    assertTrue(ledger.reserve(Map.of(PENDING_REGISTRATIONS, 1L))));
                            assertThrows(
                                    org.springframework.dao.OptimisticLockingFailureException.class,
                                    () ->
                                            tx.executeWithoutResult(
                                                    status -> repository.save(stale)));
                            tx.executeWithoutResult(
                                    status -> assertEquals(2, ledger.usage(PENDING_REGISTRATIONS)));
                            String table =
                                    org.vernac.compiler.persistence.SqlNames.quote(
                                                    IdentityJdbcTestEnvironment.SCHEMA)
                                            + "."
                                            + org.vernac.compiler.persistence.SqlNames.quote(
                                                    "IdentityCapacity");
                            try (var migration =
                                            DriverManager.getConnection(
                                                    database.url, database.credentials);
                                    var sql = migration.createStatement()) {
                                sql.execute("REVOKE SELECT ON " + table + " FROM " + database.role);
                                try {
                                    var failure =
                                            assertThrows(
                                                    JdbcIdentityCapacity.Failure.class,
                                                    () ->
                                                            tx.execute(status ->
                                                                    ledger.reserve(
                                                                            Map.of(
                                                                                    PENDING_REGISTRATIONS,
                                                                                    1L))));
                                    assertEquals(
                                            JdbcIdentityCapacity.Code.STORAGE_UNAVAILABLE,
                                            failure.code());
                                    assertNull(failure.getCause());
                                    assertFalse(failure.toString().contains(database.password));
                                } finally {
                                    sql.execute(
                                            "GRANT SELECT ON " + table + " TO " + database.role);
                                }
                            }
                            tx.executeWithoutResult(status ->
                                    assertTrue(ledger.reserve(Map.of(PENDING_REGISTRATIONS, 1L))));
                            tx.executeWithoutResult(
                                    status -> assertEquals(3, ledger.usage(PENDING_REGISTRATIONS)));
                        }));
    }

    @Test
    void upgradingThePublishedIdentitySchemaPreservesExistingIdentities() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(database -> {
                    String history = ModulePersistence.historySchema("vernac.saas.identity.jdbc");
                    Flyway.configure(database.code)
                            .dataSource(
                                    database.url,
                                    database.credentials.getProperty("user"),
                                    database.credentials.getProperty("password"))
                            .locations(
                                    "classpath:"
                                            + ModulePersistence.directory(
                                                    "vernac.saas.identity.jdbc")
                                            + "migration")
                            .schemas(history)
                            .defaultSchema(history)
                            .target("20261112150000000")
                            .load()
                            .migrate();
                    var userId = UserId.create();
                    try (var connection =
                                    DriverManager.getConnection(
                                            database.url, database.credentials);
                            var sql =
                                    connection.prepareStatement(
                                            "INSERT INTO"
                                                + " \"org.vernac.saas.identity.persistence\".\"Identity\""
                                                + " (\"@createdAt\",\"@nanoRemainder:@createdAt\",\"@updatedAt\",\"@nanoRemainder:@updatedAt\",\"@version\",\"id\",\"status\")"
                                                + " VALUES (now(),0,now(),0,1,?,'ACTIVE')")) {
                        sql.setObject(1, userId.value());
                        sql.executeUpdate();
                    }
                    assertEquals(1, database.migrationCount());
                    database.boot.run(context -> {
                        assertNull(context.getStartupFailure());
                        assertEquals(
                                IdentityStatus.ACTIVE,
                                context.getBean(IdentityDirectory.class)
                                        .status(userId)
                                        .orElseThrow());
                        var tx = transactions(context);
                        var ledger = capacity(context);
                        tx.executeWithoutResult(status -> {
                            assertEquals(0, context.getBean(IdentityRepository.class).byId(userId).securityEpoch().number());
                            ledger.initialize(JdbcIdentityCapacity.defaults());
                            assertEquals(0, ledger.usage(PENDING_REGISTRATIONS));
                        });
                    });
                    assertEquals(7, database.migrationCount());
                });
    }
}
