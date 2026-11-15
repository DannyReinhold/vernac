// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.vernac.saas.identity.persistence.JdbcIdentityCapacity.Resource.LIMITER_KEYS;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.vernac.saas.identity.domain.*;
import org.vernac.saas.identity.persistence.domain.*;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@EnabledIfEnvironmentVariable(named = "VERNAC_PG_TEST_URL", matches = ".+")
class IdentityIngressPostgresIT {
    @TempDir Path root;
    static final Instant NOW = Instant.parse("2026-11-12T12:00:59.999999999Z");

    static TransactionTemplate tx(AssertableApplicationContext c) {
        assertNull(c.getStartupFailure());
        return new TransactionTemplate(c.getBean(PlatformTransactionManager.class));
    }

    static NamedParameterJdbcTemplate jdbc(AssertableApplicationContext c) {
        return c.getBean(NamedParameterJdbcTemplate.class);
    }

    static JdbcIdentityIngress gate(AssertableApplicationContext c, Instant now) {
        assertNull(c.getStartupFailure());
        return new JdbcIdentityIngress(
                jdbc(c),
                c.getBean(PlatformTransactionManager.class),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    static void capacity(AssertableApplicationContext c) {
        tx(c).executeWithoutResult(
                        s ->
                                new JdbcIdentityCapacity(jdbc(c))
                                        .initialize(JdbcIdentityCapacity.defaults()));
    }

    static long keys(AssertableApplicationContext c) {
        return tx(c).execute(s -> new JdbcIdentityCapacity(jdbc(c)).usage(LIMITER_KEYS));
    }

    static IdentityIngress stored(AssertableApplicationContext c) {
        return tx(c).execute(
                        s ->
                                c.getBean(IdentityIngressRepository.class)
                                        .byId(JdbcIdentityIngress.ID));
    }

    @Test
    void exactWindowsRollbackRestartAndConfigurationPreserveSpentAttempts() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db -> {
                            db.boot.run(
                                    c -> {
                                        capacity(c);
                                        var gate = gate(c, NOW);
                                        gate.initialize(2);
                                        assertEquals(1, keys(c));
                                        assertTrue(gate.tryAcquire().admitted());
                                        var pending =
                                                Identity.create(IdentityStatus.PENDING_VERIFICATION, IdentitySecurityEpoch.of(0));
                                        tx(c).executeWithoutResult(
                                                        s -> {
                                                            c.getBean(IdentityRepository.class)
                                                                    .save(pending);
                                                            s.setRollbackOnly();
                                                        });
                                        assertTrue(
                                                c.getBean(IdentityDirectory.class)
                                                        .status(pending.id())
                                                        .isEmpty());
                                        assertEquals(1, stored(c).used().amount());
                                        assertTrue(gate.tryAcquire().admitted());
                                        assertFalse(gate.tryAcquire().admitted());
                                        assertEquals(1, gate.tryAcquire().retryAfterSeconds());
                                        gate.initialize(2);
                                        assertFalse(gate.tryAcquire().admitted());
                                        assertEquals(1, keys(c));
                                        assertEquals(
                                                JdbcIdentityIngress.Code.CONFIGURATION_MISMATCH,
                                                assertThrows(
                                                                JdbcIdentityIngress.Failure.class,
                                                                () -> gate.initialize(3))
                                                        .code());
                                        gate.changeLimit(1);
                                        assertFalse(gate.tryAcquire().admitted());
                                        assertEquals(2, stored(c).used().amount());
                                        gate.changeLimit(3);
                                        assertTrue(gate.tryAcquire().admitted());
                                        assertFalse(
                                                gate(c, NOW.minusSeconds(120))
                                                        .tryAcquire()
                                                        .admitted());
                                    });
                            db.boot.run(
                                    c -> {
                                        var gate = gate(c, NOW);
                                        gate.initialize(3);
                                        assertFalse(gate.tryAcquire().admitted());
                                        assertEquals(3, stored(c).used().amount());
                                        var next = gate(c, NOW.plusNanos(1));
                                        assertTrue(next.tryAcquire().admitted());
                                        assertEquals(1, stored(c).used().amount());
                                        assertTrue(next.tryAcquire().admitted());
                                        assertTrue(next.tryAcquire().admitted());
                                        var denied = next.tryAcquire();
                                        assertFalse(denied.admitted());
                                        assertEquals(60, denied.retryAfterSeconds());
                                        assertEquals(1, keys(c));
                                    });
                            assertEquals(11, db.migrationCount());
                        });
    }

    @Test
    void twoContextsBootstrapOnceAndCompeteForTheLastAdmission() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        a ->
                                                db.boot.run(
                                                        b -> {
                                                            capacity(a);
                                                            var left = gate(a, NOW);
                                                            var right = gate(b, NOW);
                                                            try (var pool =
                                                                    Executors.newFixedThreadPool(
                                                                            8)) {
                                                                var start = new CountDownLatch(1);
                                                                var first =
                                                                        pool.submit(
                                                                                () -> {
                                                                                    start.await();
                                                                                    left.initialize(
                                                                                            1);
                                                                                    return true;
                                                                                });
                                                                var second =
                                                                        pool.submit(
                                                                                () -> {
                                                                                    start.await();
                                                                                    right
                                                                                            .initialize(
                                                                                                    1);
                                                                                    return true;
                                                                                });
                                                                start.countDown();
                                                                assertTrue(
                                                                        first.get(
                                                                                30,
                                                                                TimeUnit.SECONDS));
                                                                assertTrue(
                                                                        second.get(
                                                                                30,
                                                                                TimeUnit.SECONDS));
                                                                assertEquals(1, keys(a));
                                                                var race = new CountDownLatch(1);
                                                                var ready = new CountDownLatch(8);
                                                                var futures =
                                                                        new ArrayList<
                                                                                Future<Boolean>>();
                                                                for (int i = 0; i < 8; i++) {
                                                                    var chosen =
                                                                            i % 2 == 0
                                                                                    ? left
                                                                                    : right;
                                                                    futures.add(
                                                                            pool.submit(
                                                                                    () -> {
                                                                                        ready
                                                                                                .countDown();
                                                                                        race
                                                                                                .await();
                                                                                        return chosen.tryAcquire()
                                                                                                .admitted();
                                                                                    }));
                                                                }
                                                                assertTrue(
                                                                        ready.await(
                                                                                20,
                                                                                TimeUnit.SECONDS));
                                                                race.countDown();
                                                                int accepted = 0;
                                                                for (var f : futures)
                                                                    if (f.get(30, TimeUnit.SECONDS))
                                                                        accepted++;
                                                                assertEquals(1, accepted);
                                                                assertEquals(
                                                                        1,
                                                                        stored(b).used().amount());
                                                                assertEquals(
                                                                        1,
                                                                        jdbc(a).getJdbcTemplate()
                                                                                .queryForObject(
                                                                                        "SELECT"
                                                                                            + " count(*)"
                                                                                            + " FROM"
                                                                                            + " \"org.vernac.saas.identity.persistence\".\"IdentityIngress\"",
                                                                                        Integer
                                                                                                .class));
                                                            } catch (Exception e) {
                                                                throw new AssertionError(e);
                                                            }
                                                        })));
    }

    @Test
    void missingCapacityWrongBoundariesAndStorageFailureFailClosed() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            var gate = gate(c, NOW);
                                            assertEquals(
                                                    JdbcIdentityIngress.Code.NOT_INITIALIZED,
                                                    assertThrows(
                                                                    JdbcIdentityIngress.Failure
                                                                            .class,
                                                                    gate::tryAcquire)
                                                            .code());
                                            assertThrows(
                                                    JdbcIdentityCapacity.Failure.class,
                                                    () -> gate.initialize(2));
                                            capacity(c);
                                            tx(c).executeWithoutResult(
                                                            s -> {
                                                                var ledger =
                                                                        new JdbcIdentityCapacity(
                                                                                jdbc(c));
                                                                ledger.changeLimit(LIMITER_KEYS, 1);
                                                                assertTrue(
                                                                        ledger.reserve(
                                                                                Map.of(
                                                                                        LIMITER_KEYS,
                                                                                        1L)));
                                                            });
                                            assertEquals(
                                                    JdbcIdentityIngress.Code.KEY_CAPACITY,
                                                    assertThrows(
                                                                    JdbcIdentityIngress.Failure
                                                                            .class,
                                                                    () -> gate.initialize(2))
                                                            .code());
                                            assertEquals(
                                                    0,
                                                    jdbc(c).getJdbcTemplate()
                                                            .queryForObject(
                                                                    "SELECT count(*) FROM"
                                                                        + " \"org.vernac.saas.identity.persistence\".\"IdentityIngress\"",
                                                                    Integer.class));
                                            tx(c).executeWithoutResult(
                                                            s ->
                                                                    new JdbcIdentityCapacity(
                                                                                    jdbc(c))
                                                                            .release(
                                                                                    Map.of(
                                                                                            LIMITER_KEYS,
                                                                                            1L)));
                                            gate.initialize(2);
                                            assertThrows(
                                                    IllegalArgumentException.class,
                                                    () -> gate.initialize(0));
                                            assertThrows(
                                                    IllegalArgumentException.class,
                                                    () -> gate.changeLimit(-1));
                                            tx(c).executeWithoutResult(
                                                            s ->
                                                                    assertThrows(
                                                                            IllegalTransactionStateException
                                                                                    .class,
                                                                            gate::tryAcquire));
                                            var wrongSource =
                                                    new DriverManagerDataSource(
                                                            db.url, db.role, db.password);
                                            var wrong =
                                                    new JdbcIdentityIngress(
                                                            jdbc(c),
                                                            new DataSourceTransactionManager(
                                                                    wrongSource),
                                                            Clock.fixed(NOW, ZoneOffset.UTC));
                                            var mismatch =
                                                    assertThrows(
                                                            JdbcIdentityIngress.Failure.class,
                                                            wrong::tryAcquire);
                                            assertEquals(
                                                    JdbcIdentityIngress.Code.STORAGE_UNAVAILABLE,
                                                    mismatch.code());
                                            assertNull(mismatch.getCause());
                                            try (var admin =
                                                            DriverManager.getConnection(
                                                                    db.url, db.credentials);
                                                    var sql = admin.createStatement()) {
                                                String table =
                                                        "\"org.vernac.saas.identity.persistence\".\"IdentityIngress\"";
                                                sql.execute(
                                                        "REVOKE SELECT ON "
                                                                + table
                                                                + " FROM "
                                                                + db.role);
                                                try {
                                                    var failure =
                                                            assertThrows(
                                                                    JdbcIdentityIngress.Failure
                                                                            .class,
                                                                    gate::tryAcquire);
                                                    assertEquals(
                                                            JdbcIdentityIngress.Code
                                                                    .STORAGE_UNAVAILABLE,
                                                            failure.code());
                                                    assertNull(failure.getCause());
                                                    assertFalse(
                                                            failure.toString()
                                                                    .contains(db.password));
                                                } finally {
                                                    sql.execute(
                                                            "GRANT SELECT ON "
                                                                    + table
                                                                    + " TO "
                                                                    + db.role);
                                                }
                                            } catch (Exception e) {
                                                throw new AssertionError(e);
                                            }
                                            assertTrue(gate.tryAcquire().admitted());
                                            assertEquals(1, stored(c).used().amount());
                                            assertEquals(1, keys(c));
                                        }));
    }

    @Test
    void failedBootstrapRollsBackItsKeyReservation() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            capacity(c);
                                            var gate = gate(c, NOW);
                                            try (var admin =
                                                            DriverManager.getConnection(
                                                                    db.url, db.credentials);
                                                    var sql = admin.createStatement()) {
                                                String table =
                                                        "\"org.vernac.saas.identity.persistence\".\"IdentityIngress\"";
                                                sql.execute(
                                                        "REVOKE INSERT ON "
                                                                + table
                                                                + " FROM "
                                                                + db.role);
                                                try {
                                                    var failure =
                                                            assertThrows(
                                                                    JdbcIdentityIngress.Failure
                                                                            .class,
                                                                    () -> gate.initialize(2));
                                                    assertEquals(
                                                            JdbcIdentityIngress.Code
                                                                    .STORAGE_UNAVAILABLE,
                                                            failure.code());
                                                    assertNull(failure.getCause());
                                                    assertEquals(0, keys(c));
                                                } finally {
                                                    sql.execute(
                                                            "GRANT INSERT ON "
                                                                    + table
                                                                    + " TO "
                                                                    + db.role);
                                                }
                                            } catch (Exception e) {
                                                throw new AssertionError(e);
                                            }
                                            gate.initialize(2);
                                            assertEquals(1, keys(c));
                                            jdbc(c).getJdbcTemplate()
                                                    .update(
                                                            "UPDATE"
                                                                + " \"org.vernac.saas.identity.persistence\".\"IdentityIngress\""
                                                                + " SET \"limit\"=0");
                                            var corrupt =
                                                    assertThrows(
                                                            JdbcIdentityIngress.Failure.class,
                                                            gate::tryAcquire);
                                            assertEquals(
                                                    JdbcIdentityIngress.Code.STORAGE_UNAVAILABLE,
                                                    corrupt.code());
                                            assertNull(corrupt.getCause());
                                            jdbc(c).getJdbcTemplate()
                                                    .update(
                                                            "UPDATE"
                                                                + " \"org.vernac.saas.identity.persistence\".\"IdentityIngress\""
                                                                + " SET \"limit\"=2");
                                            assertTrue(gate.tryAcquire().admitted());
                                        }));
    }
}
