// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.subscriptions;

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
import org.springframework.transaction.support.TransactionTemplate;
import org.vernac.saas.identity.domain.AccountId;
import org.vernac.saas.subscriptions.domain.*;

@EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL", matches=".+")
class SubscriptionPostgresIT {
    @TempDir Path root;
    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    static final OfferTerms FREE = SubscriptionDomainTest.offer("free", "v1", 0, 3);
    static final OfferTerms PAID = SubscriptionDomainTest.offer("pro", "v1", 1200, 100);
    static final SubscriptionPeriod PERIOD = SubscriptionPeriod.of(NOW, BillingCalendar.end(NOW, BillingInterval.MONTHLY));
    static PlanCatalog catalog() { return new PlanCatalog(List.of(FREE, PAID), PlanCatalog.id(FREE)); }
    static SubscriptionLedger ledger(AssertableApplicationContext context) {
        assertNull(context.getStartupFailure());
        return new SubscriptionLedger(context.getBean(NamedParameterJdbcTemplate.class), context.getBean(PlatformTransactionManager.class), catalog(), Clock.fixed(NOW, ZoneOffset.UTC));
    }
    @Test void packagedModuleRetainsTermsSettlementAndAccountIsolationAcrossRestart() throws Exception {
        var account = AccountId.create(); var other = AccountId.create(); var id = new AtomicReference<SubscriptionId>();
        var key = SettlementKey.of("local:payment:one");
        new SubscriptionTestEnvironment(root).withDatabase(db -> {
            db.boot.run(context -> {
                var service = ledger(context); id.set(service.ensureFree(account).id());
                assertEquals(id.get(), service.ensureFree(account).id()); service.ensureFree(other);
                service.schedule(account, PlanCatalog.id(PAID));
                assertEquals(FREE.benefits(), service.benefits(account));
                var paid = service.confirm(account, key, PAID, PERIOD); assertEquals(PAID, paid.terms());
                assertEquals(PAID.benefits(), service.benefits(account)); assertEquals(FREE.benefits(), service.benefits(other));
            });
            db.boot.run(context -> {
                var service = ledger(context); assertEquals(id.get(), service.current(account).id());
                service.confirm(account, key, PAID, PERIOD);
                assertEquals(PERIOD, service.current(account).period());
                assertEquals(SubscriptionFailure.Code.SETTLEMENT_CONFLICT, assertThrows(SubscriptionFailure.class,
                        () -> service.confirm(other, key, PAID, PERIOD)).code());
                assertEquals(1L, context.getBean(NamedParameterJdbcTemplate.class).queryForObject("SELECT count(*) FROM \"org.vernac.saas.subscriptions\".\"SubscriptionSettlement\"", Map.of(), Long.class));
            });
            assertEquals(2, db.migrationCount());
        });
    }
    @Test void earlyRenewalPreservesCurrentAccessAndExplicitReopenKeepsOldReceipts() throws Exception {
        var account = AccountId.create();
        new SubscriptionTestEnvironment(root).withDatabase(db -> db.boot.run(context -> {
            var service = ledger(context); service.ensureFree(account); service.schedule(account, PlanCatalog.id(PAID));
            service.confirm(account, SettlementKey.of("initial"), PAID, PERIOD);
            var future = SubscriptionPeriod.of(PERIOD.end(), BillingCalendar.end(PERIOD.end(), BillingInterval.MONTHLY));
            assertEquals(SubscriptionFailure.Code.NOT_DUE, assertThrows(SubscriptionFailure.class,
                    () -> service.confirm(account, SettlementKey.of("future"), PAID, future)).code());
            assertEquals(PERIOD, service.current(account).period()); assertEquals(PAID.benefits(), service.benefits(account));
            var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            tx.executeWithoutResult(status -> assertEquals(PAID.benefits(), service.benefits(account)));
            service.close(account); service.reopenFree(account); assertEquals(FREE.benefits(), service.benefits(account));
            service.confirm(account, SettlementKey.of("initial"), PAID, PERIOD); // Old receipt cannot reapply after explicit reopen.
            assertEquals(FREE, service.current(account).terms());
            var alteredPaid = SubscriptionDomainTest.offer("pro", "v1", 1300, 100);
            assertThrows(SubscriptionFailure.class, () -> new SubscriptionLedger(context.getBean(NamedParameterJdbcTemplate.class),
                    context.getBean(PlatformTransactionManager.class), new PlanCatalog(List.of(FREE, alteredPaid), PlanCatalog.id(FREE)), Clock.systemUTC()));
        }));
    }
    @Test void registeredExplicitPurchaseInstallsItsTermsOnlyAtAtomicVerifiedSettlement() throws Exception {
        var account = AccountId.create();
        new SubscriptionTestEnvironment(root).withDatabase(db -> db.boot.run(context -> {
            var service = ledger(context); service.ensureFree(account);
            assertEquals(FREE, service.current(account).renewalTerms());
            service.confirmAuthorizedPurchase(account, SettlementKey.of("explicit-purchase"), PAID, PERIOD);
            assertEquals(PAID, service.current(account).terms());
            var laterPeriod = SubscriptionPeriod.of(PERIOD.end(), BillingCalendar.end(PERIOD.end(), BillingInterval.MONTHLY));
            assertEquals(SubscriptionFailure.Code.NOT_DUE, assertThrows(SubscriptionFailure.class,
                    () -> service.confirmAuthorizedPurchase(account, SettlementKey.of("future-plan"), FREE, laterPeriod)).code());
            assertEquals(PAID, service.current(account).renewalTerms());
        }));
    }
    @Test void authoritativeReversalFencesLateConfirmationAcrossIndependentInstances() throws Exception {
        var account = AccountId.create(); var key = SettlementKey.of("refunded-order");
        new SubscriptionTestEnvironment(root).withDatabase(db -> db.boot.run(first -> db.boot.run(second -> {
            var a = ledger(first); var b = ledger(second); a.ensureFree(account); a.schedule(account, PlanCatalog.id(PAID));
            var pool = Executors.newFixedThreadPool(2); var barrier = new CyclicBarrier(2);
            try {
                var confirm = pool.submit(() -> { barrier.await(); try { a.confirm(account, key, PAID, PERIOD); } catch (SubscriptionFailure reversed) { assertEquals(SubscriptionFailure.Code.INVALID_TRANSITION, reversed.code()); } return true; });
                var reverse = pool.submit(() -> { barrier.await(); b.revokeVerifiedSettlement(account, key, PAID, PERIOD); return true; });
                assertTrue(confirm.get(10, TimeUnit.SECONDS)); assertTrue(reverse.get(10, TimeUnit.SECONDS));
                assertTrue(Benefits.allowance(a.benefits(account), BenefitKey.of("exports")).maximum() <= 3, "A reversal must prevent paid benefits while preserving an already-active free agreement if reversal wins first");
                assertEquals(SubscriptionFailure.Code.INVALID_TRANSITION, assertThrows(SubscriptionFailure.class, () -> a.confirm(account, key, PAID, PERIOD)).code());
                b.revokeVerifiedSettlement(account, key, PAID, PERIOD);
                var before = AccountId.create(); var beforeKey = SettlementKey.of("reverse-before-explicit");
                a.ensureFree(before); b.revokeVerifiedSettlement(before, beforeKey, PAID, PERIOD);
                assertEquals(FREE.benefits(), a.benefits(before));
                assertThrows(SubscriptionFailure.class, () -> a.confirmAuthorizedPurchase(before, beforeKey, PAID, PERIOD));
                var after = AccountId.create(); var afterKey = SettlementKey.of("reverse-after-explicit");
                a.ensureFree(after); a.confirmAuthorizedPurchase(after, afterKey, PAID, PERIOD); b.revokeVerifiedSettlement(after, afterKey, PAID, PERIOD);
                assertEquals(0, Benefits.allowance(a.benefits(after), BenefitKey.of("exports")).maximum());
                assertEquals(3L, jdbc(first).queryForObject("SELECT count(*) FROM \"org.vernac.saas.subscriptions\".\"SubscriptionSettlement\" WHERE \"status\"='REVOKED'", Map.of(), Long.class));
            } catch (Exception failure) { throw new AssertionError(failure); } finally { pool.shutdownNow(); }
        })));
    }
    private static NamedParameterJdbcTemplate jdbc(AssertableApplicationContext context) { return context.getBean(NamedParameterJdbcTemplate.class); }
    @Test void explicitPaidResumptionStartsAtTheNewConfirmedWindowWithoutBackbilling() throws Exception {
        var account = AccountId.create(); var key = SettlementKey.of("resumed");
        new SubscriptionTestEnvironment(root).withDatabase(db -> db.boot.run(context -> {
            var service = ledger(context); service.ensureFree(account); service.schedule(account, PlanCatalog.id(PAID)); service.confirm(account, SettlementKey.of("initial"), PAID, PERIOD);
            Instant resume = PERIOD.end().plusSeconds(20 * 86400);
            var later = new SubscriptionLedger(jdbc(context), context.getBean(PlatformTransactionManager.class), catalog(), Clock.fixed(resume, ZoneOffset.UTC));
            later.expire(account); var paid = SubscriptionPeriod.of(resume, BillingCalendar.end(resume, BillingInterval.MONTHLY));
            assertEquals(paid, later.confirm(account, key, PAID, paid).period());
            later.revokeVerifiedSettlement(account, SettlementKey.of("initial"), PAID, PERIOD);
            assertEquals(PAID.benefits(), later.benefits(account)); // Refunding an older period cannot revoke a newer one.
        }));
    }
    @Test void independentInstancesSerializeFirstProvisioningAndCompetingSettlements() throws Exception {
        var account = AccountId.create();
        new SubscriptionTestEnvironment(root).withDatabase(db -> db.boot.run(first -> db.boot.run(second -> {
            var a = ledger(first); var b = ledger(second); var pool = Executors.newFixedThreadPool(2);
            try {
                var barrier = new CyclicBarrier(2);
                var fa = pool.submit(() -> { barrier.await(); return a.ensureFree(account).id(); });
                var fb = pool.submit(() -> { barrier.await(); return b.ensureFree(account).id(); });
                assertEquals(fa.get(10, TimeUnit.SECONDS), fb.get(10, TimeUnit.SECONDS));
                a.schedule(account, PlanCatalog.id(PAID));
                var next = new CyclicBarrier(2);
                Callable<Boolean> ca = () -> { next.await(); try { a.confirm(account, SettlementKey.of("local:a"), PAID, PERIOD); return true; } catch (SubscriptionFailure denied) { return false; } };
                Callable<Boolean> cb = () -> { next.await(); try { b.confirm(account, SettlementKey.of("local:b"), PAID, PERIOD); return true; } catch (SubscriptionFailure denied) { return false; } };
                var one = pool.submit(ca); var two = pool.submit(cb);
                assertNotEquals(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
                assertEquals(PAID, a.current(account).terms());
                assertEquals(1L, first.getBean(NamedParameterJdbcTemplate.class).queryForObject("SELECT count(*) FROM \"org.vernac.saas.subscriptions\".\"SubscriptionSettlement\"", Map.of(), Long.class));
            } catch (Exception failed) { throw new AssertionError(failed); }
            finally { pool.shutdownNow(); }
        })));
    }
    @Test void failedReceiptInsertRollsBackEntitlementsAndCanRetryAfterRestart() throws Exception {
        var account = AccountId.create(); var key = SettlementKey.of("local:retry");
        new SubscriptionTestEnvironment(root).withDatabase(db -> {
            db.boot.run(context -> { var service = ledger(context); service.ensureFree(account); service.schedule(account, PlanCatalog.id(PAID)); });
            try (var admin = DriverManager.getConnection(db.url, db.credentials); var sql = admin.createStatement()) {
                sql.execute("REVOKE INSERT ON \"org.vernac.saas.subscriptions\".\"SubscriptionSettlement\" FROM " + db.role);
                db.boot.run(context -> {
                    var service = ledger(context);
                    assertEquals(SubscriptionFailure.Code.UNAVAILABLE, assertThrows(SubscriptionFailure.class, () -> service.confirm(account, key, PAID, PERIOD)).code());
                    assertEquals(FREE, service.current(account).terms());
                });
                sql.execute("GRANT INSERT ON \"org.vernac.saas.subscriptions\".\"SubscriptionSettlement\" TO " + db.role);
            }
            db.boot.run(context -> { var service = ledger(context); assertEquals(PAID, service.confirm(account, key, PAID, PERIOD).terms()); });
        });
    }
    @Test void catalogDriftAndAmbientTransactionsFailClosedWithoutChangingCustomerTerms() throws Exception {
        var account = AccountId.create();
        new SubscriptionTestEnvironment(root).withDatabase(db -> db.boot.run(context -> {
            var service = ledger(context); service.ensureFree(account);
            var jdbc = context.getBean(NamedParameterJdbcTemplate.class); var manager = context.getBean(PlatformTransactionManager.class);
            var changed = SubscriptionDomainTest.offer("free", "v1", 0, 99);
            assertEquals(SubscriptionFailure.Code.CONFIGURATION, assertThrows(SubscriptionFailure.class,
                    () -> new SubscriptionLedger(jdbc, manager, new PlanCatalog(List.of(changed), PlanCatalog.id(changed)), Clock.systemUTC())).code());
            new TransactionTemplate(manager).executeWithoutResult(status -> assertEquals(SubscriptionFailure.Code.CONFIGURATION,
                    assertThrows(SubscriptionFailure.class, () -> service.ensureFree(AccountId.create())).code()));
            assertEquals(FREE, service.current(account).terms());
            assertThrows(RuntimeException.class, () -> jdbc.getJdbcTemplate().execute("CREATE TABLE \"org.vernac.saas.subscriptions\".forbidden(id integer)"));
        }));
    }
}
