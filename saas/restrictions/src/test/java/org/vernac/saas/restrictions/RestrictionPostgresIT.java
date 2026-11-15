// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.restrictions;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.vernac.saas.identity.domain.AccountId;
import org.vernac.saas.subscriptions.domain.*;
import org.vernac.saas.restrictions.domain.*;

@EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL", matches=".+")
class RestrictionPostgresIT {
    @TempDir Path root;
    static final Instant NOW = RestrictionDomainTest.NOW;
    static final UsageWindow WINDOW = RestrictionDomainTest.WINDOW;
    static final BenefitKey EXPORTS = RestrictionDomainTest.EXPORTS;
    static RestrictionLedger ledger(AssertableApplicationContext context, long limit, Instant now) {
        assertNull(context.getStartupFailure());
        return new RestrictionLedger(context.getBean(NamedParameterJdbcTemplate.class), context.getBean(PlatformTransactionManager.class),
                (account, benefit) -> Allowance.of(limit), Clock.fixed(now, ZoneOffset.UTC));
    }
    static RestrictionLedger.QuotaScope scope(AccountId account) { return new RestrictionLedger.QuotaScope(account, EXPORTS, WINDOW); }
    static NamedParameterJdbcTemplate jdbc(AssertableApplicationContext context) { return context.getBean(NamedParameterJdbcTemplate.class); }
    static void probe(RestrictionTestEnvironment.Database db) throws Exception {
        try (var admin = DriverManager.getConnection(db.url, db.credentials); var sql = admin.createStatement()) {
            sql.execute("CREATE TABLE \"org.vernac.saas.restrictions\".probe(id UUID PRIMARY KEY, account UUID NOT NULL)");
            sql.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON \"org.vernac.saas.restrictions\".probe TO " + db.role);
        }
    }
    static long count(AssertableApplicationContext context, AccountId account) {
        return Objects.requireNonNull(jdbc(context).queryForObject("SELECT count(*) FROM \"org.vernac.saas.restrictions\".probe WHERE account=:account", Map.of("account", account.value()), Long.class));
    }
    static String insert(AssertableApplicationContext context, AccountId account) {
        jdbc(context).update("INSERT INTO \"org.vernac.saas.restrictions\".probe VALUES (:id,:account)", Map.of("id", UUID.randomUUID(), "account", account.value())); return "saved";
    }
    @Test void localCallbackRunsOnceAndBusinessFailureRollsBackUsageAndProductWrite() throws Exception {
        var account = AccountId.create(); var calls = new AtomicInteger();
        new RestrictionTestEnvironment(root).withDatabase(db -> { probe(db); db.boot.run(context -> {
            var service = ledger(context, 1, NOW);
            assertThrows(IllegalArgumentException.class, () -> service.local(scope(account), Units.of(1), () -> {
                calls.incrementAndGet(); insert(context, account); throw new IllegalArgumentException("application denial");
            }));
            assertEquals(0, count(context, account));
            assertEquals("saved", service.local(scope(account), Units.of(1), () -> { calls.incrementAndGet(); return insert(context, account); }));
            assertEquals(RestrictionFailure.Code.EXHAUSTED, assertThrows(RestrictionFailure.class,
                    () -> service.local(scope(account), Units.of(1), () -> calls.incrementAndGet())).code());
            assertEquals(2, calls.get()); assertEquals(1, count(context, account));
        }); });
    }
    @Test void independentInstancesCannotAllocateTheSameLastCapacitySlotAndDeletionWorksAfterDowngrade() throws Exception {
        var account = AccountId.create();
        new RestrictionTestEnvironment(root).withDatabase(db -> { probe(db); db.boot.run(a -> db.boot.run(b -> {
            var one = ledger(a, 1, NOW); var two = ledger(b, 1, NOW); var pool = Executors.newFixedThreadPool(2); var barrier = new CyclicBarrier(2);
            try {
                Callable<Boolean> first = () -> { barrier.await(); try { one.capacity(account, EXPORTS, Units.of(1), () -> count(a, account), () -> insert(a, account)); return true; } catch (RestrictionFailure denied) { return false; } };
                Callable<Boolean> second = () -> { barrier.await(); try { two.capacity(account, EXPORTS, Units.of(1), () -> count(b, account), () -> insert(b, account)); return true; } catch (RestrictionFailure denied) { return false; } };
                var fa = pool.submit(first); var fb = pool.submit(second); assertNotEquals(fa.get(10, TimeUnit.SECONDS), fb.get(10, TimeUnit.SECONDS));
                assertEquals(1, count(a, account));
                ledger(a, 0, NOW).reduceCapacity(account, () -> count(a, account), () -> jdbc(a).update("DELETE FROM \"org.vernac.saas.restrictions\".probe WHERE account=:account", Map.of("account", account.value())));
                assertEquals(0, count(a, account));
            } catch (Exception failure) { throw new AssertionError(failure); } finally { pool.shutdownNow(); }
        })); });
    }
    @Test void externalUnknownSurvivesRestartAndRolloverWithoutExecutingCallbackAgain() throws Exception {
        var account = AccountId.create(); var key = UsageOperationKey.of("provider:one"); var calls = new AtomicInteger();
        new RestrictionTestEnvironment(root).withDatabase(db -> {
            db.boot.run(context -> {
                var service = ledger(context, 1, NOW);
                var result = service.external(scope(account), key, Units.of(1), () -> {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); calls.incrementAndGet(); throw new IllegalStateException("untrusted provider payload");
                });
                assertEquals(RestrictionLedger.ExternalState.IN_DOUBT, result.state());
                assertEquals(RestrictionFailure.Code.EXHAUSTED, assertThrows(RestrictionFailure.class,
                        () -> service.reserve(scope(account), UsageOperationKey.of("provider:two"), Units.of(1))).code());
            });
            db.boot.run(context -> {
                var service = ledger(context, 1, WINDOW.end());
                assertEquals(RestrictionLedger.ExternalState.ALREADY_RECORDED, service.external(scope(account), key, Units.of(1), () -> calls.incrementAndGet()).state());
                assertEquals(ReservationStatus.CONFIRMED, service.confirm(account, key).status());
                assertEquals(ReservationStatus.CONFIRMED, service.confirm(account, key).status());
                assertThrows(RestrictionFailure.class, () -> service.releaseKnownUnused(account, key));
                var next = new RestrictionLedger.QuotaScope(account, EXPORTS, UsageWindow.of(WINDOW.end(), WINDOW.end().plusSeconds(60)));
                assertEquals(RestrictionFailure.Code.OPERATION_CONFLICT, assertThrows(RestrictionFailure.class,
                        () -> service.reserve(next, key, Units.of(1))).code());
                assertTrue(service.reserve(next, UsageOperationKey.of("new-period"), Units.of(1)).created());
                assertEquals(1, calls.get());
            });
        });
    }
    @Test void boundedReconciliationAndPreDispatchResizeNeverRepeatOrUnderchargeAnAttempt() throws Exception {
        var account = AccountId.create(); var key = UsageOperationKey.of("adjust");
        new RestrictionTestEnvironment(root).withDatabase(db -> db.boot.run(context -> {
            var service = ledger(context, 3, NOW);
            service.reserve(scope(account), key, Units.of(1));
            assertEquals(2, service.resizeBeforeDispatch(account, key, Units.of(2)).amount().value());
            service.local(scope(account), Units.of(1), () -> "local");
            assertEquals(RestrictionFailure.Code.EXHAUSTED, assertThrows(RestrictionFailure.class,
                    () -> service.resizeBeforeDispatch(account, key, Units.of(3))).code());
            var page = service.pending(account, Optional.empty(), 1); assertEquals(1, page.size()); assertEquals(2, page.getFirst().amount().value());
            assertTrue(service.pending(account, Optional.of(page.getFirst().id()), 1).isEmpty());
            assertTrue(service.pending(AccountId.create(), Optional.empty(), 1).isEmpty());
            assertTrue(service.beginDispatch(account, key)); assertFalse(service.beginDispatch(account, key));
            assertEquals(ReservationStatus.IN_DOUBT, service.pending(account, Optional.empty(), 1).getFirst().status());
            assertThrows(RestrictionFailure.class, () -> service.resizeBeforeDispatch(account, key, Units.of(1)));
            service.confirm(account, key); assertTrue(service.pending(account, Optional.empty(), 10).isEmpty());
            assertThrows(RestrictionFailure.class, () -> service.local(scope(account), Units.of(1), () -> "exhausted"));
        }));
    }
    @Test void knownUnusedReleaseAndNestedAccountPinPreserveAtomicBudgets() throws Exception {
        var account = AccountId.create(); var other = AccountId.create();
        new RestrictionTestEnvironment(root).withDatabase(db -> db.boot.run(context -> {
            var service = ledger(context, 2, NOW); var key = UsageOperationKey.of("unused");
            service.reserve(scope(account), key, Units.of(2)); service.releaseKnownUnused(account, key); service.releaseKnownUnused(account, key);
            service.local(scope(account), Units.of(1), () -> service.local(scope(account), Units.of(1), () -> "ok"));
            assertThrows(RestrictionFailure.class, () -> service.local(scope(account), Units.of(1), () -> "denied"));
            var second = BenefitKey.of("documents");
            assertThrows(RestrictionFailure.class, () -> service.local(new RestrictionLedger.QuotaScope(account, second, WINDOW), Units.of(1),
                    () -> service.local(scope(other), Units.of(1), () -> "foreign")));
            // The failed nested account switch rolled back its outer consumption.
            service.local(new RestrictionLedger.QuotaScope(account, second, WINDOW), Units.of(2), () -> "ok");
            assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
        }));
    }
}
