// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.vernac.saas.identity.persistence.IdentityMailRequestsPostgresIT.*;
import static org.vernac.saas.identity.persistence.JdbcIdentityCapacity.Resource.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.vernac.saas.identity.persistence.IdentityMailRequestsPostgresIT.Flow;
import org.vernac.saas.identity.persistence.domain.*;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@EnabledIfEnvironmentVariable(named = "VERNAC_PG_TEST_URL", matches = ".+")
class IdentityMailWorkerPostgresIT {
    @TempDir Path root;

    static final class Time extends Clock {
        final AtomicReference<Instant> value = new AtomicReference<>(NOW);

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId ignored) {
            return this;
        }

        public Instant instant() {
            return value.get();
        }

        void advance(long seconds) {
            value.updateAndGet(t -> t.plusSeconds(seconds));
        }
    }

    static IdentityDeliveryCrypto crypto(
            AssertableApplicationContext c, Time clock, String keyId, int n) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) n);
        return new IdentityDeliveryCrypto(
                keyId,
                Map.of(keyId, key),
                clock,
                new JdbcIdentityEncryptionAdmission(
                        jdbc(c), c.getBean(PlatformTransactionManager.class), clock));
    }

    static JdbcIdentityMailWorker worker(
            AssertableApplicationContext c, Time clock, JdbcIdentityMailWorker.Sender sender) {
        return worker(c, clock, crypto(c, clock, "key", 3), sender);
    }

    static JdbcIdentityMailWorker worker(
            AssertableApplicationContext c,
            Time clock,
            IdentityDeliveryCrypto crypto,
            JdbcIdentityMailWorker.Sender sender) {
        var worker =
                new JdbcIdentityMailWorker(
                        jdbc(c),
                        c.getBean(PlatformTransactionManager.class),
                        clock,
                        crypto,
                        sender);
        worker.initialize();
        return worker;
    }

    static JdbcIdentityMailWorker.Claim claim(JdbcIdentityMailWorker worker) {
        for (int i = 0; i < 3; i++) {
            var claim = worker.claim(100);
            if (claim != null) return claim;
        }
        throw new AssertionError("No live claim");
    }

    static JdbcIdentityMailWorker.Result pump(JdbcIdentityMailWorker worker) {
        for (int i = 0; i < 3; i++) {
            var result = worker.runOnce(100);
            if (result != JdbcIdentityMailWorker.Result.EMPTY) return result;
        }
        return JdbcIdentityMailWorker.Result.EMPTY;
    }

    @Test
    void submissionRunsOutsideTransactionsAndAcceptedPayloadIsErasedOnce() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db -> {
                            db.boot.run(
                                    c -> {
                                        initialize(c);
                                        new Flow(c, NOW).registration("original@example.test");
                                        Time clock = new Time();
                                        AtomicInteger sent = new AtomicInteger();
                                        var worker =
                                                worker(
                                                        c,
                                                        clock,
                                                        (content, context) -> {
                                                            assertFalse(
                                                                    TransactionSynchronizationManager
                                                                            .isActualTransactionActive());
                                                            assertTrue(
                                                                    TransactionSynchronizationManager
                                                                            .getResourceMap()
                                                                            .isEmpty());
                                                            assertEquals(
                                                                    "original@example.test",
                                                                    content.recipient());
                                                            assertEquals(
                                                                    context.locator(),
                                                                    content.locator());
                                                            sent.incrementAndGet();
                                                            return true;
                                                        });
                                        assertEquals(
                                                JdbcIdentityMailWorker.Result.ACCEPTED,
                                                pump(worker));
                                        assertEquals(1, sent.get());
                                        assertEquals(0, used(c, UNSENT_MAIL_JOBS));
                                        assertEquals(0, used(c, UNSENT_MAIL_BYTES));
                                        assertEquals(
                                                JdbcIdentityMailWorker.Result.EMPTY, pump(worker));
                                        assertEquals(1, sent.get());
                                    });
                            db.boot.run(
                                    c -> {
                                        var worker =
                                                worker(
                                                        c,
                                                        new Time(),
                                                        (message, context) -> {
                                                            fail("Already accepted mail replayed");
                                                            return false;
                                                        });
                                        assertEquals(
                                                JdbcIdentityMailWorker.Result.EMPTY, pump(worker));
                                    });
                        });
    }

    @Test
    void retriesKeepOneChargedEnvelopeAndRespectBackoffWithoutReencryption() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            initialize(c);
                                            new Flow(c, NOW).registration("retry@example.test");
                                            Time clock = new Time();
                                            AtomicInteger attempts = new AtomicInteger();
                                            var worker =
                                                    worker(
                                                            c,
                                                            clock,
                                                            (message, context) -> {
                                                                if (attempts.incrementAndGet() == 1)
                                                                    throw new IllegalStateException(
                                                                            "synthetic provider"
                                                                                    + " secret");
                                                                return true;
                                                            });
                                            assertEquals(
                                                    JdbcIdentityMailWorker.Result.RETRY,
                                                    pump(worker));
                                            assertEquals(1, used(c, UNSENT_MAIL_JOBS));
                                            var row =
                                                    job(
                                                            c,
                                                            subject(c, "retry@example.test"),
                                                            IdentityChallengePurpose.VERIFICATION);
                                            assertEquals(IdentityMailState.READY, row.state());
                                            assertEquals(1, row.attempts().amount());
                                            assertEquals(NOW.plusSeconds(60), row.due().instant());
                                            assertEquals(
                                                    JdbcIdentityMailWorker.Result.EMPTY,
                                                    pump(worker));
                                            assertEquals(1, attempts.get());
                                            clock.advance(60);
                                            assertEquals(
                                                    JdbcIdentityMailWorker.Result.ACCEPTED,
                                                    pump(worker));
                                            assertEquals(2, attempts.get());
                                            assertEquals(0, used(c, UNSENT_MAIL_JOBS));
                                            assertEquals(
                                                    1,
                                                    tx(c).<Long>execute(
                                                                    s ->
                                                                            c.getBean(
                                                                                            IdentityEncryptionKeyRepository
                                                                                                    .class)
                                                                                    .named(
                                                                                            IdentityEncryptionKeyName
                                                                                                    .of(
                                                                                                            "key"))
                                                                                    .orElseThrow()
                                                                                    .attempts()
                                                                                    .amount()));
                                        }));
    }

    @Test
    void crashedClaimsAreFencedAndExhaustionReclaimsOnlyAfterLeaseExpiry() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            initialize(c);
                                            new Flow(c, NOW).registration("crash@example.test");
                                            Time clock = new Time();
                                            var worker =
                                                    worker(
                                                            c,
                                                            clock,
                                                            (message, context) -> {
                                                                fail(
                                                                        "Claims in this crash"
                                                                            + " fixture never begin"
                                                                            + " transport");
                                                                return false;
                                                            });
                                            var old = claim(worker);
                                            clock.advance(46);
                                            var replacement = claim(worker);
                                            assertNotEquals(old.token, replacement.token);
                                            worker.settle(old, true);
                                            assertEquals(1, used(c, UNSENT_MAIL_JOBS));
                                            worker.settle(replacement, false);
                                            for (int i = 3; i <= 8; i++) {
                                                clock.advance(1800);
                                                var current = claim(worker);
                                                assertEquals(i, current.job.attempts().amount());
                                                if (i < 8) worker.settle(current, false);
                                            }
                                            assertEquals(1, used(c, UNSENT_MAIL_JOBS));
                                            assertEquals(0, worker.sweep(500));
                                            assertEquals(1, used(c, UNSENT_MAIL_JOBS));
                                            clock.advance(46);
                                            for (int i = 0; i < 3; i++) worker.sweep(500);
                                            assertEquals(0, used(c, UNSENT_MAIL_JOBS));
                                            assertEquals(0, used(c, UNSENT_MAIL_BYTES));
                                            worker.settle(old, true);
                                            assertEquals(0, used(c, UNSENT_MAIL_JOBS));
                                        }));
    }

    @Test
    void missingReadKeysPauseClaimsAndExpiryStillCleansChargedRows() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            initialize(c);
                                            new Flow(c, NOW).registration("keys@example.test");
                                            Time clock = new Time();
                                            AtomicInteger sent = new AtomicInteger();
                                            var worker =
                                                    worker(
                                                            c,
                                                            clock,
                                                            crypto(c, clock, "different", 4),
                                                            (message, context) -> {
                                                                sent.incrementAndGet();
                                                                return true;
                                                            });
                                            assertEquals(
                                                    JdbcIdentityMailWorker.Result.EMPTY,
                                                    pump(worker));
                                            assertEquals(0, sent.get());
                                            assertEquals(1, used(c, UNSENT_MAIL_JOBS));
                                            assertEquals(
                                                    0,
                                                    job(
                                                                    c,
                                                                    subject(c, "keys@example.test"),
                                                                    IdentityChallengePurpose
                                                                            .VERIFICATION)
                                                            .attempts()
                                                            .amount());
                                            clock.advance(24 * 3600);
                                            for (int i = 0; i < 3; i++) worker.sweep(500);
                                            assertEquals(0, used(c, UNSENT_MAIL_JOBS));
                                            for (int i = 0; i < 3; i++)
                                                worker.expireChallenges(500);
                                            assertEquals(0, used(c, ACTIONABLE_CHALLENGES));
                                            clock.advance(6 * 24 * 3600);
                                            worker.expireRegistrations(500);
                                            assertEquals(0, used(c, PENDING_REGISTRATIONS));
                                            assertEquals(0, sent.get());
                                        }));
    }

    @Test
    void consumedLeasedWorkStaysChargedUntilItsOwnerSettles() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            initialize(c);
                                            var flow = new Flow(c, NOW);
                                            flow.registration("consume@example.test");
                                            Time clock = new Time();
                                            var worker =
                                                    worker(
                                                            c,
                                                            clock,
                                                            (message, context) -> {
                                                                fail(
                                                                        "Cancelled work must not"
                                                                            + " begin transport");
                                                                return false;
                                                            });
                                            var claim = claim(worker);
                                            var content = content(flow.crypto, claim.job);
                                            tx(c).executeWithoutResult(
                                                            s ->
                                                                    assertTrue(
                                                                            new JdbcIdentityChallenges(
                                                                                            jdbc(c),
                                                                                            clock)
                                                                                    .consume(
                                                                                            content
                                                                                                    .deliveryToken(),
                                                                                            IdentityChallengePurpose
                                                                                                    .VERIFICATION,
                                                                                            user -> {})));
                                            assertEquals(1, used(c, UNSENT_MAIL_JOBS));
                                            assertEquals(0, worker.sweep(500));
                                            worker.settle(claim, false);
                                            assertEquals(0, used(c, UNSENT_MAIL_JOBS));
                                            assertEquals(0, used(c, UNSENT_MAIL_BYTES));
                                            worker.settle(claim, true);
                                            assertEquals(0, used(c, UNSENT_MAIL_JOBS));
                                        }));
    }

    @Test
    void twoApplicationContextsCannotSubmitOneLiveLeaseTwice() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            initialize(c);
                                            new Flow(c, NOW).registration("parallel@example.test");
                                            Time clock = new Time();
                                            AtomicInteger sent = new AtomicInteger();
                                            db.boot.run(
                                                    other -> {
                                                        var first =
                                                                worker(
                                                                        c,
                                                                        clock,
                                                                        (message, context) -> {
                                                                            sent.incrementAndGet();
                                                                            return true;
                                                                        });
                                                        var second =
                                                                worker(
                                                                        other,
                                                                        clock,
                                                                        (message, context) -> {
                                                                            sent.incrementAndGet();
                                                                            return true;
                                                                        });
                                                        try (var pool =
                                                                Executors.newFixedThreadPool(2)) {
                                                            var a = pool.submit(() -> pump(first));
                                                            var b = pool.submit(() -> pump(second));
                                                            try {
                                                                a.get(10, TimeUnit.SECONDS);
                                                                b.get(10, TimeUnit.SECONDS);
                                                            } catch (Exception e) {
                                                                throw new AssertionError(e);
                                                            }
                                                        }
                                                        assertEquals(1, sent.get());
                                                        assertEquals(0, used(c, UNSENT_MAIL_JOBS));
                                                        assertEquals(
                                                                0, used(other, UNSENT_MAIL_BYTES));
                                                    });
                                        }));
    }

    @Test
    void contaminatedContextsCannotClaimAndPollingWaitsForOutstandingDeliveryOnStop()
            throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            initialize(c);
                                            new Flow(c, NOW).registration("shutdown@example.test");
                                            Time clock = new Time();
                                            var entered = new CountDownLatch(1);
                                            var release = new CountDownLatch(1);
                                            var stopped = new CountDownLatch(1);
                                            var worker =
                                                    worker(
                                                            c,
                                                            clock,
                                                            (content, context) -> {
                                                                entered.countDown();
                                                                try {
                                                                    if (!release.await(
                                                                            5, TimeUnit.SECONDS))
                                                                        throw new AssertionError(
                                                                                "Sender not"
                                                                                    + " released");
                                                                } catch (
                                                                        InterruptedException
                                                                                interrupted) {
                                                                    throw new AssertionError(
                                                                            interrupted);
                                                                }
                                                                return true;
                                                            });
                                            var security =
                                                    org.springframework.security.core.context
                                                            .SecurityContextHolder.getContext();
                                            security.setAuthentication(
                                                    new org.springframework.security.authentication
                                                            .UsernamePasswordAuthenticationToken(
                                                            "synthetic", "unused"));
                                            try {
                                                safe(() -> worker.claim(10));
                                            } finally {
                                                org.springframework.security.core.context
                                                        .SecurityContextHolder.clearContext();
                                            }
                                            try (var ignored =
                                                    org.vernac.runtime.tenancy.TenantContext
                                                            .openVerified(
                                                                    org.vernac.runtime.tenancy
                                                                            .TenantId.of(
                                                                            UUID.randomUUID()))) {
                                                safe(() -> worker.claim(10));
                                            }
                                            var polling = new IdentityMailPolling(worker, () -> {});
                                            try {
                                                polling.start();
                                                try {
                                                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                                                    polling.stop(stopped::countDown);
                                                    assertFalse(
                                                            stopped.await(
                                                                    100, TimeUnit.MILLISECONDS));
                                                } catch (InterruptedException interrupted) {
                                                    throw new AssertionError(interrupted);
                                                } finally {
                                                    release.countDown();
                                                }
                                                try {
                                                    assertTrue(stopped.await(5, TimeUnit.SECONDS));
                                                } catch (InterruptedException interrupted) {
                                                    throw new AssertionError(interrupted);
                                                }
                                                assertFalse(polling.isRunning());
                                                assertEquals(0, used(c, UNSENT_MAIL_JOBS));
                                            } finally {
                                                release.countDown();
                                                polling.stop();
                                            }
                                        }));
    }

    @Test
    void corruptEnvelopesAndClosedOwnersNeverReachTheProvider() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            initialize(c);
                                            new Flow(c, NOW).registration("corrupt@example.test");
                                            Time clock = new Time();
                                            AtomicInteger sent = new AtomicInteger();
                                            tx(c).executeWithoutResult(
                                                            s ->
                                                                    jdbc(c).update(
                                                                                    "UPDATE"
                                                                                        + " \"org.vernac.saas.identity.persistence\".\"IdentityMailJob\""
                                                                                        + " SET envelope=:bad",
                                                                                    Map.of(
                                                                                            "bad",
                                                                                            "v1.key.AAAA")));
                                            // Maintain exact byte accounting in this deliberate
                                            // corruption fixture.
                                            tx(c).executeWithoutResult(
                                                            s -> {
                                                                var cap =
                                                                        new JdbcIdentityCapacity(
                                                                                jdbc(c));
                                                                long current =
                                                                        cap.usage(
                                                                                UNSENT_MAIL_BYTES);
                                                                cap.release(
                                                                        Map.of(
                                                                                UNSENT_MAIL_BYTES,
                                                                                current - 11));
                                                            });
                                            var worker =
                                                    worker(
                                                            c,
                                                            clock,
                                                            (message, context) -> {
                                                                sent.incrementAndGet();
                                                                return true;
                                                            });
                                            assertEquals(
                                                    JdbcIdentityMailWorker.Result.RETRY,
                                                    pump(worker));
                                            assertEquals(0, sent.get());
                                            tx(c).executeWithoutResult(
                                                            s -> {
                                                                var repo =
                                                                        c.getBean(
                                                                                IdentityRepository
                                                                                        .class);
                                                                var identity =
                                                                        repo.byId(
                                                                                subject(
                                                                                        c,
                                                                                        "corrupt@example.test"));
                                                                identity.close();
                                                                repo.save(identity);
                                                            });
                                            for (int i = 0; i < 3; i++) worker.sweep(500);
                                            assertEquals(0, used(c, UNSENT_MAIL_JOBS));
                                            assertEquals(0, sent.get());
                                        }));
    }

    @Test void providerRetryHintAndPermanentRejectionSettleOneEncryptedJobWithoutAnotherSend() throws Exception {
        new IdentityJdbcTestEnvironment(root).withDatabase(db -> db.boot.run(c -> {
            initialize(c); new Flow(c, NOW).registration("provider@example.test");
            Time clock = new Time(); AtomicInteger calls = new AtomicInteger();
            var provider = new org.vernac.saas.mail.domain.MailProvider() {
                public org.vernac.saas.mail.domain.MailProviderMode mode() { return org.vernac.saas.mail.domain.MailProviderMode.LIVE; }
                public org.vernac.saas.mail.domain.MailSubmission submit(org.vernac.saas.mail.domain.OutboundMail mail) {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
                    int attempt = calls.incrementAndGet();
                    assertEquals("provider@example.test", mail.recipient().encoded());
                    return org.vernac.saas.mail.domain.MailSubmission.of(
                        attempt == 1 ? org.vernac.saas.mail.domain.MailAcceptance.RETRY : org.vernac.saas.mail.domain.MailAcceptance.REJECTED,
                        org.vernac.saas.mail.domain.MailRetryDelay.of(attempt == 1 ? 1200 : 0));
                }
            };
            var sender = new IdentityProviderMailSender(provider, java.net.URI.create("https://identity.example.test"), false);
            var worker = worker(c, clock, sender);
            var envelope = job(c, subject(c, "provider@example.test"), IdentityChallengePurpose.VERIFICATION).envelope();
            assertEquals(JdbcIdentityMailWorker.Result.RETRY, pump(worker));
            var row = job(c, subject(c, "provider@example.test"), IdentityChallengePurpose.VERIFICATION);
            assertEquals(NOW.plusSeconds(1200), row.due().instant()); assertEquals(envelope, row.envelope());
            clock.advance(1199); assertEquals(JdbcIdentityMailWorker.Result.EMPTY, pump(worker)); assertEquals(1, calls.get());
            clock.advance(1); assertEquals(JdbcIdentityMailWorker.Result.REJECTED, pump(worker));
            assertEquals(0, used(c, UNSENT_MAIL_JOBS)); assertEquals(0, used(c, UNSENT_MAIL_BYTES));
            clock.advance(1800); assertEquals(JdbcIdentityMailWorker.Result.EMPTY, pump(worker)); assertEquals(2, calls.get());
        }));
    }
}
