// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.vernac.saas.identity.persistence.JdbcIdentityAdmission.Outcome.*;
import static org.vernac.saas.identity.persistence.JdbcIdentityCapacity.Resource.*;
import static org.vernac.saas.identity.persistence.domain.IdentityAdmissionKind.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.vernac.saas.identity.domain.IdentityStatus;
import org.vernac.saas.identity.persistence.domain.*;
import org.vernac.saas.identity.security.credentials.LoginAddress;

import java.net.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

import javax.crypto.spec.SecretKeySpec;

@EnabledIfEnvironmentVariable(named = "VERNAC_PG_TEST_URL", matches = ".+")
class IdentityAdmissionPostgresIT {
    @TempDir Path root;
    static final Instant NOW = Instant.parse("2026-11-13T09:00:00Z");

    static SecretKeySpec key(int fill) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) fill);
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    static InetAddress ip(int last) {
        try {
            return InetAddress.getByAddress(new byte[] {(byte) 192, 0, 2, (byte) last});
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    static LoginAddress address(int n) {
        return LoginAddress.parse("person" + n + "@example.invalid");
    }

    static final class Fixture {
        final AssertableApplicationContext context;
        final NamedParameterJdbcTemplate jdbc;
        final PlatformTransactionManager manager;

        Fixture(AssertableApplicationContext c) {
            assertNull(c.getStartupFailure());
            context = c;
            jdbc = c.getBean(NamedParameterJdbcTemplate.class);
            manager = c.getBean(PlatformTransactionManager.class);
        }

        <T> T write(Supplier<T> action) {
            return new TransactionTemplate(manager).execute(s -> action.get());
        }

        JdbcIdentityAdmission gate(
                Instant now,
                Map<IdentityAdmissionKind, JdbcIdentityAdmission.Rule> rules,
                int key) {
            return new JdbcIdentityAdmission(
                    jdbc,
                    manager,
                    Clock.fixed(now, ZoneOffset.UTC),
                    "deployment-v1",
                    key(key),
                    rules);
        }

        JdbcIdentityAdmission gate(Instant now) {
            return gate(now, JdbcIdentityAdmission.defaults(), 7);
        }

        void setup(long capacity) {
            write(
                    () -> {
                        var limits =
                                new EnumMap<JdbcIdentityCapacity.Resource, Long>(
                                        JdbcIdentityCapacity.defaults());
                        limits.put(LIMITER_KEYS, capacity);
                        new JdbcIdentityCapacity(jdbc).initialize(limits);
                        return true;
                    });
            new JdbcIdentityIngress(jdbc, manager, Clock.fixed(NOW, ZoneOffset.UTC))
                    .initialize(10000);
            gate(NOW).initialize();
        }

        long usage() {
            return write(() -> new JdbcIdentityCapacity(jdbc).usage(LIMITER_KEYS));
        }

        long rows() {
            return jdbc.getJdbcTemplate()
                    .queryForObject(
                            "SELECT count(*) FROM"
                                + " \"org.vernac.saas.identity.persistence\".\"IdentityAdmissionKey\"",
                            Long.class);
        }

        long globalUsed() {
            return write(
                    () ->
                            context.getBean(IdentityIngressRepository.class)
                                    .byId(JdbcIdentityIngress.ID)
                                    .used()
                                    .amount());
        }
    }

    @Test
    void defaultPoliciesCanonicalKeysPurposesAndSourceAttemptsAreIndependent() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            var f = new Fixture(c);
                                            f.setup(50000);
                                            var gate = f.gate(NOW);
                                            for (int i = 0; i < 3; i++)
                                                assertTrue(gate.mail(ip(1), address(1)).admitted());
                                            assertEquals(
                                                    SUBJECT_THROTTLED,
                                                    gate.mail(
                                                                    ip(1),
                                                                    LoginAddress.parse(
                                                                            "PERSON1@EXAMPLE.INVALID"))
                                                            .outcome());
                                            for (int i = 0; i < 16; i++)
                                                assertTrue(
                                                        gate.mail(ip(1), address(i + 10))
                                                                .admitted());
                                            long count = f.rows();
                                            assertEquals(
                                                    SOURCE_THROTTLED,
                                                    gate.mail(ip(1), address(99)).outcome());
                                            assertEquals(count, f.rows());
                                            for (int i = 0; i < 10; i++)
                                                assertTrue(
                                                        gate.login(ip(1), address(1)).admitted());
                                            assertEquals(
                                                    SUBJECT_THROTTLED,
                                                    gate.login(ip(2), address(1)).outcome());
                                            String locator = UUID.randomUUID().toString();
                                            for (int i = 0; i < 10; i++)
                                                assertTrue(
                                                        gate.challenge(ip(1), locator).admitted());
                                            assertEquals(
                                                    SUBJECT_THROTTLED,
                                                    gate.challenge(ip(1), locator).outcome());
                                            long before = f.rows();
                                            assertEquals(
                                                    INVALID_INPUT,
                                                    gate.challenge(
                                                                    ip(1),
                                                                    "seeded-secret-token-not-a-locator")
                                                            .outcome());
                                            assertEquals(
                                                    INVALID_INPUT,
                                                    gate.challenge(ip(1), null).outcome());
                                            assertEquals(before, f.rows());
                                            assertEquals(2 + f.rows(), f.usage());
                                            var values =
                                                    f.jdbc.getJdbcTemplate()
                                                            .queryForList(
                                                                    "SELECT \"digest\" FROM"
                                                                        + " \"org.vernac.saas.identity.persistence\".\"IdentityAdmissionKey\"",
                                                                    String.class);
                                            for (String value : values) {
                                                assertTrue(value.matches("[0-9a-f]{64}"));
                                                assertFalse(value.contains("example.invalid"));
                                                assertFalse(value.contains("192.0.2"));
                                            }
                                            for (String table :
                                                    List.of(
                                                            "Identity",
                                                            "IdentityCredential",
                                                            "IdentityChallenge"))
                                                assertEquals(
                                                        0,
                                                        f.jdbc.getJdbcTemplate()
                                                                .queryForObject(
                                                                        "SELECT count(*) FROM"
                                                                            + " \"org.vernac.saas.identity.persistence\".\""
                                                                                + table
                                                                                + "\"",
                                                                        Integer.class));
                                        }));
    }

    @Test
    void windowsRestartBusinessRollbackAndKeyDriftNeverResetSpentAttempts() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db -> {
                            db.boot.run(
                                    c -> {
                                        var f = new Fixture(c);
                                        f.setup(50000);
                                        var gate = f.gate(NOW);
                                        for (int i = 0; i < 3; i++)
                                            assertTrue(gate.mail(ip(1), address(1)).admitted());
                                        var pending =
                                                Identity.create(
                                                        IdentityStatus.PENDING_VERIFICATION,
                                                        IdentitySecurityEpoch.of(0));
                                        new TransactionTemplate(f.manager)
                                                .executeWithoutResult(
                                                        status -> {
                                                            c.getBean(IdentityRepository.class)
                                                                    .save(pending);
                                                            status.setRollbackOnly();
                                                        });
                                        assertTrue(
                                                f.write(
                                                                () ->
                                                                        c.getBean(
                                                                                        IdentityRepository
                                                                                                .class)
                                                                                .existing(
                                                                                        pending
                                                                                                .id()))
                                                        .isEmpty());
                                        assertEquals(3, f.globalUsed());
                                        assertEquals(
                                                SUBJECT_THROTTLED,
                                                gate.mail(ip(1), address(1)).outcome());
                                        gate.initialize();
                                        assertEquals(4, f.globalUsed());
                                        assertEquals(
                                                JdbcIdentityAdmission.Code.CONFIGURATION_MISMATCH,
                                                assertThrows(
                                                                JdbcIdentityAdmission.Failure.class,
                                                                () ->
                                                                        f.gate(
                                                                                        NOW,
                                                                                        JdbcIdentityAdmission
                                                                                                .defaults(),
                                                                                        8)
                                                                                .initialize())
                                                        .code());
                                        var changed =
                                                new EnumMap<
                                                        IdentityAdmissionKind,
                                                        JdbcIdentityAdmission.Rule>(
                                                        JdbcIdentityAdmission.defaults());
                                        changed.put(
                                                MAIL_KEY, new JdbcIdentityAdmission.Rule(4, 3600));
                                        assertEquals(
                                                JdbcIdentityAdmission.Code.CONFIGURATION_MISMATCH,
                                                assertThrows(
                                                                JdbcIdentityAdmission.Failure.class,
                                                                () ->
                                                                        f.gate(NOW, changed, 7)
                                                                                .initialize())
                                                        .code());
                                        assertEquals(
                                                JdbcIdentityAdmission.Code.CONFIGURATION_MISMATCH,
                                                assertThrows(
                                                                JdbcIdentityAdmission.Failure.class,
                                                                () ->
                                                                        f.gate(
                                                                                        NOW,
                                                                                        JdbcIdentityAdmission
                                                                                                .defaults(),
                                                                                        8)
                                                                                .mail(
                                                                                        ip(1),
                                                                                        address(1)))
                                                        .code());
                                        assertEquals(5, f.globalUsed());
                                        assertEquals(2 + f.rows(), f.usage());
                                    });
                            db.boot.run(
                                    c -> {
                                        var f = new Fixture(c);
                                        var gate = f.gate(NOW);
                                        gate.initialize();
                                        assertEquals(
                                                SUBJECT_THROTTLED,
                                                gate.mail(ip(1), address(1)).outcome());
                                        assertEquals(
                                                SUBJECT_THROTTLED,
                                                f.gate(NOW.plusSeconds(3599))
                                                        .mail(ip(1), address(1))
                                                        .outcome());
                                        assertTrue(
                                                f.gate(NOW.plusSeconds(3600))
                                                        .mail(ip(1), address(1))
                                                        .admitted());
                                        for (int i = 0; i < 2; i++)
                                            assertTrue(
                                                    f.gate(NOW.minusSeconds(60))
                                                            .mail(ip(1), address(1))
                                                            .admitted());
                                        assertEquals(
                                                SUBJECT_THROTTLED,
                                                f.gate(NOW.minusSeconds(60))
                                                        .mail(ip(1), address(1))
                                                        .outcome());
                                    });
                            assertEquals(11, db.migrationCount());
                        });
    }

    @Test
    void capacityPressureIsUniformAndBoundedCleanupReclaimsOnlyDeletedRows() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            var f = new Fixture(c);
                                            f.setup(4);
                                            var gate = f.gate(NOW);
                                            assertTrue(gate.login(ip(1), address(1)).admitted());
                                            assertEquals(4, f.usage());
                                            assertEquals(2, f.rows());
                                            for (int i = 0; i < 50; i++) {
                                                int index = i;
                                                assertEquals(
                                                        JdbcIdentityAdmission.Code.KEY_CAPACITY,
                                                        assertThrows(
                                                                        JdbcIdentityAdmission
                                                                                .Failure.class,
                                                                        () ->
                                                                                gate.login(
                                                                                        ip(
                                                                                                index
                                                                                                        + 1),
                                                                                        address(
                                                                                                index
                                                                                                        + 1)))
                                                                .code());
                                            }
                                            assertEquals(
                                                    JdbcIdentityAdmission.Code.KEY_CAPACITY,
                                                    assertThrows(
                                                                    JdbcIdentityAdmission.Failure
                                                                            .class,
                                                                    () ->
                                                                            gate.login(
                                                                                    ip(1),
                                                                                    address(1)))
                                                            .code());
                                            assertEquals(2, f.rows());
                                            assertEquals(4, f.usage());
                                            assertEquals(0, gate.cleanup(1).removed());
                                            assertEquals(4, f.usage());
                                            assertEquals(
                                                    0,
                                                    f.gate(NOW.plusSeconds(899))
                                                            .cleanup(500)
                                                            .removed());
                                            var expired = f.gate(NOW.plusSeconds(900));
                                            int removed = 0;
                                            for (int i = 0; i < 5; i++) {
                                                var result = expired.cleanup(1);
                                                assertTrue(result.scanned() <= 1);
                                                removed += result.removed();
                                                if (result.cycleComplete()) break;
                                            }
                                            // Cursor may need a complete wrap after the earlier
                                            // partial sweep.
                                            for (int i = 0; i < 5 && f.rows() > 0; i++) {
                                                var result = expired.cleanup(1);
                                                removed += result.removed();
                                            }
                                            assertEquals(2, removed);
                                            assertEquals(2, f.usage());
                                            assertEquals(0, f.rows());
                                            assertTrue(
                                                    f.gate(NOW.minusSeconds(60))
                                                            .login(ip(1), address(1))
                                                            .admitted());
                                            var stored =
                                                    f.write(
                                                            () ->
                                                                    c.getBean(
                                                                                    IdentityAdmissionKeyRepository
                                                                                            .class)
                                                                            .existing(
                                                                                    AdmissionDigests
                                                                                            .id(
                                                                                                    new AdmissionDigests(
                                                                                                                    key(
                                                                                                                            7))
                                                                                                            .digest(
                                                                                                                    LOGIN_KEY
                                                                                                                            .name(),
                                                                                                                    address(
                                                                                                                                    1)
                                                                                                                            .loginKey()
                                                                                                                            .getBytes(
                                                                                                                                    java
                                                                                                                                            .nio
                                                                                                                                            .charset
                                                                                                                                            .StandardCharsets
                                                                                                                                            .US_ASCII))))
                                                                            .orElseThrow());
                                            assertEquals(
                                                    Math.floorDiv(
                                                            NOW.plusSeconds(900).getEpochSecond(),
                                                            900),
                                                    stored.window().number());
                                            assertThrows(
                                                    IllegalArgumentException.class,
                                                    () -> gate.cleanup(501));
                                        }));
    }

    @Test
    void independentContextsShareLastSubjectAttemptAndDoNotDoubleReleaseCleanup() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        a ->
                                                db.boot.run(
                                                        b -> {
                                                            var left = new Fixture(a);
                                                            var right = new Fixture(b);
                                                            left.setup(50000);
                                                            var x = left.gate(NOW);
                                                            var y = right.gate(NOW);
                                                            y.initialize();
                                                            for (int i = 0; i < 9; i++)
                                                                assertTrue(
                                                                        x.login(ip(1), address(1))
                                                                                .admitted());
                                                            try (var pool =
                                                                    Executors.newFixedThreadPool(
                                                                            2)) {
                                                                var start = new CountDownLatch(1);
                                                                var first =
                                                                        pool.submit(
                                                                                () -> {
                                                                                    start.await();
                                                                                    return x.login(
                                                                                            ip(1),
                                                                                            address(
                                                                                                    1));
                                                                                });
                                                                var second =
                                                                        pool.submit(
                                                                                () -> {
                                                                                    start.await();
                                                                                    return y.login(
                                                                                            ip(1),
                                                                                            address(
                                                                                                    1));
                                                                                });
                                                                start.countDown();
                                                                assertNotEquals(
                                                                        first.get(
                                                                                        30,
                                                                                        TimeUnit
                                                                                                .SECONDS)
                                                                                .admitted(),
                                                                        second.get(
                                                                                        30,
                                                                                        TimeUnit
                                                                                                .SECONDS)
                                                                                .admitted());
                                                                assertEquals(
                                                                        2 + left.rows(),
                                                                        right.usage());
                                                                var renewal = new CountDownLatch(1);
                                                                var request =
                                                                        pool.submit(
                                                                                () -> {
                                                                                    renewal.await();
                                                                                    return left.gate(
                                                                                                    NOW
                                                                                                            .plusSeconds(
                                                                                                                    900))
                                                                                            .login(
                                                                                                    ip(
                                                                                                            1),
                                                                                                    address(
                                                                                                            1));
                                                                                });
                                                                var cleanup =
                                                                        pool.submit(
                                                                                () -> {
                                                                                    renewal.await();
                                                                                    return right.gate(
                                                                                                    NOW
                                                                                                            .plusSeconds(
                                                                                                                    900))
                                                                                            .cleanup(
                                                                                                    500);
                                                                                });
                                                                renewal.countDown();
                                                                assertTrue(
                                                                        request.get(
                                                                                        30,
                                                                                        TimeUnit
                                                                                                .SECONDS)
                                                                                .admitted());
                                                                int raced =
                                                                        cleanup.get(
                                                                                        30,
                                                                                        TimeUnit
                                                                                                .SECONDS)
                                                                                .removed();
                                                                assertTrue(
                                                                        raced == 0 || raced == 2);
                                                                assertEquals(2, left.rows());
                                                                assertEquals(4, right.usage());
                                                                var sweep = new CountDownLatch(1);
                                                                var ca =
                                                                        pool.submit(
                                                                                () -> {
                                                                                    sweep.await();
                                                                                    return left.gate(
                                                                                                    NOW
                                                                                                            .plusSeconds(
                                                                                                                    1800))
                                                                                            .cleanup(
                                                                                                    500);
                                                                                });
                                                                var cb =
                                                                        pool.submit(
                                                                                () -> {
                                                                                    sweep.await();
                                                                                    return right.gate(
                                                                                                    NOW
                                                                                                            .plusSeconds(
                                                                                                                    1800))
                                                                                            .cleanup(
                                                                                                    500);
                                                                                });
                                                                sweep.countDown();
                                                                assertEquals(
                                                                        2,
                                                                        ca.get(30, TimeUnit.SECONDS)
                                                                                        .removed()
                                                                                + cb.get(
                                                                                                30,
                                                                                                TimeUnit
                                                                                                        .SECONDS)
                                                                                        .removed());
                                                                assertEquals(0, left.rows());
                                                                assertEquals(2, right.usage());
                                                            } catch (Exception e) {
                                                                throw new AssertionError(e);
                                                            }
                                                        })));
    }

    @Test
    void malformedLocatorsStillSpendGlobalSourceBudgetsAndGlobalDenialCreatesNoKeys()
            throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            var f = new Fixture(c);
                                            f.setup(50000);
                                            var gate = f.gate(NOW);
                                            for (int i = 0; i < 60; i++)
                                                assertEquals(
                                                        INVALID_INPUT,
                                                        gate.challenge(ip(1), "x".repeat(10000))
                                                                .outcome());
                                            assertEquals(
                                                    SOURCE_THROTTLED,
                                                    gate.challenge(
                                                                    ip(1),
                                                                    UUID.randomUUID().toString())
                                                            .outcome());
                                            assertEquals(1, f.rows());
                                            assertEquals(61, f.globalUsed());
                                            new JdbcIdentityIngress(
                                                            f.jdbc,
                                                            f.manager,
                                                            Clock.fixed(NOW, ZoneOffset.UTC))
                                                    .changeLimit(61);
                                            assertEquals(
                                                    GLOBAL_THROTTLED,
                                                    gate.login(ip(9), address(99)).outcome());
                                            assertEquals(1, f.rows());
                                            assertEquals(61, f.globalUsed());
                                            assertThrows(
                                                    IllegalTransactionStateException.class,
                                                    () ->
                                                            f.write(
                                                                    () ->
                                                                            gate.login(
                                                                                    ip(1),
                                                                                    address(1))));
                                        }));
    }

    @Test
    void failedAllocationAndCleanupRollBackAccountingButNotTheGlobalAttempt() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            var f = new Fixture(c);
                                            f.setup(50000);
                                            var gate = f.gate(NOW);
                                            String table =
                                                    "\"org.vernac.saas.identity.persistence\".\"IdentityAdmissionKey\"";
                                            try (var admin =
                                                            DriverManager.getConnection(
                                                                    db.url, db.credentials);
                                                    var sql = admin.createStatement()) {
                                                sql.execute(
                                                        "REVOKE INSERT ON "
                                                                + table
                                                                + " FROM "
                                                                + db.role);
                                                try {
                                                    var failure =
                                                            assertThrows(
                                                                    JdbcIdentityAdmission.Failure
                                                                            .class,
                                                                    () ->
                                                                            gate.login(
                                                                                    ip(1),
                                                                                    address(1)));
                                                    assertEquals(
                                                            JdbcIdentityAdmission.Code
                                                                    .STORAGE_UNAVAILABLE,
                                                            failure.code());
                                                    assertNull(failure.getCause());
                                                } finally {
                                                    sql.execute(
                                                            "GRANT INSERT ON "
                                                                    + table
                                                                    + " TO "
                                                                    + db.role);
                                                }
                                                assertEquals(0, f.rows());
                                                assertEquals(2, f.usage());
                                                assertEquals(1, f.globalUsed());
                                                assertTrue(
                                                        gate.login(ip(1), address(1)).admitted());
                                                sql.execute(
                                                        "REVOKE DELETE ON "
                                                                + table
                                                                + " FROM "
                                                                + db.role);
                                                try {
                                                    assertEquals(
                                                            JdbcIdentityAdmission.Code
                                                                    .STORAGE_UNAVAILABLE,
                                                            assertThrows(
                                                                            JdbcIdentityAdmission
                                                                                    .Failure.class,
                                                                            () ->
                                                                                    f.gate(
                                                                                                    NOW
                                                                                                            .plusSeconds(
                                                                                                                    900))
                                                                                            .cleanup(
                                                                                                    500))
                                                                    .code());
                                                } finally {
                                                    sql.execute(
                                                            "GRANT DELETE ON "
                                                                    + table
                                                                    + " TO "
                                                                    + db.role);
                                                }
                                                assertEquals(2, f.rows());
                                                assertEquals(4, f.usage());
                                                assertEquals(
                                                        2,
                                                        f.gate(NOW.plusSeconds(900))
                                                                .cleanup(500)
                                                                .removed());
                                                assertEquals(2, f.usage());
                                            } catch (Exception e) {
                                                throw new AssertionError(e);
                                            }
                                        }));
    }

    @Test
    void concurrentConfigurationInitializationAndDigestCollisionsFailSafely() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        a ->
                                                db.boot.run(
                                                        b -> {
                                                            var left = new Fixture(a);
                                                            var right = new Fixture(b);
                                                            left.write(
                                                                    () -> {
                                                                        var limits =
                                                                                new EnumMap<
                                                                                        JdbcIdentityCapacity
                                                                                                .Resource,
                                                                                        Long>(
                                                                                        JdbcIdentityCapacity
                                                                                                .defaults());
                                                                        limits.put(
                                                                                LIMITER_KEYS, 1L);
                                                                        new JdbcIdentityCapacity(
                                                                                        left.jdbc)
                                                                                .initialize(limits);
                                                                        return true;
                                                                    });
                                                            new JdbcIdentityIngress(
                                                                            left.jdbc,
                                                                            left.manager,
                                                                            Clock.fixed(
                                                                                    NOW,
                                                                                    ZoneOffset.UTC))
                                                                    .initialize(10000);
                                                            var x = left.gate(NOW);
                                                            var y = right.gate(NOW);
                                                            assertEquals(
                                                                    JdbcIdentityAdmission.Code
                                                                            .KEY_CAPACITY,
                                                                    assertThrows(
                                                                                    JdbcIdentityAdmission
                                                                                            .Failure
                                                                                            .class,
                                                                                    x::initialize)
                                                                            .code());
                                                            assertEquals(1, left.usage());
                                                            left.write(
                                                                    () -> {
                                                                        new JdbcIdentityCapacity(
                                                                                        left.jdbc)
                                                                                .changeLimit(
                                                                                        LIMITER_KEYS,
                                                                                        50000);
                                                                        return true;
                                                                    });
                                                            try (var pool =
                                                                    Executors.newFixedThreadPool(
                                                                            2)) {
                                                                var start = new CountDownLatch(1);
                                                                var first =
                                                                        pool.submit(
                                                                                () -> {
                                                                                    start.await();
                                                                                    x.initialize();
                                                                                    return true;
                                                                                });
                                                                var second =
                                                                        pool.submit(
                                                                                () -> {
                                                                                    start.await();
                                                                                    y.initialize();
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
                                                                assertEquals(2, left.usage());
                                                            } catch (Exception e) {
                                                                throw new AssertionError(e);
                                                            }
                                                            var expected =
                                                                    new AdmissionDigests(key(7))
                                                                            .digest(
                                                                                    LOGIN_SOURCE
                                                                                            .name(),
                                                                                    ip(1).getAddress());
                                                            String text = expected.encoded();
                                                            var collision =
                                                                    IdentityAdmissionDigest.of(
                                                                            text.substring(0, 32)
                                                                                    + (text.charAt(
                                                                                                            32)
                                                                                                    == 'a'
                                                                                            ? "b"
                                                                                            : "a")
                                                                                    + text
                                                                                            .substring(
                                                                                                    33));
                                                            assertEquals(
                                                                    AdmissionDigests.id(expected),
                                                                    AdmissionDigests.id(collision));
                                                            left.write(
                                                                    () -> {
                                                                        assertTrue(
                                                                                new JdbcIdentityCapacity(
                                                                                                left.jdbc)
                                                                                        .reserve(
                                                                                                Map
                                                                                                        .of(
                                                                                                                LIMITER_KEYS,
                                                                                                                1L)));
                                                                        var row =
                                                                                IdentityAdmissionKey
                                                                                        .reconstitute(
                                                                                                AdmissionDigests
                                                                                                        .id(
                                                                                                                expected),
                                                                                                LOGIN_SOURCE,
                                                                                                collision,
                                                                                                IdentityAdmissionWindow
                                                                                                        .of(
                                                                                                                Math
                                                                                                                        .floorDiv(
                                                                                                                                NOW
                                                                                                                                        .getEpochSecond(),
                                                                                                                                900)),
                                                                                                IdentityAdmissionCount
                                                                                                        .of(
                                                                                                                0),
                                                                                                IdentityAdmissionSecond
                                                                                                        .of(
                                                                                                                NOW.plusSeconds(
                                                                                                                                900)
                                                                                                                        .getEpochSecond()),
                                                                                                NOW,
                                                                                                NOW,
                                                                                                0);
                                                                        return a.getBean(
                                                                                        IdentityAdmissionKeyRepository
                                                                                                .class)
                                                                                .save(row);
                                                                    });
                                                            var rejected =
                                                                    assertThrows(
                                                                            JdbcIdentityAdmission
                                                                                    .Failure.class,
                                                                            () ->
                                                                                    x.login(
                                                                                            ip(1),
                                                                                            address(
                                                                                                    1)));
                                                            assertEquals(
                                                                    JdbcIdentityAdmission.Code
                                                                            .KEY_COLLISION,
                                                                    rejected.code());
                                                            assertNull(rejected.getCause());
                                                            assertFalse(
                                                                    rejected.toString()
                                                                            .contains(text));
                                                            assertEquals(1, left.rows());
                                                            assertEquals(3, right.usage());
                                                            assertEquals(1, left.globalUsed());
                                                        })));
    }
}
