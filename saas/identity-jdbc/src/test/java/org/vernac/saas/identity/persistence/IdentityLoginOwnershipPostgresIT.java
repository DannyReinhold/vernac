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
import org.vernac.runtime.persistence.UniqueConstraintViolationException;
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
class IdentityLoginOwnershipPostgresIT {
    @TempDir Path root;
    static final Instant NOW = Instant.parse("2026-11-13T12:00:00.123456789Z");
    static final Instant DEADLINE = NOW.plus(Duration.ofDays(7));
    static final String ORIGINAL = "Alice+Reports@Example.COM", KEY = "alice+reports@example.com";
    static final String ENCODED =
            new CredentialPasswords(OfflinePasswordBlocklist.bundled())
                    .encode("independent ownership proof password phrase 792546");

    static final class Fixture {
        final NamedParameterJdbcTemplate jdbc;
        final PlatformTransactionManager manager;
        final IdentityRepository identities;
        final IdentityPendingRegistrationRepository pending;
        final IdentityLoginAddressRepository addresses;
        final JdbcIdentityCapacity capacity;

        Fixture(AssertableApplicationContext c) {
            assertNull(c.getStartupFailure());
            jdbc = c.getBean(NamedParameterJdbcTemplate.class);
            manager = c.getBean(PlatformTransactionManager.class);
            identities = c.getBean(IdentityRepository.class);
            pending = c.getBean(IdentityPendingRegistrationRepository.class);
            addresses = c.getBean(IdentityLoginAddressRepository.class);
            capacity = new JdbcIdentityCapacity(jdbc);
        }

        <T> T write(Supplier<T> work) {
            return new TransactionTemplate(manager).execute(s -> work.get());
        }

        JdbcIdentityRegistrations registrations(Instant now) {
            return new JdbcIdentityRegistrations(jdbc, Clock.fixed(now, ZoneOffset.UTC));
        }

        JdbcIdentityRegistrations.Request request(String address, Instant now) {
            return write(() -> registrations(now).request(LoginAddress.parse(address)));
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

        IdentityLoginAddress owner(String key) {
            return write(() -> addresses.forKey(IdentityLoginKey.of(key)).orElseThrow());
        }

        boolean activate(UserId subject) {
            return write(() -> registrations(NOW).complete(subject, ENCODED, ignored -> {}));
        }
    }

    @Test
    void canonicalRepeatsPreserveOriginalSpellingDeadlineAndOneOwnerWithoutAliasFolding()
            throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            var f = new Fixture(c);
                                            f.setup(10);
                                            var first = f.request(" " + ORIGINAL + " ", NOW);
                                            assertEquals(
                                                    JdbcIdentityRegistrations.Outcome.CREATED,
                                                    first.outcome());
                                            var repeated = f.request(KEY, NOW.plusSeconds(120));
                                            assertEquals(
                                                    JdbcIdentityRegistrations.Outcome.PENDING,
                                                    repeated.outcome());
                                            assertEquals(first.subject(), repeated.subject());
                                            assertEquals(ORIGINAL, repeated.deliveryAddress());
                                            assertEquals(ORIGINAL, first.deliveryAddress());
                                            var row = f.owner(KEY);
                                            assertEquals(NOW, row.createdAt());
                                            assertEquals(1, row.persistenceState().version());
                                            assertEquals(
                                                    DEADLINE,
                                                    f.write(
                                                            () ->
                                                                    f.pending
                                                                            .byId(first.subject())
                                                                            .deadline()
                                                                            .instant()));
                                            assertFalse(repeated.toString().contains(ORIGINAL));
                                            assertFalse(row.toString().contains(KEY));
                                            var distinct = new HashSet<UserId>();
                                            distinct.add(first.subject());
                                            for (String alias :
                                                    List.of(
                                                            "alice@example.com",
                                                            "a.lice+reports@example.com",
                                                            "alice+other@example.com"))
                                                distinct.add(f.request(alias, NOW).subject());
                                            assertEquals(4, distinct.size());
                                            assertEquals(4, f.used(PENDING_REGISTRATIONS));
                                            for (String table :
                                                    List.of(
                                                            "Identity",
                                                            "IdentityPendingRegistration",
                                                            "IdentityLoginAddress"))
                                                assertEquals(4, f.count(table));
                                            assertEquals(0, f.count("IdentityChallenge"));
                                            assertEquals(0, f.count("IdentityCredential"));
                                        }));
    }

    @Test
    void independentContextsHaveOneCreatorAndRetainOwnershipAcrossRestart() throws Exception {
        var subject = new AtomicReference<UserId>();
        var spelling = new AtomicReference<String>();
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db -> {
                            db.boot.run(
                                    a ->
                                            db.boot.run(
                                                    b -> {
                                                        var first = new Fixture(a);
                                                        var second = new Fixture(b);
                                                        first.setup(2);
                                                        var start = new CountDownLatch(1);
                                                        try (var pool =
                                                                Executors.newFixedThreadPool(2)) {
                                                            var one =
                                                                    pool.submit(
                                                                            () -> {
                                                                                start.await();
                                                                                return first
                                                                                        .request(
                                                                                                ORIGINAL,
                                                                                                NOW);
                                                                            });
                                                            var two =
                                                                    pool.submit(
                                                                            () -> {
                                                                                start.await();
                                                                                return second
                                                                                        .request(
                                                                                                KEY,
                                                                                                NOW);
                                                                            });
                                                            start.countDown();
                                                            var x = one.get(20, TimeUnit.SECONDS);
                                                            var y = two.get(20, TimeUnit.SECONDS);
                                                            assertEquals(
                                                                    1,
                                                                    (x.outcome()
                                                                                            == JdbcIdentityRegistrations
                                                                                                    .Outcome
                                                                                                    .CREATED
                                                                                    ? 1
                                                                                    : 0)
                                                                            + (y.outcome()
                                                                                            == JdbcIdentityRegistrations
                                                                                                    .Outcome
                                                                                                    .CREATED
                                                                                    ? 1
                                                                                    : 0));
                                                            assertEquals(x.subject(), y.subject());
                                                            assertEquals(
                                                                    x.deliveryAddress(),
                                                                    y.deliveryAddress());
                                                            subject.set(x.subject());
                                                            spelling.set(x.deliveryAddress());
                                                        } catch (Exception failure) {
                                                            throw new AssertionError(failure);
                                                        }
                                                        assertEquals(
                                                                1,
                                                                first.used(PENDING_REGISTRATIONS));
                                                        assertEquals(
                                                                1,
                                                                second.count(
                                                                        "IdentityLoginAddress"));
                                                    }));
                            db.boot.run(
                                    c -> {
                                        var f = new Fixture(c);
                                        f.setup(2);
                                        var repeated = f.request(KEY, NOW.plusSeconds(60));
                                        assertEquals(subject.get(), repeated.subject());
                                        assertEquals(spelling.get(), repeated.deliveryAddress());
                                        assertEquals(1, f.used(PENDING_REGISTRATIONS));
                                    });
                        });
    }

    @Test
    void capacityPressureIsUniformBeforeLookupAndRollbackLeavesNoIdentityOrClaim()
            throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            var f = new Fixture(c);
                                            f.setup(1);
                                            var first = f.request(ORIGINAL, NOW);
                                            for (String input : List.of(KEY, "unknown@example.com"))
                                                assertCapacity(f, input);
                                            assertEquals(1, f.count("Identity"));
                                            assertTrue(f.activate(first.subject()));
                                            f.request("filler@example.com", NOW);
                                            for (String input :
                                                    List.of(
                                                            KEY,
                                                            "unknown@example.com",
                                                            "filler@example.com"))
                                                assertCapacity(f, input);
                                            f.write(
                                                    () -> {
                                                        f.capacity.changeLimit(
                                                                PENDING_REGISTRATIONS, 2);
                                                        return null;
                                                    });
                                            new TransactionTemplate(f.manager)
                                                    .executeWithoutResult(
                                                            status -> {
                                                                f.registrations(NOW)
                                                                        .request(
                                                                                LoginAddress.parse(
                                                                                        "rollback@example.com"));
                                                                status.setRollbackOnly();
                                                            });
                                            assertEquals(2, f.count("Identity"));
                                            assertEquals(2, f.count("IdentityLoginAddress"));
                                            assertEquals(1, f.used(PENDING_REGISTRATIONS));
                                            assertTrue(
                                                    f.write(
                                                                    () ->
                                                                            f.addresses.forKey(
                                                                                    IdentityLoginKey
                                                                                            .of(
                                                                                                    "rollback@example.com")))
                                                            .isEmpty());
                                        }));
    }

    static void assertCapacity(Fixture f, String address) {
        var failure =
                assertThrows(
                        JdbcIdentityRegistrations.Failure.class, () -> f.request(address, NOW));
        assertEquals(JdbcIdentityRegistrations.Code.CAPACITY, failure.code());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains(address));
    }

    @Test
    void activationDisablingAndClosureKeepTheOriginalKeyAndCannotIssueAnotherRegistration()
            throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            var f = new Fixture(c);
                                            f.setup(3);
                                            var first = f.request(ORIGINAL, NOW);
                                            assertTrue(f.activate(first.subject()));
                                            for (int state = 0; state < 3; state++) {
                                                var request = f.request(KEY, NOW.plusSeconds(60));
                                                assertEquals(
                                                        JdbcIdentityRegistrations.Outcome
                                                                .UNAVAILABLE,
                                                        request.outcome());
                                                assertThrows(
                                                        IllegalStateException.class,
                                                        request::subject);
                                                assertThrows(
                                                        IllegalStateException.class,
                                                        request::deliveryAddress);
                                                assertEquals(first.subject(), f.owner(KEY).id());
                                                assertEquals(
                                                        ORIGINAL,
                                                        f.owner(KEY).deliveryAddress().encoded());
                                                if (state == 0)
                                                    f.write(
                                                            () -> {
                                                                var identity =
                                                                        f.identities.byId(
                                                                                first.subject());
                                                                identity.disable();
                                                                f.identities.save(identity);
                                                                return null;
                                                            });
                                                if (state == 1)
                                                    f.write(
                                                            () -> {
                                                                var identity =
                                                                        f.identities.byId(
                                                                                first.subject());
                                                                identity.close();
                                                                f.identities.save(identity);
                                                                return null;
                                                            });
                                            }
                                            assertEquals(0, f.used(PENDING_REGISTRATIONS));
                                            assertEquals(1, f.count("Identity"));
                                            assertEquals(1, f.count("IdentityCredential"));
                                            assertEquals(1, f.count("IdentityLoginAddress"));
                                            assertEquals(0, f.count("IdentityChallenge"));
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
                                        }));
    }

    @Test
    void abandonedClaimIsReleasedAtomicallyAndOldLiveTokenCannotFollowTheNewOwner()
            throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            var f = new Fixture(c);
                                            f.setup(3);
                                            var old = f.request(ORIGINAL, NOW);
                                            var token =
                                                    f.write(
                                                                    () ->
                                                                            f.challenges(
                                                                                            DEADLINE
                                                                                                    .minusSeconds(
                                                                                                            3600))
                                                                                    .issue(
                                                                                            old
                                                                                                    .subject(),
                                                                                            IdentityChallengePurpose
                                                                                                    .VERIFICATION))
                                                            .token()
                                                            .deliveryValue();
                                            assertEquals(
                                                    JdbcIdentityRegistrations.Outcome.UNAVAILABLE,
                                                    f.request(KEY, DEADLINE).outcome());
                                            assertEquals(1, f.used(PENDING_REGISTRATIONS));
                                            assertEquals(1, f.used(ACTIONABLE_CHALLENGES));
                                            assertEquals(
                                                    1,
                                                    f.write(
                                                                    () ->
                                                                            f.registrations(
                                                                                            DEADLINE)
                                                                                    .sweep(
                                                                                            null,
                                                                                            500))
                                                            .removed());
                                            assertEquals(0, f.used(PENDING_REGISTRATIONS));
                                            assertEquals(0, f.used(ACTIONABLE_CHALLENGES));
                                            assertEquals(0, f.count("IdentityLoginAddress"));
                                            var fresh = f.request(KEY, DEADLINE);
                                            assertNotEquals(old.subject(), fresh.subject());
                                            assertEquals(
                                                    JdbcIdentityRegistrations.Outcome.CREATED,
                                                    fresh.outcome());
                                            assertFalse(
                                                    f.write(
                                                            () ->
                                                                    f.challenges(DEADLINE)
                                                                            .consume(
                                                                                    token,
                                                                                    IdentityChallengePurpose
                                                                                            .VERIFICATION,
                                                                                    ignored ->
                                                                                            fail(
                                                                                                    "old token"
                                                                                                        + " followed"
                                                                                                        + " new owner"))));
                                            assertEquals(fresh.subject(), f.owner(KEY).id());
                                            assertEquals(1, f.used(PENDING_REGISTRATIONS));
                                        }));
    }

    @Test
    void addressWriteAndCleanupFailuresRollBackAllDependentRowsAndCapacity() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            var f = new Fixture(c);
                                            f.setup(3);
                                            try (var admin =
                                                            DriverManager.getConnection(
                                                                    db.url, db.credentials);
                                                    var sql = admin.createStatement()) {
                                                sql.execute(
                                                        "REVOKE INSERT ON"
                                                            + " \"org.vernac.saas.identity.persistence\".\"IdentityLoginAddress\""
                                                            + " FROM "
                                                                + db.role);
                                                assertThrows(
                                                        UnexpectedRollbackException.class,
                                                        () ->
                                                                new TransactionTemplate(f.manager)
                                                                        .executeWithoutResult(
                                                                                status -> {
                                                                                    var failure =
                                                                                            assertThrows(
                                                                                                    JdbcIdentityRegistrations
                                                                                                            .Failure
                                                                                                            .class,
                                                                                                    () ->
                                                                                                            f.registrations(
                                                                                                                            NOW)
                                                                                                                    .request(
                                                                                                                            LoginAddress
                                                                                                                                    .parse(
                                                                                                                                            ORIGINAL)));
                                                                                    assertEquals(
                                                                                            JdbcIdentityRegistrations
                                                                                                    .Code
                                                                                                    .STORAGE_UNAVAILABLE,
                                                                                            failure
                                                                                                    .code());
                                                                                    assertNull(
                                                                                            failure
                                                                                                    .getCause());
                                                                                    assertFalse(
                                                                                            failure.toString()
                                                                                                    .contains(
                                                                                                            ORIGINAL));
                                                                                }));
                                                assertEquals(0, f.used(PENDING_REGISTRATIONS));
                                                assertEquals(0, f.count("Identity"));
                                                assertEquals(0, f.count("IdentityLoginAddress"));
                                                sql.execute(
                                                        "GRANT INSERT ON"
                                                            + " \"org.vernac.saas.identity.persistence\".\"IdentityLoginAddress\""
                                                            + " TO "
                                                                + db.role);
                                                var first = f.request(ORIGINAL, NOW);
                                                f.write(
                                                        () ->
                                                                f.challenges(NOW)
                                                                        .issue(
                                                                                first.subject(),
                                                                                IdentityChallengePurpose
                                                                                        .VERIFICATION));
                                                sql.execute(
                                                        "REVOKE DELETE ON"
                                                            + " \"org.vernac.saas.identity.persistence\".\"IdentityLoginAddress\""
                                                            + " FROM "
                                                                + db.role);
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
                                                assertEquals(1, f.used(PENDING_REGISTRATIONS));
                                                assertEquals(1, f.used(ACTIONABLE_CHALLENGES));
                                                assertEquals(1, f.count("IdentityChallenge"));
                                                assertEquals(1, f.count("IdentityLoginAddress"));
                                                sql.execute(
                                                        "GRANT DELETE ON"
                                                            + " \"org.vernac.saas.identity.persistence\".\"IdentityLoginAddress\""
                                                            + " TO "
                                                                + db.role);
                                            } catch (Exception failure) {
                                                throw new AssertionError(failure);
                                            }
                                            assertEquals(
                                                    1,
                                                    f.write(
                                                                    () ->
                                                                            f.registrations(
                                                                                            DEADLINE)
                                                                                    .sweep(
                                                                                            null,
                                                                                            500))
                                                            .removed());
                                            assertEquals(0, f.used(PENDING_REGISTRATIONS));
                                            assertEquals(0, f.count("IdentityLoginAddress"));
                                        }));
    }

    @Test
    void databaseConstraintIsExactAndLegacyUnclaimedPendingRowsAreNotInventedIntoAddresses()
            throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            var f = new Fixture(c);
                                            f.setup(3);
                                            f.request(ORIGINAL, NOW);
                                            var conflict =
                                                    assertThrows(
                                                            UniqueConstraintViolationException
                                                                    .class,
                                                            () ->
                                                                    f.write(
                                                                            () ->
                                                                                    f.addresses
                                                                                            .save(
                                                                                                    IdentityLoginAddress
                                                                                                            .create(
                                                                                                                    IdentityLoginKey
                                                                                                                            .of(
                                                                                                                                    KEY),
                                                                                                                    IdentityDeliveryAddress
                                                                                                                            .of(
                                                                                                                                    KEY)))));
                                            assertEquals("loginKey", conflict.field());
                                            assertNull(conflict.getCause());
                                            assertFalse(conflict.toString().contains(KEY));
                                            var legacy =
                                                    f.write(
                                                            () -> {
                                                                assertTrue(
                                                                        f.capacity.reserve(
                                                                                Map.of(
                                                                                        PENDING_REGISTRATIONS,
                                                                                        1L)));
                                                                var identity =
                                                                        Identity.reconstitute(
                                                                                UserId.create(),
                                                                                IdentityStatus
                                                                                        .PENDING_VERIFICATION,
                                                                                IdentitySecurityEpoch
                                                                                        .of(0),
                                                                                NOW,
                                                                                NOW,
                                                                                0);
                                                                f.identities.save(identity);
                                                                f.pending.save(
                                                                        IdentityPendingRegistration
                                                                                .reconstitute(
                                                                                        identity
                                                                                                .id(),
                                                                                        IdentityRegistrationDeadline
                                                                                                .of(
                                                                                                        DEADLINE),
                                                                                        NOW,
                                                                                        NOW,
                                                                                        0));
                                                                return identity.id();
                                                            });
                                            assertTrue(
                                                    f.write(() -> f.addresses.existing(legacy))
                                                            .isEmpty());
                                            var failure =
                                                    assertThrows(
                                                            JdbcIdentityRegistrations.Failure.class,
                                                            () ->
                                                                    f.write(
                                                                            () ->
                                                                                    f.registrations(
                                                                                                    NOW)
                                                                                            .complete(
                                                                                                    legacy,
                                                                                                    ENCODED,
                                                                                                    ignored ->
                                                                                                            fail())));
                                            assertEquals(
                                                    JdbcIdentityRegistrations.Code
                                                            .INCONSISTENT_STATE,
                                                    failure.code());
                                            assertEquals(
                                                    2,
                                                    f.write(
                                                                    () ->
                                                                            f.registrations(
                                                                                            DEADLINE)
                                                                                    .sweep(
                                                                                            null,
                                                                                            500))
                                                            .removed());
                                            assertEquals(0, f.count("Identity"));
                                            assertEquals(0, f.count("IdentityLoginAddress"));
                                            assertEquals(0, f.used(PENDING_REGISTRATIONS));
                                        }));
    }

    @Test
    void cleanupRacingWithAnotherRequestCannotDeleteTheFreshOwner() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        a ->
                                                db.boot.run(
                                                        b -> {
                                                            var first = new Fixture(a);
                                                            var second = new Fixture(b);
                                                            first.setup(3);
                                                            var original =
                                                                    first.request(ORIGINAL, NOW);
                                                            var start = new CountDownLatch(1);
                                                            try (var pool =
                                                                    Executors.newFixedThreadPool(
                                                                            2)) {
                                                                var cleanup =
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
                                                                var requested =
                                                                        pool.submit(
                                                                                () -> {
                                                                                    start.await();
                                                                                    return second
                                                                                            .request(
                                                                                                    KEY,
                                                                                                    DEADLINE);
                                                                                });
                                                                start.countDown();
                                                                assertEquals(
                                                                        1,
                                                                        cleanup.get(
                                                                                20,
                                                                                TimeUnit.SECONDS));
                                                                var result =
                                                                        requested.get(
                                                                                20,
                                                                                TimeUnit.SECONDS);
                                                                if (result.outcome()
                                                                        == JdbcIdentityRegistrations
                                                                                .Outcome
                                                                                .UNAVAILABLE)
                                                                    result =
                                                                            second.request(
                                                                                    KEY, DEADLINE);
                                                                assertEquals(
                                                                        JdbcIdentityRegistrations
                                                                                .Outcome.CREATED,
                                                                        result.outcome());
                                                                assertNotEquals(
                                                                        original.subject(),
                                                                        result.subject());
                                                                assertEquals(
                                                                        result.subject(),
                                                                        first.owner(KEY).id());
                                                            } catch (Exception failure) {
                                                                throw new AssertionError(failure);
                                                            }
                                                            assertEquals(
                                                                    0,
                                                                    first.write(
                                                                                    () ->
                                                                                            first.registrations(
                                                                                                            DEADLINE)
                                                                                                    .sweep(
                                                                                                            null,
                                                                                                            500))
                                                                            .removed());
                                                            assertEquals(
                                                                    1,
                                                                    second.count(
                                                                            "IdentityLoginAddress"));
                                                            assertEquals(
                                                                    1,
                                                                    second.count(
                                                                            "IdentityPendingRegistration"));
                                                            assertEquals(
                                                                    1,
                                                                    second.used(
                                                                            PENDING_REGISTRATIONS));
                                                        })));
    }
}
