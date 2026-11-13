// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.vernac.saas.identity.persistence.JdbcIdentityCapacity.Resource.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.vernac.saas.identity.domain.*;
import org.vernac.saas.identity.persistence.domain.*;
import org.vernac.saas.identity.security.credentials.LoginAddress;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@EnabledIfEnvironmentVariable(named = "VERNAC_PG_TEST_URL", matches = ".+")
class IdentityMailRequestsPostgresIT {
    @TempDir Path root;
    static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");

    static TransactionTemplate tx(AssertableApplicationContext c) {
        return new TransactionTemplate(c.getBean(PlatformTransactionManager.class));
    }

    static NamedParameterJdbcTemplate jdbc(AssertableApplicationContext c) {
        return c.getBean(NamedParameterJdbcTemplate.class);
    }

    static void initialize(AssertableApplicationContext c) {
        assertNull(c.getStartupFailure());
        tx(c).executeWithoutResult(
                        s ->
                                new JdbcIdentityCapacity(jdbc(c))
                                        .initialize(JdbcIdentityCapacity.defaults()));
        new JdbcIdentityEncryptionAdmission(
                        jdbc(c),
                        c.getBean(PlatformTransactionManager.class),
                        Clock.fixed(NOW, ZoneOffset.UTC))
                .initialize();
    }

    static final class Flow {
        final AssertableApplicationContext c;
        final IdentityDeliveryCrypto crypto;
        final JdbcIdentityMailRequests requests;

        Flow(AssertableApplicationContext c, Instant instant) {
            this.c = c;
            var clock = Clock.fixed(instant, ZoneOffset.UTC);
            byte[] key = new byte[32];
            Arrays.fill(key, (byte) 3);
            crypto =
                    new IdentityDeliveryCrypto(
                            "key",
                            Map.of("key", key),
                            clock,
                            new JdbcIdentityEncryptionAdmission(
                                    jdbc(c), c.getBean(PlatformTransactionManager.class), clock));
            requests = new JdbcIdentityMailRequests(jdbc(c), clock, crypto);
        }

        void registration(String address) {
            var permit = crypto.prepare();
            tx(c).executeWithoutResult(
                            s -> requests.registration(LoginAddress.parse(address), permit));
        }

        void recovery(String address) {
            var permit = crypto.prepare();
            tx(c).executeWithoutResult(s -> requests.recovery(LoginAddress.parse(address), permit));
        }
    }

    static UserId subject(AssertableApplicationContext c, String email) {
        return tx(c).execute(
                        s ->
                                c.getBean(IdentityLoginAddressRepository.class)
                                        .forKey(
                                                IdentityLoginKey.of(
                                                        LoginAddress.parse(email).loginKey()))
                                        .orElseThrow()
                                        .id());
    }

    static IdentityMailJob job(
            AssertableApplicationContext c, UserId subject, IdentityChallengePurpose purpose) {
        return tx(c).execute(
                        s -> {
                            var challenge =
                                    c.getBean(IdentityChallengeRepository.class)
                                            .current(subject, purpose)
                                            .orElseThrow();
                            return c.getBean(IdentityMailJobRepository.class)
                                    .forChallenge(challenge.id())
                                    .orElseThrow();
                        });
    }

    static long used(AssertableApplicationContext c, JdbcIdentityCapacity.Resource resource) {
        return tx(c).execute(s -> new JdbcIdentityCapacity(jdbc(c)).usage(resource));
    }

    static IdentityMailContent content(IdentityDeliveryCrypto crypto, IdentityMailJob job) {
        byte[] clear =
                crypto.open(
                        new IdentityDeliveryCrypto.Context(
                                job.id().value(),
                                job.subject().value(),
                                job.purpose(),
                                job.challenge().value(),
                                job.expiresAt().instant()),
                        job.envelope().encoded());
        try {
            return IdentityMailContent.decode(clear, job.challenge().value());
        } finally {
            Arrays.fill(clear, (byte) 0);
        }
    }

    static void safe(org.junit.jupiter.api.function.Executable work) {
        var error = assertThrows(IllegalStateException.class, work);
        assertEquals("IDENTITY_DELIVERY_UNAVAILABLE", error.getMessage());
        assertNull(error.getCause());
    }

    @Test
    void registrationPersistsOnlyProtectedMailAndCooldownReusesOriginalOwner() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            initialize(c);
                                            var flow = new Flow(c, NOW);
                                            flow.registration("Original@Example.test");
                                            var subject = subject(c, "original@example.test");
                                            var job =
                                                    job(
                                                            c,
                                                            subject,
                                                            IdentityChallengePurpose.VERIFICATION);
                                            var content = content(flow.crypto, job);
                                            assertEquals(
                                                    "Original@Example.test", content.recipient());
                                            var challenge =
                                                    tx(c).execute(
                                                                    s ->
                                                                            c.getBean(
                                                                                            IdentityChallengeRepository
                                                                                                    .class)
                                                                                    .byId(
                                                                                            job
                                                                                                    .challenge()));
                                            assertTrue(
                                                    IdentityChallengeToken.parse(
                                                                    content.deliveryToken())
                                                            .matches(
                                                                    challenge
                                                                            .verifier()
                                                                            .encoded()));
                                            String stored =
                                                    jdbc(c).queryForList(
                                                                    "SELECT * FROM"
                                                                        + " \"org.vernac.saas.identity.persistence\".\"IdentityMailJob\"",
                                                                    Map.of())
                                                            .toString();
                                            assertFalse(stored.contains(content.deliveryToken()));
                                            assertFalse(stored.contains(content.recipient()));
                                            assertFalse(
                                                    job.toString()
                                                            .contains(content.deliveryToken()));
                                            assertEquals(
                                                    "IdentityMailContent[REDACTED]",
                                                    content.toString());
                                            flow.registration("ORIGINAL@EXAMPLE.TEST");
                                            assertEquals(
                                                    job.id(),
                                                    job(
                                                                    c,
                                                                    subject,
                                                                    IdentityChallengePurpose
                                                                            .VERIFICATION)
                                                            .id());
                                            assertEquals(1, used(c, PENDING_REGISTRATIONS));
                                            assertEquals(1, used(c, ACTIONABLE_CHALLENGES));
                                            assertEquals(1, used(c, UNSENT_MAIL_JOBS));
                                            assertEquals(
                                                    job.envelope().encoded().length(),
                                                    used(c, UNSENT_MAIL_BYTES));
                                        }));
    }

    @Test
    void restartAndChallengeConsumptionKeepSecretErasureAndRefundAtomic() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db -> {
                            db.boot.run(
                                    c -> {
                                        initialize(c);
                                        new Flow(c, NOW).registration("restart@example.test");
                                    });
                            db.boot.run(
                                    c -> {
                                        assertNull(c.getStartupFailure());
                                        var flow = new Flow(c, NOW);
                                        var owner = subject(c, "restart@example.test");
                                        var job =
                                                job(
                                                        c,
                                                        owner,
                                                        IdentityChallengePurpose.VERIFICATION);
                                        var content = content(flow.crypto, job);
                                        var challenges =
                                                new JdbcIdentityChallenges(
                                                        jdbc(c), Clock.fixed(NOW, ZoneOffset.UTC));
                                        assertThrows(
                                                IllegalStateException.class,
                                                () ->
                                                        tx(c).executeWithoutResult(
                                                                        s ->
                                                                                challenges.consume(
                                                                                        content
                                                                                                .deliveryToken(),
                                                                                        IdentityChallengePurpose
                                                                                                .VERIFICATION,
                                                                                        user -> {
                                                                                            throw new IllegalStateException(
                                                                                                    "synthetic"
                                                                                                        + " action"
                                                                                                        + " failure");
                                                                                        })));
                                        assertEquals(1, used(c, ACTIONABLE_CHALLENGES));
                                        assertEquals(1, used(c, UNSENT_MAIL_JOBS));
                                        assertEquals(
                                                job.envelope().encoded().length(),
                                                used(c, UNSENT_MAIL_BYTES));
                                        tx(c).executeWithoutResult(
                                                        s ->
                                                                assertTrue(
                                                                        challenges.consume(
                                                                                content
                                                                                        .deliveryToken(),
                                                                                IdentityChallengePurpose
                                                                                        .VERIFICATION,
                                                                                user -> {})));
                                        assertEquals(0, used(c, ACTIONABLE_CHALLENGES));
                                        assertEquals(0, used(c, UNSENT_MAIL_JOBS));
                                        assertEquals(0, used(c, UNSENT_MAIL_BYTES));
                                        tx(c).executeWithoutResult(
                                                        s ->
                                                                assertFalse(
                                                                        challenges.consume(
                                                                                content
                                                                                        .deliveryToken(),
                                                                                IdentityChallengePurpose
                                                                                        .VERIFICATION,
                                                                                user -> {})));
                                    });
                        });
    }

    @Test
    void fullJobOrByteBudgetFailsBeforeKnownAndUnknownLookupWithoutLeaks() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            initialize(c);
                                            var flow = new Flow(c, NOW);
                                            flow.registration("known@example.test");
                                            tx(c).executeWithoutResult(
                                                            s ->
                                                                    new JdbcIdentityCapacity(
                                                                                    jdbc(c))
                                                                            .changeLimit(
                                                                                    UNSENT_MAIL_JOBS,
                                                                                    1));
                                            safe(() -> flow.registration("known@example.test"));
                                            safe(() -> flow.registration("unknown@example.test"));
                                            assertEquals(1, used(c, PENDING_REGISTRATIONS));
                                            assertEquals(1, used(c, UNSENT_MAIL_JOBS));
                                            tx(c).executeWithoutResult(
                                                            s -> {
                                                                var cap =
                                                                        new JdbcIdentityCapacity(
                                                                                jdbc(c));
                                                                cap.changeLimit(
                                                                        UNSENT_MAIL_JOBS, 10);
                                                                cap.changeLimit(
                                                                        UNSENT_MAIL_BYTES, 65535);
                                                            });
                                            safe(() -> flow.recovery("known@example.test"));
                                            safe(() -> flow.recovery("unknown@example.test"));
                                            assertEquals(1, used(c, ACTIONABLE_CHALLENGES));
                                            assertTrue(
                                                    tx(c).<Boolean>execute(
                                                                    s ->
                                                                            c.getBean(
                                                                                            IdentityLoginAddressRepository
                                                                                                    .class)
                                                                                    .forKey(
                                                                                            IdentityLoginKey
                                                                                                    .of(
                                                                                                            "unknown@example.test"))
                                                                                    .isEmpty()));
                                        }));
    }

    @Test
    void rollbackAndCaughtEncryptionFailureCannotLeaveIdentityChallengeOrJob() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            initialize(c);
                                            var flow = new Flow(c, NOW);
                                            var permit = flow.crypto.prepare();
                                            tx(c).executeWithoutResult(
                                                            s -> {
                                                                flow.requests.registration(
                                                                        LoginAddress.parse(
                                                                                "rollback@example.test"),
                                                                        permit);
                                                                s.setRollbackOnly();
                                                            });
                                            assertEquals(0, used(c, PENDING_REGISTRATIONS));
                                            assertEquals(0, used(c, ACTIONABLE_CHALLENGES));
                                            assertEquals(0, used(c, UNSENT_MAIL_JOBS));
                                            assertEquals(0, used(c, UNSENT_MAIL_BYTES));
                                            assertThrows(
                                                    RuntimeException.class,
                                                    () ->
                                                            tx(c).executeWithoutResult(
                                                                            s -> {
                                                                                safe(
                                                                                        () ->
                                                                                                flow
                                                                                                        .requests
                                                                                                        .registration(
                                                                                                                LoginAddress
                                                                                                                        .parse(
                                                                                                                                "failure@example.test"),
                                                                                                                null));
                                                                            }));
                                            assertEquals(0, used(c, PENDING_REGISTRATIONS));
                                            assertEquals(0, used(c, ACTIONABLE_CHALLENGES));
                                            assertEquals(0, used(c, UNSENT_MAIL_JOBS));
                                        }));
    }

    @Test
    void resendReplacesQueuedWorkButRetainsChargedLeasedPayload() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            initialize(c);
                                            var first = new Flow(c, NOW);
                                            first.registration("original@example.test");
                                            var subject = subject(c, "original@example.test");
                                            var old =
                                                    job(
                                                            c,
                                                            subject,
                                                            IdentityChallengePurpose.VERIFICATION);
                                            var second = new Flow(c, NOW.plusSeconds(61));
                                            second.registration("ORIGINAL@EXAMPLE.TEST");
                                            var next =
                                                    job(
                                                            c,
                                                            subject,
                                                            IdentityChallengePurpose.VERIFICATION);
                                            assertNotEquals(old.id(), next.id());
                                            assertEquals(1, used(c, UNSENT_MAIL_JOBS));
                                            assertTrue(
                                                    tx(c).<Boolean>execute(
                                                                    s ->
                                                                            c.getBean(
                                                                                            IdentityMailJobRepository
                                                                                                    .class)
                                                                                    .existing(
                                                                                            old
                                                                                                    .id())
                                                                                    .isEmpty()));
                                            tx(c).executeWithoutResult(
                                                            s -> {
                                                                var r =
                                                                        c.getBean(
                                                                                IdentityMailJobRepository
                                                                                        .class);
                                                                var leased = r.byId(next.id());
                                                                leased.claim(
                                                                        UUID.randomUUID(),
                                                                        NOW.plusSeconds(600));
                                                                r.save(leased);
                                                            });
                                            new Flow(c, NOW.plusSeconds(122))
                                                    .registration("original@example.test");
                                            var latest =
                                                    job(
                                                            c,
                                                            subject,
                                                            IdentityChallengePurpose.VERIFICATION);
                                            assertEquals(2, used(c, UNSENT_MAIL_JOBS));
                                            assertEquals(
                                                    next.envelope().encoded().length()
                                                            + latest.envelope().encoded().length(),
                                                    used(c, UNSENT_MAIL_BYTES));
                                            assertEquals(
                                                    IdentityMailState.CANCELLED,
                                                    tx(c).execute(
                                                                    s ->
                                                                            c.getBean(
                                                                                            IdentityMailJobRepository
                                                                                                    .class)
                                                                                    .byId(next.id())
                                                                                    .state()));
                                            assertEquals(1, used(c, ACTIONABLE_CHALLENGES));
                                            assertEquals(
                                                    "original@example.test",
                                                    content(
                                                                    new Flow(
                                                                                    c,
                                                                                    NOW.plusSeconds(
                                                                                            122))
                                                                            .crypto,
                                                                    latest)
                                                            .recipient());
                                        }));
    }

    @Test
    void lastBacklogSlotIsAtomicAcrossConcurrentInstances() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            initialize(c);
                                            tx(c).executeWithoutResult(
                                                            s ->
                                                                    new JdbcIdentityCapacity(
                                                                                    jdbc(c))
                                                                            .changeLimit(
                                                                                    UNSENT_MAIL_JOBS,
                                                                                    1));
                                            db.boot.run(
                                                    other -> {
                                                        assertNull(other.getStartupFailure());
                                                        var first = new Flow(c, NOW);
                                                        var second = new Flow(other, NOW);
                                                        AtomicInteger admitted =
                                                                new AtomicInteger();
                                                        try (var pool =
                                                                Executors.newFixedThreadPool(2)) {
                                                            var a =
                                                                    pool.submit(
                                                                            () -> {
                                                                                try {
                                                                                    first
                                                                                            .registration(
                                                                                                    "one@example.test");
                                                                                    admitted
                                                                                            .incrementAndGet();
                                                                                } catch (
                                                                                        IllegalStateException
                                                                                                denied) {
                                                                                    assertEquals(
                                                                                            "IDENTITY_DELIVERY_UNAVAILABLE",
                                                                                            denied
                                                                                                    .getMessage());
                                                                                }
                                                                            });
                                                            var b =
                                                                    pool.submit(
                                                                            () -> {
                                                                                try {
                                                                                    second
                                                                                            .registration(
                                                                                                    "two@example.test");
                                                                                    admitted
                                                                                            .incrementAndGet();
                                                                                } catch (
                                                                                        IllegalStateException
                                                                                                denied) {
                                                                                    assertEquals(
                                                                                            "IDENTITY_DELIVERY_UNAVAILABLE",
                                                                                            denied
                                                                                                    .getMessage());
                                                                                }
                                                                            });
                                                            try {
                                                                a.get(10, TimeUnit.SECONDS);
                                                                b.get(10, TimeUnit.SECONDS);
                                                            } catch (Exception e) {
                                                                throw new AssertionError(e);
                                                            }
                                                        }
                                                        assertEquals(1, admitted.get());
                                                        assertEquals(
                                                                1, used(c, PENDING_REGISTRATIONS));
                                                        assertEquals(
                                                                1,
                                                                used(other, ACTIONABLE_CHALLENGES));
                                                        assertEquals(
                                                                1, used(other, UNSENT_MAIL_JOBS));
                                                    });
                                        }));
    }

    @Test
    void recoveryUnknownAndIneligibleOwnersAllocateNothing() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            initialize(c);
                                            var flow = new Flow(c, NOW);
                                            flow.recovery("unknown@example.test");
                                            assertEquals(0, used(c, ACTIONABLE_CHALLENGES));
                                            assertEquals(0, used(c, UNSENT_MAIL_JOBS));
                                            flow.registration("pending@example.test");
                                            flow.recovery("pending@example.test");
                                            assertEquals(1, used(c, ACTIONABLE_CHALLENGES));
                                            assertEquals(1, used(c, UNSENT_MAIL_JOBS));
                                            var subject = subject(c, "pending@example.test");
                                            tx(c).executeWithoutResult(
                                                            s -> {
                                                                var r =
                                                                        c.getBean(
                                                                                IdentityRepository
                                                                                        .class);
                                                                var identity = r.byId(subject);
                                                                identity.activate();
                                                                r.save(identity);
                                                            });
                                            flow.recovery("PENDING@EXAMPLE.TEST");
                                            assertEquals(2, used(c, ACTIONABLE_CHALLENGES));
                                            assertEquals(2, used(c, UNSENT_MAIL_JOBS));
                                            assertEquals(
                                                    IdentityChallengePurpose.RECOVERY,
                                                    job(
                                                                    c,
                                                                    subject,
                                                                    IdentityChallengePurpose
                                                                            .RECOVERY)
                                                            .purpose());
                                        }));
    }
}
