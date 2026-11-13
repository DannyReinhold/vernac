// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.vernac.saas.identity.persistence.JdbcIdentityCapacity.Resource.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.vernac.saas.identity.domain.*;
import org.vernac.saas.identity.persistence.domain.*;
import org.vernac.saas.identity.security.credentials.*;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@EnabledIfEnvironmentVariable(named = "VERNAC_PG_TEST_URL", matches = ".+")
class IdentityRegistrationsPostgresIT {
    @TempDir Path root;
    static final Instant NOW = Instant.parse("2026-11-13T09:00:00.123456789Z");
    static final Instant DEADLINE = NOW.plus(Duration.ofDays(7));
    static final String ENCODED =
            new CredentialPasswords(OfflinePasswordBlocklist.bundled())
                    .encode("independent pending registration password 391756");

    static final class Fixture {
        final NamedParameterJdbcTemplate jdbc;
        final PlatformTransactionManager manager;
        final IdentityRepository identities;
        final JdbcIdentityCapacity capacity;

        Fixture(AssertableApplicationContext c) {
            assertNull(c.getStartupFailure());
            jdbc = c.getBean(NamedParameterJdbcTemplate.class);
            manager = c.getBean(PlatformTransactionManager.class);
            identities = c.getBean(IdentityRepository.class);
            capacity = new JdbcIdentityCapacity(jdbc);
        }

        <T> T write(Supplier<T> work) {
            return new TransactionTemplate(manager).execute(s -> work.get());
        }

        JdbcIdentityRegistrations registrations(Instant now) {
            return new JdbcIdentityRegistrations(jdbc, Clock.fixed(now, ZoneOffset.UTC));
        }

        JdbcIdentityChallenges challenges(Instant now) {
            return new JdbcIdentityChallenges(jdbc, Clock.fixed(now, ZoneOffset.UTC));
        }

        void setup(long slots) {
            write(
                    () -> {
                        var limits = new EnumMap<>(JdbcIdentityCapacity.defaults());
                        limits.put(PENDING_REGISTRATIONS, slots);
                        capacity.initialize(limits);
                        return null;
                    });
        }

        long used(JdbcIdentityCapacity.Resource resource) {
            return write(() -> capacity.usage(resource));
        }

        long count(String table) {
            return jdbc.queryForObject(
                    "SELECT count(*) FROM \"org.vernac.saas.identity.persistence\".\""
                            + table
                            + "\"",
                    Map.of(),
                    Long.class);
        }
    }

    @Test
    void deadlineIsImmutableExactAndSurvivesRestart() throws Exception {
        var subject = new AtomicReference<UserId>();
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db -> {
                            db.boot.run(
                                    c -> {
                                        var f = new Fixture(c);
                                        f.setup(2);
                                        subject.set(f.write(() -> f.registrations(NOW).create()));
                                        assertTrue(
                                                f.write(
                                                        () ->
                                                                f.registrations(
                                                                                DEADLINE.minusNanos(
                                                                                        1))
                                                                        .pending(subject.get())));
                                        assertFalse(
                                                f.write(
                                                        () ->
                                                                f.registrations(DEADLINE)
                                                                        .pending(subject.get())));
                                        assertFalse(
                                                f.write(
                                                        () ->
                                                                f.registrations(NOW)
                                                                        .pending(UserId.create())));
                                        var row =
                                                f.write(
                                                        () ->
                                                                c.getBean(
                                                                                IdentityPendingRegistrationRepository
                                                                                        .class)
                                                                        .byId(subject.get()));
                                        assertEquals(NOW, row.createdAt());
                                        assertEquals(DEADLINE, row.deadline().instant());
                                        assertEquals(
                                                1, f.used(PENDING_REGISTRATIONS)); // time does not
                                        // refund physical
                                        // rows
                                        assertEquals(0, f.count("IdentityCredential"));
                                        assertEquals(0, f.count("IdentityChallenge"));
                                        assertThrows(
                                                IllegalTransactionStateException.class,
                                                () -> f.registrations(NOW).create());
                                        var read = new TransactionTemplate(f.manager);
                                        read.setReadOnly(true);
                                        assertThrows(
                                                IllegalTransactionStateException.class,
                                                () ->
                                                        read.execute(
                                                                s ->
                                                                        f.registrations(NOW)
                                                                                .create()));
                                    });
                            db.boot.run(
                                    c -> {
                                        var f = new Fixture(c);
                                        f.setup(2);
                                        assertFalse(
                                                f.write(
                                                        () ->
                                                                f.registrations(DEADLINE)
                                                                        .pending(subject.get())));
                                        assertFalse(
                                                f.write(
                                                        () ->
                                                                f.registrations(DEADLINE)
                                                                        .complete(
                                                                                subject.get(),
                                                                                ENCODED,
                                                                                id ->
                                                                                        fail(
                                                                                                "expired"
                                                                                                    + " provisioning"))));
                                        assertEquals(1, f.used(PENDING_REGISTRATIONS));
                                        assertEquals(7, uncheckedMigrations(db));
                                    });
                        });
    }

    @Test
    void concurrentLastSlotAndRollbackNeverLeakPendingIdentities() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        a ->
                                                db.boot.run(
                                                        b -> {
                                                            var first = new Fixture(a);
                                                            var second = new Fixture(b);
                                                            first.setup(1);
                                                            var start = new CountDownLatch(1);
                                                            try (var pool =
                                                                    Executors.newFixedThreadPool(
                                                                            2)) {
                                                                Callable<Boolean> one =
                                                                        () -> {
                                                                            start.await();
                                                                            return allocate(first);
                                                                        };
                                                                Callable<Boolean> two =
                                                                        () -> {
                                                                            start.await();
                                                                            return allocate(second);
                                                                        };
                                                                var x = pool.submit(one);
                                                                var y = pool.submit(two);
                                                                start.countDown();
                                                                assertEquals(
                                                                        1,
                                                                        (x.get(20, TimeUnit.SECONDS)
                                                                                        ? 1
                                                                                        : 0)
                                                                                + (y.get(
                                                                                                20,
                                                                                                TimeUnit
                                                                                                        .SECONDS)
                                                                                        ? 1
                                                                                        : 0));
                                                            } catch (Exception e) {
                                                                throw new AssertionError(e);
                                                            }
                                                            assertEquals(
                                                                    1,
                                                                    first.used(
                                                                            PENDING_REGISTRATIONS));
                                                            assertEquals(
                                                                    1, first.count("Identity"));
                                                            assertEquals(
                                                                    1,
                                                                    first.count(
                                                                            "IdentityPendingRegistration"));
                                                            first.write(
                                                                    () -> {
                                                                        first.capacity.changeLimit(
                                                                                PENDING_REGISTRATIONS,
                                                                                2);
                                                                        return null;
                                                                    });
                                                            new TransactionTemplate(first.manager)
                                                                    .executeWithoutResult(
                                                                            s -> {
                                                                                first.registrations(
                                                                                                NOW)
                                                                                        .create();
                                                                                s.setRollbackOnly();
                                                                            });
                                                            assertEquals(
                                                                    1,
                                                                    second.used(
                                                                            PENDING_REGISTRATIONS));
                                                            assertEquals(
                                                                    1, second.count("Identity"));
                                                        })));
    }

    @Test
    void challengeCompletionProvisioningAndCapacityAreOneRollbackBoundary() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            var f = new Fixture(c);
                                            f.setup(2);
                                            var id = f.write(() -> f.registrations(NOW).create());
                                            var issue =
                                                    f.write(
                                                            () ->
                                                                    f.challenges(NOW)
                                                                            .issue(
                                                                                    id,
                                                                                    IdentityChallengePurpose
                                                                                            .VERIFICATION));
                                            String token = issue.token().deliveryValue();
                                            var failed = new TransactionTemplate(f.manager);
                                            // Catching the callback error inside the transaction
                                            // must still roll everything back.
                                            assertThrows(
                                                    UnexpectedRollbackException.class,
                                                    () ->
                                                            failed.executeWithoutResult(
                                                                    s ->
                                                                            assertThrows(
                                                                                    IllegalStateException
                                                                                            .class,
                                                                                    () ->
                                                                                            f.challenges(
                                                                                                            NOW)
                                                                                                    .consume(
                                                                                                            token,
                                                                                                            IdentityChallengePurpose
                                                                                                                    .VERIFICATION,
                                                                                                            subject -> {
                                                                                                                assertTrue(
                                                                                                                        f.registrations(
                                                                                                                                        NOW)
                                                                                                                                .complete(
                                                                                                                                        subject,
                                                                                                                                        ENCODED,
                                                                                                                                        ignored -> {
                                                                                                                                            throw new IllegalStateException(
                                                                                                                                                    "provisioning"
                                                                                                                                                        + " failed");
                                                                                                                                        }));
                                                                                                            }))));
                                            assertEquals(
                                                    IdentityStatus.PENDING_VERIFICATION,
                                                    f.write(() -> f.identities.byId(id).status()));
                                            assertEquals(1, f.used(PENDING_REGISTRATIONS));
                                            assertEquals(1, f.used(ACTIONABLE_CHALLENGES));
                                            assertEquals(0, f.count("IdentityCredential"));
                                            assertTrue(
                                                    f.write(
                                                            () ->
                                                                    f.challenges(NOW)
                                                                            .consume(
                                                                                    token,
                                                                                    IdentityChallengePurpose
                                                                                            .VERIFICATION,
                                                                                    subject -> {
                                                                                        assertTrue(
                                                                                                f.registrations(
                                                                                                                NOW)
                                                                                                        .complete(
                                                                                                                subject,
                                                                                                                ENCODED,
                                                                                                                owner ->
                                                                                                                        assertEquals(
                                                                                                                                id,
                                                                                                                                owner)));
                                                                                    })));
                                            assertEquals(
                                                    IdentityStatus.ACTIVE,
                                                    f.write(() -> f.identities.byId(id).status()));
                                            assertEquals(0, f.used(PENDING_REGISTRATIONS));
                                            assertEquals(0, f.used(ACTIONABLE_CHALLENGES));
                                            assertEquals(1, f.count("IdentityCredential"));
                                            assertFalse(
                                                    f.write(
                                                            () ->
                                                                    f.registrations(NOW)
                                                                            .complete(
                                                                                    id,
                                                                                    ENCODED,
                                                                                    ignored ->
                                                                                            fail())));
                                            assertFalse(
                                                    f.write(
                                                            () ->
                                                                    f.challenges(NOW)
                                                                            .consume(
                                                                                    token,
                                                                                    IdentityChallengePurpose
                                                                                            .VERIFICATION,
                                                                                    ignored ->
                                                                                            fail())));
                                            assertEquals(
                                                    0,
                                                    f.write(
                                                                    () ->
                                                                            f.registrations(
                                                                                            DEADLINE)
                                                                                    .sweep(
                                                                                            null,
                                                                                            10))
                                                            .removed());
                                            assertEquals(1, f.count("Identity"));
                                        }));
    }

    @Test
    void indexedSweepIsBoundedAndRefundsActualDependenciesOnlyOnce() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            var f = new Fixture(c);
                                            f.setup(10);
                                            var ids = new ArrayList<UserId>();
                                            for (int i = 0; i < 5; i++)
                                                ids.add(
                                                        f.write(
                                                                () ->
                                                                        f.registrations(NOW)
                                                                                .create()));
                                            var live =
                                                    f.write(
                                                            () ->
                                                                    f.registrations(
                                                                                    NOW.plusSeconds(
                                                                                            60))
                                                                            .create());
                                            for (var id : ids)
                                                f.write(
                                                        () ->
                                                                f.challenges(NOW)
                                                                        .issue(
                                                                                id,
                                                                                IdentityChallengePurpose
                                                                                        .VERIFICATION));
                                            f.write(
                                                    () ->
                                                            new JdbcIdentityCredentials(f.jdbc)
                                                                    .enroll(
                                                                            ids.getFirst(),
                                                                            ENCODED));
                                            assertEquals(6, f.used(PENDING_REGISTRATIONS));
                                            assertEquals(5, f.used(ACTIONABLE_CHALLENGES));
                                            UUID cursor = null;
                                            int inspected = 0, removed = 0;
                                            for (int batches = 0; batches < 4; batches++) {
                                                UUID after = cursor;
                                                var result =
                                                        f.write(
                                                                () ->
                                                                        f.registrations(DEADLINE)
                                                                                .sweep(after, 2));
                                                assertTrue(result.inspected() <= 2);
                                                inspected += result.inspected();
                                                removed += result.removed();
                                                cursor = result.after();
                                                if (cursor == null) break;
                                            }
                                            assertNull(cursor);
                                            assertEquals(6, inspected);
                                            assertEquals(5, removed);
                                            assertEquals(1, f.used(PENDING_REGISTRATIONS));
                                            assertEquals(0, f.used(ACTIONABLE_CHALLENGES));
                                            assertEquals(0, f.count("IdentityCredential"));
                                            assertEquals(1, f.count("Identity"));
                                            assertTrue(
                                                    f.write(
                                                            () ->
                                                                    f.registrations(DEADLINE)
                                                                            .pending(live)));
                                            assertEquals(
                                                    0,
                                                    f.write(
                                                                    () ->
                                                                            f.registrations(
                                                                                            DEADLINE)
                                                                                    .sweep(
                                                                                            null,
                                                                                            500))
                                                            .removed());
                                            assertThrows(
                                                    IllegalArgumentException.class,
                                                    () -> f.registrations(NOW).sweep(null, 501));
                                        }));
    }

    @Test
    void cleanupAndActivationAcrossContextsHaveExactlyOneWinner() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        a ->
                                                db.boot.run(
                                                        b -> {
                                                            var first = new Fixture(a);
                                                            var second = new Fixture(b);
                                                            first.setup(10);
                                                            for (int iteration = 0;
                                                                    iteration < 4;
                                                                    iteration++) {
                                                                var id =
                                                                        first.write(
                                                                                () ->
                                                                                        first.registrations(
                                                                                                        NOW)
                                                                                                .create());
                                                                var start = new CountDownLatch(1);
                                                                try (var pool =
                                                                        Executors
                                                                                .newFixedThreadPool(
                                                                                        2)) {
                                                                    var activation =
                                                                            pool.submit(
                                                                                    () -> {
                                                                                        start
                                                                                                .await();
                                                                                        return first
                                                                                                .write(
                                                                                                        () ->
                                                                                                                first.registrations(
                                                                                                                                DEADLINE
                                                                                                                                        .minusNanos(
                                                                                                                                                1))
                                                                                                                        .complete(
                                                                                                                                id,
                                                                                                                                ENCODED,
                                                                                                                                ignored -> {}));
                                                                                    });
                                                                    var cleanup =
                                                                            pool.submit(
                                                                                    () -> {
                                                                                        start
                                                                                                .await();
                                                                                        return second.write(
                                                                                                        () ->
                                                                                                                second.registrations(
                                                                                                                                DEADLINE)
                                                                                                                        .sweep(
                                                                                                                                null,
                                                                                                                                500))
                                                                                                .removed();
                                                                                    });
                                                                    start.countDown();
                                                                    boolean active =
                                                                            activation.get(
                                                                                    20,
                                                                                    TimeUnit
                                                                                            .SECONDS);
                                                                    int deleted =
                                                                            cleanup.get(
                                                                                    20,
                                                                                    TimeUnit
                                                                                            .SECONDS);
                                                                    assertEquals(
                                                                            active ? 0 : 1,
                                                                            deleted);
                                                                    assertEquals(
                                                                            active,
                                                                            first.write(
                                                                                            () ->
                                                                                                    first
                                                                                                            .identities
                                                                                                            .existing(
                                                                                                                    id))
                                                                                    .isPresent());
                                                                    if (active)
                                                                        assertEquals(
                                                                                IdentityStatus
                                                                                        .ACTIVE,
                                                                                first.write(
                                                                                        () ->
                                                                                                first.identities
                                                                                                        .byId(
                                                                                                                id)
                                                                                                        .status()));
                                                                } catch (Exception e) {
                                                                    throw new AssertionError(e);
                                                                }
                                                                assertEquals(
                                                                        0,
                                                                        first.used(
                                                                                PENDING_REGISTRATIONS));
                                                            }
                                                        })));
    }

    @Test
    void cleanupFailureAndInconsistentEligibilityFailClosedWithoutDeletingActiveState()
            throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            var f = new Fixture(c);
                                            f.setup(2);
                                            var id = f.write(() -> f.registrations(NOW).create());
                                            f.write(
                                                    () ->
                                                            f.challenges(NOW)
                                                                    .issue(
                                                                            id,
                                                                            IdentityChallengePurpose
                                                                                    .VERIFICATION));
                                            try (var connection =
                                                            DriverManager.getConnection(
                                                                    db.url, db.credentials);
                                                    var sql = connection.createStatement()) {
                                                sql.execute(
                                                        "REVOKE DELETE ON"
                                                            + " \"org.vernac.saas.identity.persistence\".\"Identity\""
                                                            + " FROM "
                                                                + db.role);
                                                var failure =
                                                        assertThrows(
                                                                JdbcIdentityRegistrations.Failure
                                                                        .class,
                                                                () ->
                                                                        f.write(
                                                                                () ->
                                                                                        f.registrations(
                                                                                                        DEADLINE)
                                                                                                .sweep(
                                                                                                        null,
                                                                                                        500)));
                                                assertEquals(
                                                        JdbcIdentityRegistrations.Code
                                                                .STORAGE_UNAVAILABLE,
                                                        failure.code());
                                                assertNull(failure.getCause());
                                                assertEquals(
                                                        1, f.count("IdentityPendingRegistration"));
                                                assertEquals(1, f.count("IdentityChallenge"));
                                                assertEquals(1, f.used(ACTIONABLE_CHALLENGES));
                                                assertEquals(1, f.used(PENDING_REGISTRATIONS));
                                                sql.execute(
                                                        "GRANT DELETE ON"
                                                            + " \"org.vernac.saas.identity.persistence\".\"Identity\""
                                                            + " TO "
                                                                + db.role);
                                            } catch (Exception e) {
                                                throw new AssertionError(e);
                                            }
                                            // A bypass of the controlled lifecycle is detected,
                                            // never repaired by deleting a user.
                                            f.write(
                                                    () -> {
                                                        var identity = f.identities.byId(id);
                                                        identity.activate();
                                                        f.identities.save(identity);
                                                        return null;
                                                    });
                                            var failure =
                                                    assertThrows(
                                                            JdbcIdentityRegistrations.Failure.class,
                                                            () ->
                                                                    f.write(
                                                                            () ->
                                                                                    f.registrations(
                                                                                                    DEADLINE)
                                                                                            .sweep(
                                                                                                    null,
                                                                                                    500)));
                                            assertEquals(
                                                    JdbcIdentityRegistrations.Code
                                                            .INCONSISTENT_STATE,
                                                    failure.code());
                                            assertEquals(
                                                    IdentityStatus.ACTIVE,
                                                    f.write(() -> f.identities.byId(id).status()));
                                            assertEquals(1, f.count("IdentityPendingRegistration"));
                                            assertEquals(1, f.used(PENDING_REGISTRATIONS));
                                        }));
    }

    @Test
    void twoCleanersReleaseOnlyTheWinnerAndCompletionFailureRestoresAllocation() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        a ->
                                                db.boot.run(
                                                        b -> {
                                                            var first = new Fixture(a);
                                                            var second = new Fixture(b);
                                                            first.setup(1);
                                                            var id =
                                                                    first.write(
                                                                            () ->
                                                                                    first.registrations(
                                                                                                    NOW)
                                                                                            .create());
                                                            assertThrows(
                                                                    JdbcIdentityCredentials.Failure
                                                                            .class,
                                                                    () ->
                                                                            first.write(
                                                                                    () ->
                                                                                            first.registrations(
                                                                                                            NOW)
                                                                                                    .complete(
                                                                                                            id,
                                                                                                            "{noop}invalid",
                                                                                                            ignored ->
                                                                                                                    fail())));
                                                            assertTrue(
                                                                    first.write(
                                                                            () ->
                                                                                    first.registrations(
                                                                                                    NOW)
                                                                                            .pending(
                                                                                                    id)));
                                                            assertEquals(
                                                                    0,
                                                                    first.count(
                                                                            "IdentityCredential"));
                                                            var start = new CountDownLatch(1);
                                                            try (var pool =
                                                                    Executors.newFixedThreadPool(
                                                                            2)) {
                                                                var one =
                                                                        pool.submit(
                                                                                () -> {
                                                                                    start.await();
                                                                                    return first.write(
                                                                                                    () ->
                                                                                                            first.registrations(
                                                                                                                            DEADLINE)
                                                                                                                    .sweep(
                                                                                                                            null,
                                                                                                                            500))
                                                                                            .removed();
                                                                                });
                                                                var two =
                                                                        pool.submit(
                                                                                () -> {
                                                                                    start.await();
                                                                                    return second.write(
                                                                                                    () ->
                                                                                                            second.registrations(
                                                                                                                            DEADLINE)
                                                                                                                    .sweep(
                                                                                                                            null,
                                                                                                                            500))
                                                                                            .removed();
                                                                                });
                                                                start.countDown();
                                                                assertEquals(
                                                                        1,
                                                                        one.get(
                                                                                        20,
                                                                                        TimeUnit
                                                                                                .SECONDS)
                                                                                + two.get(
                                                                                        20,
                                                                                        TimeUnit
                                                                                                .SECONDS));
                                                            } catch (Exception failure) {
                                                                throw new AssertionError(failure);
                                                            }
                                                            assertEquals(
                                                                    0,
                                                                    first.used(
                                                                            PENDING_REGISTRATIONS));
                                                            assertEquals(
                                                                    0, first.count("Identity"));
                                                            assertEquals(
                                                                    0,
                                                                    first.count(
                                                                            "IdentityPendingRegistration"));
                                                            var fresh =
                                                                    first.write(
                                                                            () ->
                                                                                    first.registrations(
                                                                                                    DEADLINE)
                                                                                            .create());
                                                            assertNotEquals(id, fresh);
                                                            assertFalse(
                                                                    first.write(
                                                                            () ->
                                                                                    first.registrations(
                                                                                                    DEADLINE)
                                                                                            .pending(
                                                                                                    id)));
                                                        })));
    }

    static boolean allocate(Fixture f) {
        try {
            f.write(() -> f.registrations(NOW).create());
            return true;
        } catch (JdbcIdentityRegistrations.Failure failure) {
            assertEquals(JdbcIdentityRegistrations.Code.CAPACITY, failure.code());
            return false;
        }
    }

    static long uncheckedMigrations(IdentityJdbcTestEnvironment.Database db) {
        try {
            return db.migrationCount();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
