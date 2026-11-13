// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import static org.junit.jupiter.api.Assertions.*;

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
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

@EnabledIfEnvironmentVariable(named = "VERNAC_PG_TEST_URL", matches = ".+")
class IdentityCredentialsPostgresIT {
    @TempDir Path root;
    static final CredentialPasswords PASSWORDS =
            new CredentialPasswords(OfflinePasswordBlocklist.bundled());
    static final String FIRST = "independent credential phrase number one 7839";
    static final String SECOND = "independent replacement phrase number two 2947";
    static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");

    static final class Fixture {
        final AssertableApplicationContext context;
        final TransactionTemplate tx;
        final NamedParameterJdbcTemplate jdbc;
        final JdbcIdentityCredentials credentials;

        Fixture(AssertableApplicationContext context) {
            assertNull(context.getStartupFailure());
            this.context = context;
            tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            jdbc = context.getBean(NamedParameterJdbcTemplate.class);
            credentials = new JdbcIdentityCredentials(jdbc);
        }

        <T> T write(Supplier<T> action) {
            return tx.execute(s -> action.get());
        }

        IdentityRepository identities() {
            return context.getBean(IdentityRepository.class);
        }

        UserId pending() {
            return write(
                    () ->
                            identities()
                                    .save(
                                            Identity.create(
                                                    IdentityStatus.PENDING_VERIFICATION,
                                                    IdentitySecurityEpoch.of(0)))
                                    .id());
        }

        void activate(UserId id) {
            write(
                    () -> {
                        var row = identities().byId(id);
                        row.activate();
                        return identities().save(row);
                    });
        }

        long epoch(UserId id) {
            return write(() -> identities().byId(id).securityEpoch().number());
        }

        JdbcIdentityCredentials.Snapshot snapshot(UserId id) {
            return write(() -> credentials.snapshot(id).orElseThrow());
        }
    }

    @Test
    void enrollmentIsPrivateUniqueAndRedactedAndSurvivesRestart() throws Exception {
        String encoded = PASSWORDS.encode(FIRST), other = PASSWORDS.encode(SECOND);
        var id = new AtomicReference<UserId>();
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db -> {
                            db.boot.run(
                                    c -> {
                                        var f = new Fixture(c);
                                        id.set(f.pending());
                                        assertEquals(
                                                Boolean.FALSE,
                                                f.write(
                                                        () ->
                                                                f.credentials.enroll(
                                                                        UserId.create(), encoded)));
                                        assertEquals(
                                                Boolean.TRUE,
                                                f.write(
                                                        () ->
                                                                f.credentials.enroll(
                                                                        id.get(), encoded)));
                                        assertEquals(
                                                Boolean.FALSE,
                                                f.write(
                                                        () ->
                                                                f.credentials.enroll(
                                                                        id.get(), other)));
                                        assertEquals(
                                                Boolean.TRUE,
                                                f.write(() -> f.credentials.snapshot(id.get()))
                                                        .isEmpty());
                                        f.activate(id.get());
                                        assertEquals(
                                                Boolean.FALSE,
                                                f.write(
                                                        () ->
                                                                f.credentials.enroll(
                                                                        id.get(), other)));
                                        var snapshot = f.snapshot(id.get());
                                        assertEquals(0, snapshot.securityEpoch());
                                        assertEquals(1, snapshot.credentialVersion());
                                        assertTrue(PASSWORDS.matches(FIRST, snapshot.encoded()));
                                        assertFalse(PASSWORDS.matches(SECOND, snapshot.encoded()));
                                        assertFalse(snapshot.toString().contains(encoded));
                                        assertFalse(snapshot.toString().contains(FIRST));
                                        var row =
                                                f.write(
                                                        () ->
                                                                c.getBean(
                                                                                IdentityCredentialRepository
                                                                                        .class)
                                                                        .byId(id.get()));
                                        assertFalse(row.toString().contains(encoded));
                                        assertTrue(row.toString().contains("REDACTED"));
                                        assertThrows(
                                                IllegalTransactionStateException.class,
                                                () -> f.credentials.snapshot(id.get()));
                                        var invalid =
                                                assertThrows(
                                                        JdbcIdentityCredentials.Failure.class,
                                                        () ->
                                                                f.write(
                                                                        () ->
                                                                                f.credentials
                                                                                        .enroll(
                                                                                                id
                                                                                                        .get(),
                                                                                                "{noop}seeded-secret")));
                                        assertEquals(
                                                JdbcIdentityCredentials.Code.INVALID_ENCODING,
                                                invalid.code());
                                        assertNull(invalid.getCause());
                                        assertFalse(invalid.toString().contains("seeded-secret"));
                                    });
                            db.boot.run(
                                    c -> {
                                        var f = new Fixture(c);
                                        var snapshot = f.snapshot(id.get());
                                        assertTrue(PASSWORDS.matches(FIRST, snapshot.encoded()));
                                        assertEquals(
                                                Boolean.TRUE,
                                                f.write(() -> f.credentials.confirm(snapshot)));
                                        assertEquals(0, f.epoch(id.get()));
                                    });
                            assertEquals(5, db.migrationCount());
                        });
    }

    @Test
    void changesAdvanceEpochRehashDoesNotAndStaleSnapshotsCannotOverwrite() throws Exception {
        String first = PASSWORDS.encode(FIRST),
                rehash = PASSWORDS.encode(FIRST),
                second = PASSWORDS.encode(SECOND);
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            var f = new Fixture(c);
                                            var id = f.pending();
                                            f.write(() -> f.credentials.enroll(id, first));
                                            f.activate(id);
                                            var initial = f.snapshot(id);
                                            assertEquals(
                                                    Boolean.TRUE,
                                                    f.write(
                                                            () ->
                                                                    f.credentials.rehash(
                                                                            initial, rehash)));
                                            assertEquals(0, f.epoch(id));
                                            assertEquals(
                                                    Boolean.FALSE,
                                                    f.write(() -> f.credentials.confirm(initial)));
                                            assertEquals(
                                                    Boolean.FALSE,
                                                    f.write(
                                                            () ->
                                                                    f.credentials.replace(
                                                                            initial, second)));
                                            var latest = f.snapshot(id);
                                            assertTrue(PASSWORDS.matches(FIRST, latest.encoded()));
                                            assertEquals(
                                                    Boolean.TRUE,
                                                    f.write(
                                                            () ->
                                                                    f.credentials.replace(
                                                                            latest, second)));
                                            assertEquals(1, f.epoch(id));
                                            assertEquals(
                                                    Boolean.FALSE,
                                                    f.write(
                                                            () ->
                                                                    f.credentials.rehash(
                                                                            latest, rehash)));
                                            var changed = f.snapshot(id);
                                            assertTrue(
                                                    PASSWORDS.matches(SECOND, changed.encoded()));
                                            f.write(
                                                    () -> {
                                                        var row = f.identities().byId(id);
                                                        row.disable();
                                                        return f.identities().save(row);
                                                    });
                                            assertEquals(2, f.epoch(id));
                                            assertEquals(
                                                    Boolean.FALSE,
                                                    f.write(() -> f.credentials.confirm(changed)));
                                            assertEquals(
                                                    Boolean.TRUE,
                                                    f.write(() -> f.credentials.snapshot(id))
                                                            .isEmpty());
                                            f.write(
                                                    () -> {
                                                        var row = f.identities().byId(id);
                                                        row.close();
                                                        return f.identities().save(row);
                                                    });
                                            assertEquals(3, f.epoch(id));
                                            f.write(
                                                    () -> {
                                                        var row = f.identities().byId(id);
                                                        row.close();
                                                        return f.identities().save(row);
                                                    });
                                            assertEquals(3, f.epoch(id));
                                        }));
    }

    @Test
    void challengeConsumptionCredentialsActivationAndEpochShareRollback() throws Exception {
        String first = PASSWORDS.encode(FIRST), second = PASSWORDS.encode(SECOND);
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            var f = new Fixture(c);
                                            var id = f.pending();
                                            var ledger = new JdbcIdentityCapacity(f.jdbc);
                                            f.write(
                                                    () -> {
                                                        ledger.initialize(
                                                                JdbcIdentityCapacity.defaults());
                                                        return true;
                                                    });
                                            var challenges =
                                                    new JdbcIdentityChallenges(
                                                            f.jdbc,
                                                            Clock.fixed(NOW, ZoneOffset.UTC));
                                            String token =
                                                    f.write(
                                                                    () ->
                                                                            challenges.issue(
                                                                                    id,
                                                                                    IdentityChallengePurpose
                                                                                            .VERIFICATION))
                                                            .token()
                                                            .deliveryValue();
                                            assertThrows(
                                                    UnexpectedRollbackException.class,
                                                    () ->
                                                            f.write(
                                                                    () -> {
                                                                        assertThrows(
                                                                                IllegalArgumentException
                                                                                        .class,
                                                                                () ->
                                                                                        challenges
                                                                                                .consume(
                                                                                                        token,
                                                                                                        IdentityChallengePurpose
                                                                                                                .VERIFICATION,
                                                                                                        subject -> {
                                                                                                            assertTrue(
                                                                                                                    f
                                                                                                                            .credentials
                                                                                                                            .enroll(
                                                                                                                                    subject,
                                                                                                                                    first));
                                                                                                            var
                                                                                                                    row =
                                                                                                                            f.identities()
                                                                                                                                    .byId(
                                                                                                                                            subject);
                                                                                                            row
                                                                                                                    .activate();
                                                                                                            f.identities()
                                                                                                                    .save(
                                                                                                                            row);
                                                                                                            throw new IllegalArgumentException(
                                                                                                                    "safe provisioning"
                                                                                                                        + " fixture"
                                                                                                                        + " failure");
                                                                                                        }));
                                                                        return true;
                                                                    }));
                                            assertEquals(
                                                    IdentityStatus.PENDING_VERIFICATION,
                                                    f.write(
                                                            () ->
                                                                    f.identities()
                                                                            .byId(id)
                                                                            .status()));
                                            assertEquals(
                                                    Boolean.TRUE,
                                                    f.write(
                                                                    () ->
                                                                            c.getBean(
                                                                                            IdentityCredentialRepository
                                                                                                    .class)
                                                                                    .existing(id))
                                                            .isEmpty());
                                            assertEquals(
                                                    1,
                                                    (long)
                                                            f.write(
                                                                    () ->
                                                                            ledger.usage(
                                                                                    JdbcIdentityCapacity
                                                                                            .Resource
                                                                                            .ACTIONABLE_CHALLENGES)));
                                            assertEquals(
                                                    Boolean.TRUE,
                                                    f.write(
                                                            () ->
                                                                    challenges.consume(
                                                                            token,
                                                                            IdentityChallengePurpose
                                                                                    .VERIFICATION,
                                                                            subject -> {
                                                                                assertTrue(
                                                                                        f
                                                                                                .credentials
                                                                                                .enroll(
                                                                                                        subject,
                                                                                                        first));
                                                                                var row =
                                                                                        f.identities()
                                                                                                .byId(
                                                                                                        subject);
                                                                                row.activate();
                                                                                f.identities()
                                                                                        .save(row);
                                                                            })));
                                            var current = f.snapshot(id);
                                            f.tx.executeWithoutResult(
                                                    s -> {
                                                        assertTrue(
                                                                f.credentials.replace(
                                                                        current, second));
                                                        s.setRollbackOnly();
                                                    });
                                            assertEquals(0, f.epoch(id));
                                            assertTrue(
                                                    PASSWORDS.matches(
                                                            FIRST, f.snapshot(id).encoded()));
                                            assertEquals(
                                                    Boolean.TRUE,
                                                    f.write(() -> f.credentials.confirm(current)));
                                        }));
    }

    @Test
    void twoContextsAllowOnlyOneChangeAndRejectRehashAfterDisable() throws Exception {
        String first = PASSWORDS.encode(FIRST),
                second = PASSWORDS.encode(SECOND),
                rehash = PASSWORDS.encode(SECOND);
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        a ->
                                                db.boot.run(
                                                        b -> {
                                                            var left = new Fixture(a);
                                                            var right = new Fixture(b);
                                                            var id = left.pending();
                                                            left.write(
                                                                    () ->
                                                                            left.credentials.enroll(
                                                                                    id, first));
                                                            left.activate(id);
                                                            var snapshot = left.snapshot(id);
                                                            try (var pool =
                                                                    Executors.newFixedThreadPool(
                                                                            2)) {
                                                                var start = new CountDownLatch(1);
                                                                var x =
                                                                        pool.submit(
                                                                                () -> {
                                                                                    start.await();
                                                                                    return left
                                                                                            .write(
                                                                                                    () ->
                                                                                                            left
                                                                                                                    .credentials
                                                                                                                    .replace(
                                                                                                                            snapshot,
                                                                                                                            second));
                                                                                });
                                                                var y =
                                                                        pool.submit(
                                                                                () -> {
                                                                                    start.await();
                                                                                    return right
                                                                                            .write(
                                                                                                    () ->
                                                                                                            right
                                                                                                                    .credentials
                                                                                                                    .replace(
                                                                                                                            snapshot,
                                                                                                                            second));
                                                                                });
                                                                start.countDown();
                                                                assertNotEquals(
                                                                        x.get(30, TimeUnit.SECONDS),
                                                                        y.get(
                                                                                30,
                                                                                TimeUnit.SECONDS));
                                                                assertEquals(1, right.epoch(id));
                                                                assertTrue(
                                                                        PASSWORDS.matches(
                                                                                SECOND,
                                                                                right.snapshot(id)
                                                                                        .encoded()));
                                                                var fresh = left.snapshot(id);
                                                                var locked = new CountDownLatch(1);
                                                                var release = new CountDownLatch(1);
                                                                var disable =
                                                                        pool.submit(
                                                                                () ->
                                                                                        left.write(
                                                                                                () -> {
                                                                                                    var
                                                                                                            row =
                                                                                                                    left.identities()
                                                                                                                            .byId(
                                                                                                                                    id);
                                                                                                    row
                                                                                                            .disable();
                                                                                                    left.identities()
                                                                                                            .save(
                                                                                                                    row);
                                                                                                    locked
                                                                                                            .countDown();
                                                                                                    try {
                                                                                                        assertTrue(
                                                                                                                release
                                                                                                                        .await(
                                                                                                                                20,
                                                                                                                                TimeUnit
                                                                                                                                        .SECONDS));
                                                                                                    } catch (
                                                                                                            InterruptedException
                                                                                                                    e) {
                                                                                                        throw new AssertionError(
                                                                                                                e);
                                                                                                    }
                                                                                                    return true;
                                                                                                }));
                                                                assertTrue(
                                                                        locked.await(
                                                                                20,
                                                                                TimeUnit.SECONDS));
                                                                var attempted =
                                                                        new CountDownLatch(1);
                                                                var stale =
                                                                        pool.submit(
                                                                                () -> {
                                                                                    attempted
                                                                                            .countDown();
                                                                                    return right
                                                                                            .write(
                                                                                                    () ->
                                                                                                            right
                                                                                                                    .credentials
                                                                                                                    .rehash(
                                                                                                                            fresh,
                                                                                                                            rehash));
                                                                                });
                                                                assertTrue(
                                                                        attempted.await(
                                                                                20,
                                                                                TimeUnit.SECONDS));
                                                                try {
                                                                    assertThrows(
                                                                            TimeoutException.class,
                                                                            () ->
                                                                                    stale.get(
                                                                                            150,
                                                                                            TimeUnit
                                                                                                    .MILLISECONDS));
                                                                } finally {
                                                                    release.countDown();
                                                                }
                                                                assertTrue(
                                                                        disable.get(
                                                                                30,
                                                                                TimeUnit.SECONDS));
                                                                assertFalse(
                                                                        stale.get(
                                                                                30,
                                                                                TimeUnit.SECONDS));
                                                                assertEquals(2, right.epoch(id));
                                                                assertEquals(
                                                                        Boolean.TRUE,
                                                                        right.write(
                                                                                        () ->
                                                                                                right
                                                                                                        .credentials
                                                                                                        .snapshot(
                                                                                                                id))
                                                                                .isEmpty());
                                                            } catch (Exception e) {
                                                                throw new AssertionError(e);
                                                            }
                                                        })));
    }

    @Test
    void corruptStorageAndEpochExhaustionFailWithoutOverwritingCredentials() throws Exception {
        String first = PASSWORDS.encode(FIRST), second = PASSWORDS.encode(SECOND);
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            var f = new Fixture(c);
                                            var id = f.pending();
                                            f.write(() -> f.credentials.enroll(id, first));
                                            f.activate(id);
                                            f.jdbc.getJdbcTemplate()
                                                    .update(
                                                            "UPDATE"
                                                                + " \"org.vernac.saas.identity.persistence\".\"IdentityCredential\""
                                                                + " SET \"encoding\"='{noop}seeded-secret'");
                                            var corrupt =
                                                    assertThrows(
                                                            JdbcIdentityCredentials.Failure.class,
                                                            () -> f.snapshot(id));
                                            assertEquals(
                                                    JdbcIdentityCredentials.Code
                                                            .STORAGE_UNAVAILABLE,
                                                    corrupt.code());
                                            assertNull(corrupt.getCause());
                                            assertFalse(
                                                    corrupt.toString().contains("seeded-secret"));
                                            f.jdbc.update(
                                                    "UPDATE"
                                                        + " \"org.vernac.saas.identity.persistence\".\"IdentityCredential\""
                                                        + " SET \"encoding\"=:encoding",
                                                    Map.of("encoding", first));
                                            f.write(
                                                    () -> {
                                                        var row = f.identities().byId(id);
                                                        return f.identities()
                                                                .save(
                                                                        Identity.reconstitute(
                                                                                id,
                                                                                row.status(),
                                                                                IdentitySecurityEpoch
                                                                                        .of(
                                                                                                Long
                                                                                                        .MAX_VALUE),
                                                                                row.createdAt(),
                                                                                row.updatedAt(),
                                                                                row.persistenceState()
                                                                                        .version()));
                                                    });
                                            var max = f.snapshot(id);
                                            assertThrows(
                                                    JdbcIdentityCredentials.Failure.class,
                                                    () ->
                                                            f.write(
                                                                    () ->
                                                                            f.credentials.replace(
                                                                                    max, second)));
                                            assertEquals(Long.MAX_VALUE, f.epoch(id));
                                            assertTrue(
                                                    PASSWORDS.matches(
                                                            FIRST, f.snapshot(id).encoded()));
                                        }));
    }

    @Test
    void credentialWriteFailureRollsBackAnAlreadyWrittenEpochEvenWhenCaught() throws Exception {
        String first = PASSWORDS.encode(FIRST), second = PASSWORDS.encode(SECOND);
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            var f = new Fixture(c);
                                            var id = f.pending();
                                            f.write(() -> f.credentials.enroll(id, first));
                                            f.activate(id);
                                            var snapshot = f.snapshot(id);
                                            try (var admin =
                                                            DriverManager.getConnection(
                                                                    db.url, db.credentials);
                                                    var sql = admin.createStatement()) {
                                                String table =
                                                        "\"org.vernac.saas.identity.persistence\".\"IdentityCredential\"";
                                                sql.execute(
                                                        "REVOKE UPDATE ON "
                                                                + table
                                                                + " FROM "
                                                                + db.role);
                                                try {
                                                    assertThrows(
                                                            UnexpectedRollbackException.class,
                                                            () ->
                                                                    f.write(
                                                                            () -> {
                                                                                var failure =
                                                                                        assertThrows(
                                                                                                JdbcIdentityCredentials
                                                                                                        .Failure
                                                                                                        .class,
                                                                                                () ->
                                                                                                        f
                                                                                                                .credentials
                                                                                                                .replace(
                                                                                                                        snapshot,
                                                                                                                        second));
                                                                                assertEquals(
                                                                                        JdbcIdentityCredentials
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
                                                                                                        first));
                                                                                assertFalse(
                                                                                        failure.toString()
                                                                                                .contains(
                                                                                                        db.password));
                                                                                return true;
                                                                            }));
                                                } finally {
                                                    sql.execute(
                                                            "GRANT UPDATE ON "
                                                                    + table
                                                                    + " TO "
                                                                    + db.role);
                                                }
                                            } catch (Exception e) {
                                                throw new AssertionError(e);
                                            }
                                            assertEquals(0, f.epoch(id));
                                            assertEquals(
                                                    Boolean.TRUE,
                                                    f.write(() -> f.credentials.confirm(snapshot)));
                                            assertTrue(
                                                    PASSWORDS.matches(
                                                            FIRST, f.snapshot(id).encoded()));
                                            assertEquals(
                                                    Boolean.TRUE,
                                                    f.write(
                                                            () ->
                                                                    f.credentials.replace(
                                                                            snapshot, second)));
                                            assertEquals(1, f.epoch(id));
                                        }));
    }

    @Test
    void concurrentEnrollmentCreatesOneCredentialWithoutReplacingTheWinner() throws Exception {
        String first = PASSWORDS.encode(FIRST), second = PASSWORDS.encode(SECOND);
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        a ->
                                                db.boot.run(
                                                        b -> {
                                                            var left = new Fixture(a);
                                                            var right = new Fixture(b);
                                                            var id = left.pending();
                                                            try (var pool =
                                                                    Executors.newFixedThreadPool(
                                                                            2)) {
                                                                var start = new CountDownLatch(1);
                                                                var x =
                                                                        pool.submit(
                                                                                () -> {
                                                                                    start.await();
                                                                                    return left
                                                                                            .write(
                                                                                                    () ->
                                                                                                            left
                                                                                                                    .credentials
                                                                                                                    .enroll(
                                                                                                                            id,
                                                                                                                            first));
                                                                                });
                                                                var y =
                                                                        pool.submit(
                                                                                () -> {
                                                                                    start.await();
                                                                                    return right
                                                                                            .write(
                                                                                                    () ->
                                                                                                            right
                                                                                                                    .credentials
                                                                                                                    .enroll(
                                                                                                                            id,
                                                                                                                            second));
                                                                                });
                                                                start.countDown();
                                                                boolean firstWon =
                                                                        x.get(30, TimeUnit.SECONDS);
                                                                assertNotEquals(
                                                                        firstWon,
                                                                        y.get(
                                                                                30,
                                                                                TimeUnit.SECONDS));
                                                                left.activate(id);
                                                                var snapshot = right.snapshot(id);
                                                                assertTrue(
                                                                        PASSWORDS.matches(
                                                                                firstWon
                                                                                        ? FIRST
                                                                                        : SECOND,
                                                                                snapshot
                                                                                        .encoded()));
                                                                assertFalse(
                                                                        PASSWORDS.matches(
                                                                                firstWon
                                                                                        ? SECOND
                                                                                        : FIRST,
                                                                                snapshot
                                                                                        .encoded()));
                                                                assertEquals(0, right.epoch(id));
                                                                assertEquals(
                                                                        1,
                                                                        snapshot
                                                                                .credentialVersion());
                                                                assertEquals(
                                                                        1,
                                                                        left.jdbc
                                                                                .getJdbcTemplate()
                                                                                .queryForObject(
                                                                                        "SELECT"
                                                                                            + " count(*)"
                                                                                            + " FROM"
                                                                                            + " \"org.vernac.saas.identity.persistence\".\"IdentityCredential\"",
                                                                                        Integer
                                                                                                .class));
                                                            } catch (Exception e) {
                                                                throw new AssertionError(e);
                                                            }
                                                        })));
    }

    @Test
    void boundedSpringVerificationRunsOutsideTheWriterAndStillConfirmsCurrentState()
            throws Exception {
        try (var work = new BoundedCredentialPasswords(PASSWORDS)) {
            String encoded = work.encode(FIRST);
            new IdentityJdbcTestEnvironment(root)
                    .withDatabase(
                            db ->
                                    db.boot.run(
                                            c -> {
                                                var f = new Fixture(c);
                                                var id = f.pending();
                                                f.write(() -> f.credentials.enroll(id, encoded));
                                                f.activate(id);
                                                var snapshot = f.snapshot(id);
                                                var denied =
                                                        assertThrows(
                                                                CredentialConfigurationException
                                                                        .class,
                                                                () ->
                                                                        f.write(
                                                                                () ->
                                                                                        work
                                                                                                .matches(
                                                                                                        FIRST,
                                                                                                        snapshot
                                                                                                                .encoded())));
                                                assertEquals(
                                                        CredentialConfigurationException.Code
                                                                .WORK_CONTEXT_INVALID,
                                                        denied.code());
                                                assertTrue(work.matches(FIRST, snapshot.encoded()));
                                                assertEquals(
                                                        Boolean.TRUE,
                                                        f.write(
                                                                () ->
                                                                        f.credentials.confirm(
                                                                                snapshot)));
                                                String changed = work.encode(SECOND);
                                                assertEquals(
                                                        Boolean.TRUE,
                                                        f.write(
                                                                () ->
                                                                        f.credentials.replace(
                                                                                snapshot,
                                                                                changed)));
                                                assertEquals(
                                                        Boolean.FALSE,
                                                        f.write(
                                                                () ->
                                                                        f.credentials.confirm(
                                                                                snapshot)));
                                                assertTrue(
                                                        work.matches(
                                                                SECOND, f.snapshot(id).encoded()));
                                            }));
        }
    }
}
