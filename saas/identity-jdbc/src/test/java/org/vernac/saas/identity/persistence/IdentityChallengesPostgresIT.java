// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.vernac.saas.identity.persistence.JdbcIdentityCapacity.Resource.ACTIONABLE_CHALLENGES;
import static org.vernac.saas.identity.persistence.domain.IdentityChallengePurpose.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.vernac.saas.identity.domain.*;
import org.vernac.saas.identity.persistence.domain.*;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@EnabledIfEnvironmentVariable(named = "VERNAC_PG_TEST_URL", matches = ".+")
class IdentityChallengesPostgresIT {
    @TempDir Path root;
    static final Instant NOW = Instant.parse("2026-11-12T12:00:00.123456789Z");

    static TransactionTemplate tx(AssertableApplicationContext context) {
        assertNull(context.getStartupFailure());
        return new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    static NamedParameterJdbcTemplate jdbc(AssertableApplicationContext c) {
        return c.getBean(NamedParameterJdbcTemplate.class);
    }

    static JdbcIdentityChallenges challenges(AssertableApplicationContext c, Instant now) {
        return new JdbcIdentityChallenges(jdbc(c), Clock.fixed(now, ZoneOffset.UTC));
    }

    static UserId seed(AssertableApplicationContext c, long limit) {
        var user = Identity.create(IdentityStatus.PENDING_VERIFICATION);
        tx(c).executeWithoutResult(
                        s -> {
                            var ledger = new JdbcIdentityCapacity(jdbc(c));
                            var limits =
                                    new EnumMap<JdbcIdentityCapacity.Resource, Long>(
                                            JdbcIdentityCapacity.defaults());
                            limits.put(ACTIONABLE_CHALLENGES, limit);
                            ledger.initialize(limits);
                            c.getBean(IdentityRepository.class).save(user);
                        });
        return user.id();
    }

    static long used(AssertableApplicationContext c) {
        return tx(c).execute(s -> new JdbcIdentityCapacity(jdbc(c)).usage(ACTIONABLE_CHALLENGES));
    }

    @Test
    void purposeExpiryReplayReplacementAndBoundedCleanup() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            var user = seed(c, 1);
                                            var service = challenges(c, NOW);
                                            var first =
                                                    tx(c).execute(
                                                                    s ->
                                                                            service.issue(
                                                                                    user,
                                                                                    VERIFICATION));
                                            assertEquals(
                                                    JdbcIdentityChallenges.Outcome.ISSUED,
                                                    first.outcome());
                                            String token = first.token().deliveryValue();
                                            assertFalse(first.toString().contains(token));
                                            assertEquals(1, used(c));
                                            var stored =
                                                    tx(c).execute(
                                                                    s ->
                                                                            c.getBean(
                                                                                            IdentityChallengeRepository
                                                                                                    .class)
                                                                                    .existing(
                                                                                            IdentityChallengeId
                                                                                                    .of(
                                                                                                            first.token()
                                                                                                                    .locator()))
                                                                                    .orElseThrow());
                                            assertEquals(
                                                    first.token().verifier(),
                                                    stored.verifier().encoded());
                                            assertFalse(
                                                    stored.verifier()
                                                            .encoded()
                                                            .contains(token.substring(37)));
                                            String wrongSecret =
                                                    token.substring(0, 37)
                                                            + IdentityChallengeToken.create()
                                                                    .deliveryValue()
                                                                    .substring(37);
                                            assertEquals(
                                                    Boolean.FALSE,
                                                    tx(c).execute(
                                                                    s ->
                                                                            service.consume(
                                                                                    wrongSecret,
                                                                                    VERIFICATION,
                                                                                    id -> fail())));
                                            assertEquals(
                                                    JdbcIdentityChallenges.Outcome.COOLDOWN,
                                                    tx(c).execute(
                                                                    s ->
                                                                            challenges(
                                                                                            c,
                                                                                            NOW
                                                                                                    .plusSeconds(
                                                                                                            59))
                                                                                    .issue(
                                                                                            user,
                                                                                            VERIFICATION))
                                                            .outcome());
                                            assertEquals(
                                                    Boolean.FALSE,
                                                    tx(c).execute(
                                                                    s ->
                                                                            service.consume(
                                                                                    token,
                                                                                    RECOVERY,
                                                                                    id -> fail())));
                                            assertEquals(
                                                    Boolean.FALSE,
                                                    tx(c).execute(
                                                                    s ->
                                                                            challenges(
                                                                                            c,
                                                                                            NOW
                                                                                                    .plus(
                                                                                                            Duration
                                                                                                                    .ofHours(
                                                                                                                            24)))
                                                                                    .consume(
                                                                                            token,
                                                                                            VERIFICATION,
                                                                                            id ->
                                                                                                    fail())));
                                            assertEquals(
                                                    1, used(c)); // expiration alone never refunds
                                            // storage
                                            var replacement =
                                                    tx(c).execute(
                                                                    s ->
                                                                            challenges(
                                                                                            c,
                                                                                            NOW
                                                                                                    .plusSeconds(
                                                                                                            60))
                                                                                    .issue(
                                                                                            user,
                                                                                            VERIFICATION));
                                            assertEquals(
                                                    JdbcIdentityChallenges.Outcome.ISSUED,
                                                    replacement.outcome());
                                            assertEquals(1, used(c));
                                            assertEquals(
                                                    Boolean.FALSE,
                                                    tx(c).execute(
                                                                    s ->
                                                                            service.consume(
                                                                                    token,
                                                                                    VERIFICATION,
                                                                                    id -> fail())));
                                            var action = new AtomicInteger();
                                            assertEquals(
                                                    Boolean.TRUE,
                                                    tx(c).execute(
                                                                    s ->
                                                                            challenges(
                                                                                            c,
                                                                                            NOW
                                                                                                    .plusSeconds(
                                                                                                            60))
                                                                                    .consume(
                                                                                            replacement
                                                                                                    .token()
                                                                                                    .deliveryValue(),
                                                                                            VERIFICATION,
                                                                                            id -> {
                                                                                                assertEquals(
                                                                                                        user,
                                                                                                        id);
                                                                                                action
                                                                                                        .incrementAndGet();
                                                                                            })));
                                            assertEquals(
                                                    Boolean.FALSE,
                                                    tx(c).execute(
                                                                    s ->
                                                                            service.consume(
                                                                                    replacement
                                                                                            .token()
                                                                                            .deliveryValue(),
                                                                                    VERIFICATION,
                                                                                    id -> fail())));
                                            assertEquals(1, action.get());
                                            assertEquals(0, used(c));
                                            tx(c).executeWithoutResult(
                                                            s -> service.issue(user, VERIFICATION));
                                            assertEquals(
                                                    0,
                                                    (int)
                                                            tx(c).execute(
                                                                            s ->
                                                                                    challenges(
                                                                                                    c,
                                                                                                    NOW
                                                                                                            .plusSeconds(
                                                                                                                    1))
                                                                                            .removeExpired(
                                                                                                    1)));
                                            assertEquals(
                                                    0,
                                                    (int)
                                                            tx(c).execute(
                                                                            s ->
                                                                                    challenges(
                                                                                                    c,
                                                                                                    NOW.plus(
                                                                                                                    Duration
                                                                                                                            .ofHours(
                                                                                                                                    24))
                                                                                                            .minusNanos(
                                                                                                                    1))
                                                                                            .removeExpired(
                                                                                                    1)));
                                            assertEquals(
                                                    1,
                                                    (int)
                                                            tx(c).execute(
                                                                            s ->
                                                                                    challenges(
                                                                                                    c,
                                                                                                    NOW
                                                                                                            .plus(
                                                                                                                    Duration
                                                                                                                            .ofHours(
                                                                                                                                    24)))
                                                                                            .removeExpired(
                                                                                                    1)));
                                            assertEquals(
                                                    0,
                                                    (int)
                                                            tx(c).execute(
                                                                            s ->
                                                                                    challenges(
                                                                                                    c,
                                                                                                    NOW
                                                                                                            .plus(
                                                                                                                    Duration
                                                                                                                            .ofHours(
                                                                                                                                    24)))
                                                                                            .removeExpired(
                                                                                                    1)));
                                            assertEquals(0, used(c));
                                            assertThrows(
                                                    IllegalArgumentException.class,
                                                    () -> service.removeExpired(501));
                                            assertFalse(
                                                    service.consume(
                                                            "malformed",
                                                            VERIFICATION,
                                                            id -> fail()));
                                            assertThrows(
                                                    IllegalTransactionStateException.class,
                                                    () -> service.issue(user, VERIFICATION));
                                        }));
    }

    @Test
    void caughtActionFailuresPoisonTheWriterAndRestartRetainsTheToken() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db -> {
                            var token = new AtomicReference<String>();
                            var user = new AtomicReference<UserId>();
                            db.boot.run(
                                    c -> {
                                        user.set(seed(c, 2));
                                        token.set(
                                                tx(c).execute(
                                                                s ->
                                                                        challenges(c, NOW)
                                                                                .issue(
                                                                                        user.get(),
                                                                                        VERIFICATION))
                                                        .token()
                                                        .deliveryValue());
                                        assertThrows(
                                                UnexpectedRollbackException.class,
                                                () ->
                                                        tx(c).execute(
                                                                        s -> {
                                                                            assertThrows(
                                                                                    IllegalArgumentException
                                                                                            .class,
                                                                                    () ->
                                                                                            challenges(
                                                                                                            c,
                                                                                                            NOW)
                                                                                                    .consume(
                                                                                                            token
                                                                                                                    .get(),
                                                                                                            VERIFICATION,
                                                                                                            id -> {
                                                                                                                var
                                                                                                                        identity =
                                                                                                                                c.getBean(
                                                                                                                                                IdentityRepository
                                                                                                                                                        .class)
                                                                                                                                        .byId(
                                                                                                                                                id);
                                                                                                                identity
                                                                                                                        .activate();
                                                                                                                c.getBean(
                                                                                                                                IdentityRepository
                                                                                                                                        .class)
                                                                                                                        .save(
                                                                                                                                identity);
                                                                                                                throw new IllegalArgumentException(
                                                                                                                        "safe fixture"
                                                                                                                            + " failure");
                                                                                                            }));
                                                                            return true;
                                                                        }));
                                        assertEquals(
                                                IdentityStatus.PENDING_VERIFICATION,
                                                c.getBean(IdentityDirectory.class)
                                                        .status(user.get())
                                                        .orElseThrow());
                                        assertEquals(1, used(c));
                                    });
                            db.boot.run(
                                    c -> {
                                        assertEquals(
                                                Boolean.TRUE,
                                                tx(c).execute(
                                                                s ->
                                                                        challenges(c, NOW)
                                                                                .consume(
                                                                                        token.get(),
                                                                                        VERIFICATION,
                                                                                        id -> {})));
                                        assertEquals(0, used(c));
                                    });
                        });
    }

    @Test
    void twoContextsSerializeResendConsumptionAndLastSlot() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        a ->
                                                db.boot.run(
                                                        b -> {
                                                            var user = seed(a, 1);
                                                            var another =
                                                                    Identity.create(
                                                                            IdentityStatus
                                                                                    .PENDING_VERIFICATION);
                                                            tx(a).executeWithoutResult(
                                                                            s ->
                                                                                    a.getBean(
                                                                                                    IdentityRepository
                                                                                                            .class)
                                                                                            .save(
                                                                                                    another));
                                                            try (var pool =
                                                                    Executors.newFixedThreadPool(
                                                                            2)) {
                                                                var start = new CountDownLatch(1);
                                                                var f1 =
                                                                        pool.submit(
                                                                                () -> {
                                                                                    start.await();
                                                                                    return tx(a).execute(
                                                                                                    s ->
                                                                                                            challenges(
                                                                                                                            a,
                                                                                                                            NOW)
                                                                                                                    .issue(
                                                                                                                            user,
                                                                                                                            VERIFICATION));
                                                                                });
                                                                var f2 =
                                                                        pool.submit(
                                                                                () -> {
                                                                                    start.await();
                                                                                    return tx(b).execute(
                                                                                                    s ->
                                                                                                            challenges(
                                                                                                                            b,
                                                                                                                            NOW)
                                                                                                                    .issue(
                                                                                                                            another
                                                                                                                                    .id(),
                                                                                                                            VERIFICATION));
                                                                                });
                                                                start.countDown();
                                                                var x =
                                                                        f1.get(
                                                                                30,
                                                                                TimeUnit.SECONDS);
                                                                var y =
                                                                        f2.get(
                                                                                30,
                                                                                TimeUnit.SECONDS);
                                                                assertEquals(
                                                                        1,
                                                                        List.of(x, y).stream()
                                                                                .filter(
                                                                                        r ->
                                                                                                r
                                                                                                                .outcome()
                                                                                                        == JdbcIdentityChallenges
                                                                                                                .Outcome
                                                                                                                .ISSUED)
                                                                                .count());
                                                                var issued =
                                                                        x.outcome()
                                                                                        == JdbcIdentityChallenges
                                                                                                .Outcome
                                                                                                .ISSUED
                                                                                ? x
                                                                                : y;
                                                                var subject =
                                                                        x == issued
                                                                                ? user
                                                                                : another.id();
                                                                var actionCount =
                                                                        new AtomicInteger();
                                                                var both = new CountDownLatch(1);
                                                                var c1 =
                                                                        pool.submit(
                                                                                () -> {
                                                                                    both.await();
                                                                                    return tx(a).execute(
                                                                                                    s ->
                                                                                                            challenges(
                                                                                                                            a,
                                                                                                                            NOW)
                                                                                                                    .consume(
                                                                                                                            issued.token()
                                                                                                                                    .deliveryValue(),
                                                                                                                            VERIFICATION,
                                                                                                                            id ->
                                                                                                                                    actionCount
                                                                                                                                            .incrementAndGet()));
                                                                                });
                                                                var c2 =
                                                                        pool.submit(
                                                                                () -> {
                                                                                    both.await();
                                                                                    return tx(b).execute(
                                                                                                    s ->
                                                                                                            challenges(
                                                                                                                            b,
                                                                                                                            NOW)
                                                                                                                    .consume(
                                                                                                                            issued.token()
                                                                                                                                    .deliveryValue(),
                                                                                                                            VERIFICATION,
                                                                                                                            id ->
                                                                                                                                    actionCount
                                                                                                                                            .incrementAndGet()));
                                                                                });
                                                                both.countDown();
                                                                assertNotEquals(
                                                                        c1.get(
                                                                                30,
                                                                                TimeUnit.SECONDS),
                                                                        c2.get(
                                                                                30,
                                                                                TimeUnit.SECONDS));
                                                                assertEquals(1, actionCount.get());
                                                                assertEquals(0, used(a));
                                                                tx(a).executeWithoutResult(
                                                                                s ->
                                                                                        challenges(
                                                                                                        a,
                                                                                                        NOW)
                                                                                                .issue(
                                                                                                        subject,
                                                                                                        VERIFICATION));
                                                                var resend = new CountDownLatch(1);
                                                                var r1 =
                                                                        pool.submit(
                                                                                () -> {
                                                                                    resend.await();
                                                                                    return tx(a).execute(
                                                                                                    s ->
                                                                                                            challenges(
                                                                                                                            a,
                                                                                                                            NOW
                                                                                                                                    .plusSeconds(
                                                                                                                                            60))
                                                                                                                    .issue(
                                                                                                                            subject,
                                                                                                                            VERIFICATION));
                                                                                });
                                                                var r2 =
                                                                        pool.submit(
                                                                                () -> {
                                                                                    resend.await();
                                                                                    return tx(b).execute(
                                                                                                    s ->
                                                                                                            challenges(
                                                                                                                            b,
                                                                                                                            NOW
                                                                                                                                    .plusSeconds(
                                                                                                                                            60))
                                                                                                                    .issue(
                                                                                                                            subject,
                                                                                                                            VERIFICATION));
                                                                                });
                                                                resend.countDown();
                                                                var outcomes =
                                                                        List.of(
                                                                                r1.get(
                                                                                                30,
                                                                                                TimeUnit
                                                                                                        .SECONDS)
                                                                                        .outcome(),
                                                                                r2.get(
                                                                                                30,
                                                                                                TimeUnit
                                                                                                        .SECONDS)
                                                                                        .outcome());
                                                                assertTrue(
                                                                        outcomes.contains(
                                                                                JdbcIdentityChallenges
                                                                                        .Outcome
                                                                                        .ISSUED));
                                                                assertTrue(
                                                                        outcomes.contains(
                                                                                JdbcIdentityChallenges
                                                                                        .Outcome
                                                                                        .COOLDOWN));
                                                                assertEquals(1, used(b));
                                                            } catch (Exception e) {
                                                                throw new AssertionError(e);
                                                            }
                                                        })));
    }

    @Test
    void recoveryEligibilityExpiryAndIssueRollbackUseRealRecords() throws Exception {
        new IdentityJdbcTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        c -> {
                                            var user = seed(c, 2);
                                            assertEquals(
                                                    JdbcIdentityChallenges.Outcome.UNAVAILABLE,
                                                    tx(c).execute(
                                                                    s ->
                                                                            challenges(c, NOW)
                                                                                    .issue(
                                                                                            user,
                                                                                            RECOVERY))
                                                            .outcome());
                                            tx(c).executeWithoutResult(
                                                            s -> {
                                                                var row =
                                                                        c.getBean(
                                                                                        IdentityRepository
                                                                                                .class)
                                                                                .byId(user);
                                                                row.activate();
                                                                c.getBean(IdentityRepository.class)
                                                                        .save(row);
                                                            });
                                            assertEquals(
                                                    JdbcIdentityChallenges.Outcome.UNAVAILABLE,
                                                    tx(c).execute(
                                                                    s ->
                                                                            challenges(c, NOW)
                                                                                    .issue(
                                                                                            user,
                                                                                            VERIFICATION))
                                                            .outcome());
                                            var rolledBack = new AtomicReference<String>();
                                            tx(c).executeWithoutResult(
                                                            s -> {
                                                                rolledBack.set(
                                                                        challenges(c, NOW)
                                                                                .issue(
                                                                                        user,
                                                                                        RECOVERY)
                                                                                .token()
                                                                                .deliveryValue());
                                                                s.setRollbackOnly();
                                                            });
                                            assertEquals(0, used(c));
                                            assertEquals(
                                                    Boolean.FALSE,
                                                    tx(c).execute(
                                                                    s ->
                                                                            challenges(c, NOW)
                                                                                    .consume(
                                                                                            rolledBack
                                                                                                    .get(),
                                                                                            RECOVERY,
                                                                                            id ->
                                                                                                    fail())));
                                            var issued =
                                                    tx(c).execute(
                                                                    s ->
                                                                            challenges(c, NOW)
                                                                                    .issue(
                                                                                            user,
                                                                                            RECOVERY));
                                            String token = issued.token().deliveryValue();
                                            var expiry = NOW.plusSeconds(1800);
                                            assertEquals(
                                                    Boolean.FALSE,
                                                    tx(c).execute(
                                                                    s ->
                                                                            challenges(c, expiry)
                                                                                    .consume(
                                                                                            token,
                                                                                            RECOVERY,
                                                                                            id ->
                                                                                                    fail())));
                                            tx(c).executeWithoutResult(
                                                            s -> {
                                                                var row =
                                                                        c.getBean(
                                                                                        IdentityRepository
                                                                                                .class)
                                                                                .byId(user);
                                                                row.disable();
                                                                c.getBean(IdentityRepository.class)
                                                                        .save(row);
                                                            });
                                            assertEquals(
                                                    Boolean.FALSE,
                                                    tx(c).execute(
                                                                    s ->
                                                                            challenges(
                                                                                            c,
                                                                                            NOW
                                                                                                    .plusSeconds(
                                                                                                            1))
                                                                                    .consume(
                                                                                            token,
                                                                                            RECOVERY,
                                                                                            id ->
                                                                                                    fail())));
                                            assertEquals(1, used(c));
                                            var readOnly = tx(c);
                                            readOnly.setReadOnly(true);
                                            assertThrows(
                                                    IllegalTransactionStateException.class,
                                                    () ->
                                                            readOnly.execute(
                                                                    s ->
                                                                            challenges(c, NOW)
                                                                                    .consume(
                                                                                            token,
                                                                                            RECOVERY,
                                                                                            id ->
                                                                                                    fail())));
                                            tx(c).executeWithoutResult(
                                                            s -> {
                                                                assertEquals(
                                                                        1,
                                                                        challenges(c, expiry)
                                                                                .removeExpired(1));
                                                                s.setRollbackOnly();
                                                            });
                                            assertEquals(1, used(c));
                                            assertEquals(
                                                    1,
                                                    (int)
                                                            tx(c).execute(
                                                                            s ->
                                                                                    challenges(
                                                                                                    c,
                                                                                                    expiry)
                                                                                            .removeExpired(
                                                                                                    1)));
                                            assertEquals(0, used(c));
                                        }));
    }
}
