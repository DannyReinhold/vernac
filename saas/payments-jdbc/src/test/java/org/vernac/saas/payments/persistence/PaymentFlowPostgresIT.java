// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.payments.persistence;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.vernac.saas.identity.domain.AccountId;
import org.vernac.saas.payments.*;
import org.vernac.saas.payments.domain.*;
import org.vernac.saas.payments.persistence.domain.*;
import org.vernac.saas.subscriptions.*;
import org.vernac.saas.subscriptions.domain.*;
@EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL", matches=".+")
class PaymentFlowPostgresIT {
    @TempDir Path root;
    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    static final PaymentProfileKey LOCAL = PaymentProfileKey.of("local");
    static final PaymentUri URL = PaymentUri.of("https://local.example.test/payment");
    static final BenefitKey EXPORTS = BenefitKey.of("exports");
    static OfferTerms offer(String key, long price, long allowance) {
        return OfferTerms.of(PlanKey.of(key), OfferRevision.of("v1"), MinorUnits.of(price), BillingCurrency.EUR, BillingInterval.MONTHLY, Benefits.of(Map.of(EXPORTS, Allowance.of(allowance))));
    }
    static final OfferTerms FREE = offer("free", 0, 3), PAID = offer("pro", 1200, 100);
    static PlanCatalog catalog() { return new PlanCatalog(List.of(FREE, PAID), PlanCatalog.id(FREE)); }
    static final class Time extends Clock {
        final AtomicReference<Instant> value = new AtomicReference<>(NOW);
        void advance(long seconds) { value.updateAndGet(now -> now.plusSeconds(seconds)); }
        public Instant instant() { return value.get(); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { if (!zone.equals(ZoneOffset.UTC)) throw new IllegalArgumentException(); return this; }
    }
    record Fixture(PaymentFlow flow, SubscriptionLedger subscriptions, NamedParameterJdbcTemplate jdbc, PaymentProvider provider) {}
    static Fixture fixture(AssertableApplicationContext context, Time time, AccountId allowed) {
        assertNull(context.getStartupFailure()); var jdbc = context.getBean(NamedParameterJdbcTemplate.class); var manager = context.getBean(PlatformTransactionManager.class);
        var subscriptions = new SubscriptionLedger(jdbc, manager, catalog(), time); var provider = context.getBean(PaymentProvider.class);
        // Synthetic authority/admission fixtures: full fresh Identity/HTTP owner integration remains separate acceptance.
        var flow = new PaymentFlow(jdbc, manager, Map.of(LOCAL, provider), catalog(), subscriptions,
                account -> { if (!account.equals(allowed)) throw new IllegalArgumentException(); }, profile -> { if (!profile.equals(LOCAL)) throw new IllegalArgumentException(); }, time);
        return new Fixture(flow, subscriptions, jdbc, provider);
    }
    static void settle(Fixture f, Time time) {
        assertTrue(f.flow.processOne(URL, URL)); assertTrue(f.flow.processOne(URL, URL)); assertTrue(f.flow.processOne(URL, URL));
        time.advance(30); assertTrue(f.flow.processOne(URL, URL)); assertTrue(f.flow.processOne(URL, URL));
    }
    @Test void actualLocalCheckoutAndRegisteredProofApplySubscriptionOnceAcrossRestartAndDuplicateCallbacks() throws Exception {
        var account = AccountId.create(); var request = PaymentOperationId.create(); var order = new AtomicReference<PaymentOrderId>(); var remote = new AtomicReference<String>(); var time = new Time();
        new PaymentFlowTestEnvironment(root).withDatabase(db -> {
            db.boot.run(context -> {
                var f = fixture(context, time, account); var pending = f.flow.checkout(account, request, LOCAL, PlanCatalog.id(PAID)); order.set(pending.id());
                assertEquals(FREE, f.subscriptions.current(account).renewalTerms());
                assertEquals(order.get(), f.flow.checkout(account, request, LOCAL, PlanCatalog.id(PAID)).id());
                settle(f, time); assertEquals(PaymentOrderStatus.APPLIED, f.flow.order(account, order.get()).status());
                assertEquals(PAID.benefits(), f.subscriptions.benefits(account));
                remote.set(f.jdbc.queryForObject("SELECT \"remote\" FROM \"org.vernac.saas.payments.persistence\".\"PaymentAttempt\" WHERE \"order\"=:order", Map.of("order", order.get().value()), String.class));
            });
            db.boot.run(context -> {
                var f = fixture(context, time, account);
                f.flow.webhook(LOCAL, PaymentNoticeBody.of("id=" + remote.get()), PaymentSignature.of(""));
                f.flow.webhook(LOCAL, PaymentNoticeBody.of("id=" + remote.get()), PaymentSignature.of(""));
                assertEquals(PaymentOrderStatus.APPLIED, f.flow.checkout(account, request, LOCAL, PlanCatalog.id(PAID)).status());
                assertEquals(1L, f.jdbc.queryForObject("SELECT count(*) FROM \"org.vernac.saas.subscriptions\".\"SubscriptionSettlement\"", Map.of(), Long.class));
                assertEquals(1L, f.jdbc.queryForObject("SELECT count(*) FROM \"org.vernac.saas.payments.local\".\"LocalPayment\"", Map.of(), Long.class));
            });
        });
    }
    @Test void foreignAdministrationUnknownOperationsAndCallbackTenantClaimsCannotSelectAnAccount() throws Exception {
        var account = AccountId.create(); var foreign = AccountId.create(); var time = new Time();
        new PaymentFlowTestEnvironment(root).withDatabase(db -> db.boot.run(context -> {
            var f = fixture(context, time, account);
            assertEquals(PaymentProcessingFailure.Code.ACCESS_DENIED, assertThrows(PaymentProcessingFailure.class,
                    () -> f.flow.checkout(foreign, PaymentOperationId.create(), LOCAL, PlanCatalog.id(PAID))).code());
            var customer = f.provider.createCustomer(PaymentOperationId.create());
            var unregistered = f.provider.checkout(CheckoutRequest.of(PaymentOperationId.create(), customer, MinorUnits.of(1200), BillingCurrency.EUR, URL, URL));
            assertEquals(PaymentProcessingFailure.Code.UNVERIFIED_RESOURCE, assertThrows(PaymentProcessingFailure.class,
                    () -> f.flow.webhook(LOCAL, PaymentNoticeBody.of("id=" + unregistered.id().encoded()), PaymentSignature.of(""))).code());
            assertThrows(PaymentFailure.class, () -> f.flow.webhook(LOCAL, PaymentNoticeBody.of("id=" + unregistered.id().encoded() + "&tenant=" + foreign.asString()), PaymentSignature.of("")));
            assertEquals(0L, f.jdbc.queryForObject("SELECT count(*) FROM \"org.vernac.saas.payments.persistence\".\"PaymentOrder\"", Map.of(), Long.class));
        }));
    }
    @Test void aDifferentSubmittedFormCannotCreateAParallelChargeAndUnsentCloseDoesNotChangeRenewalTerms() throws Exception {
        var account = AccountId.create(); var time = new Time();
        new PaymentFlowTestEnvironment(root).withDatabase(db -> db.boot.run(context -> {
            var f = fixture(context, time, account); var first = f.flow.checkout(account, PaymentOperationId.create(), LOCAL, PlanCatalog.id(PAID));
            assertEquals(PaymentProcessingFailure.Code.CONFLICT, assertThrows(PaymentProcessingFailure.class,
                    () -> f.flow.checkout(account, PaymentOperationId.create(), LOCAL, PlanCatalog.id(PAID))).code());
            f.flow.closeUnpaid(account, first.id()); assertEquals(FREE, f.subscriptions.current(account).renewalTerms());
            time.advance(1); var next = f.flow.checkout(account, PaymentOperationId.create(), LOCAL, PlanCatalog.id(PAID));
            assertNotEquals(first.id(), next.id());
        }));
    }
    @Test void fullRefundBeforeFirstApplicationCreatesTombstoneAndNeverGrantsPaidBenefits() throws Exception {
        refundReversesSettlement(false);
    }
    @Test void fullRefundAfterApplicationRevokesBenefitsAndDuplicateCallbacksRemainIdempotent() throws Exception {
        refundReversesSettlement(true);
    }
    private void refundReversesSettlement(boolean applyFirst) throws Exception {
        var account = AccountId.create(); var time = new Time();
        var order = new AtomicReference<PaymentOrderId>(); var remote = new AtomicReference<RemotePaymentId>();
        new PaymentFlowTestEnvironment(root).withDatabase(db -> {
            db.boot.run(context -> {
                var f = fixture(context, time, account);
                order.set(f.flow.checkout(account, PaymentOperationId.create(), LOCAL, PlanCatalog.id(PAID)).id());
                for (int i = 0; i < 3; i++) assertTrue(f.flow.processOne(URL, URL));
                time.advance(30); assertTrue(f.flow.processOne(URL, URL)); // Authoritative PAID, before APPLY.
                if (applyFirst) {
                    assertTrue(f.flow.processOne(URL, URL));
                    assertEquals(PAID.benefits(), f.subscriptions.benefits(account));
                }
                remote.set(RemotePaymentId.of(f.jdbc.queryForObject("SELECT \"remote\" FROM \"org.vernac.saas.payments.persistence\".\"PaymentAttempt\" WHERE \"order\"=:order", Map.of("order", order.get().value()), String.class)));
                f.provider.refund(RefundRequest.of(PaymentOperationId.create(), remote.get(), PAID.price(), BillingCurrency.EUR));
                f.flow.webhook(LOCAL, PaymentNoticeBody.of("id=" + remote.get().encoded()), PaymentSignature.of(""));
                assertTrue(f.flow.processOne(URL, URL));
                assertEquals(PaymentOrderStatus.CLOSED, f.flow.order(account, order.get()).status());
                assertNotEquals(PAID.benefits(), f.subscriptions.benefits(account));
            });
            db.boot.run(context -> {
                var f = fixture(context, time, account);
                f.flow.webhook(LOCAL, PaymentNoticeBody.of("id=" + remote.get().encoded()), PaymentSignature.of(""));
                assertTrue(f.flow.processOne(URL, URL));
                assertNotEquals(PAID.benefits(), f.subscriptions.benefits(account));
                assertEquals("REVOKED", f.jdbc.queryForObject("SELECT \"status\" FROM \"org.vernac.saas.subscriptions\".\"SubscriptionSettlement\"", Map.of(), String.class));
                assertEquals(1L, f.jdbc.queryForObject("SELECT count(*) FROM \"org.vernac.saas.subscriptions\".\"SubscriptionSettlement\"", Map.of(), Long.class));
            });
        });
    }

    @Test void durableRefundReservationsBoundPartialAndFullRefundsAcrossRestart() throws Exception {
        var account = AccountId.create(); var time = new Time(); var order = new AtomicReference<PaymentOrderId>();
        var partial = new AtomicReference<PaymentRefundId>(); var operation = PaymentOperationId.create();
        new PaymentFlowTestEnvironment(root).withDatabase(db -> {
            db.boot.run(context -> {
                var f = fixture(context, time, account);
                order.set(f.flow.checkout(account, PaymentOperationId.create(), LOCAL, PlanCatalog.id(PAID)).id()); settle(f, time);
                partial.set(f.flow.refund(account, order.get(), operation, MinorUnits.of(200)).id());
                assertEquals(partial.get(), f.flow.refund(account, order.get(), operation, MinorUnits.of(200)).id());
                assertEquals(PaymentProcessingFailure.Code.CONFLICT, assertThrows(PaymentProcessingFailure.class,
                        () -> f.flow.refund(account, order.get(), operation, MinorUnits.of(300))).code());
                assertEquals(PaymentProcessingFailure.Code.CONFLICT, assertThrows(PaymentProcessingFailure.class,
                        () -> f.flow.refund(account, order.get(), PaymentOperationId.create(), MinorUnits.of(1001))).code());
                assertTrue(f.flow.processRefundOne()); // Provider has refunded; reconciliation remains durable work.
            });
            db.boot.run(context -> {
                var f = fixture(context, time, account);
                assertTrue(f.flow.processRefundOne());
                assertEquals(RefundState.REFUNDED, f.flow.refund(account, partial.get()).state());
                assertEquals(PAID.benefits(), f.subscriptions.benefits(account));
                assertEquals(200, f.flow.order(account, order.get()).refunded().value());
                var full = f.flow.refund(account, order.get(), PaymentOperationId.create(), MinorUnits.of(1000));
                assertTrue(f.flow.processRefundOne()); assertTrue(f.flow.processRefundOne());
                assertTrue(f.flow.processOne(URL, URL));
                assertEquals(RefundState.REFUNDED, f.flow.refund(account, full.id()).state());
                assertEquals(PaymentOrderStatus.CLOSED, f.flow.order(account, order.get()).status());
                assertNotEquals(PAID.benefits(), f.subscriptions.benefits(account));
                assertFalse(f.flow.processRefundOne());
                assertEquals(2L, f.jdbc.queryForObject("SELECT count(*) FROM \"org.vernac.saas.payments.local\".\"LocalRefund\"", Map.of(), Long.class));
            });
        });
    }

    @Test void recurringWorkUsesVerifiedMandateAndOneCurrentPeriodWithoutChargingCancelledRenewal() throws Exception {
        var account = AccountId.create(); var time = new Time();
        new PaymentFlowTestEnvironment(root).withDatabase(db -> db.boot.run(context -> {
            var f = fixture(context, time, account);
            f.flow.checkout(account, PaymentOperationId.create(), LOCAL, PlanCatalog.id(PAID)); settle(f, time);
            assertFalse(f.flow.scheduleRenewalOne());
            time.value.set(Instant.parse("2026-02-01T00:00:00Z"));
            assertTrue(f.flow.scheduleRenewalOne()); assertFalse(f.flow.scheduleRenewalOne());
            assertTrue(f.flow.processOne(URL, URL)); assertTrue(f.flow.processOne(URL, URL));
            time.advance(30); assertTrue(f.flow.processOne(URL, URL)); assertTrue(f.flow.processOne(URL, URL));
            assertEquals(Instant.parse("2026-03-01T00:00:00Z"), f.subscriptions.current(account).period().end());
            assertEquals(2L, f.jdbc.queryForObject("SELECT count(*) FROM \"org.vernac.saas.payments.local\".\"LocalPayment\"", Map.of(), Long.class));
            f.subscriptions.cancel(account);
            time.value.set(Instant.parse("2026-03-01T00:00:00Z"));
            assertTrue(f.flow.scheduleRenewalOne()); assertFalse(f.flow.scheduleRenewalOne());
            assertEquals(SubscriptionStatus.ENDED, f.subscriptions.current(account).status());
            assertFalse(f.flow.processOne(URL, URL));
            assertEquals(2L, f.jdbc.queryForObject("SELECT count(*) FROM \"org.vernac.saas.payments.local\".\"LocalPayment\"", Map.of(), Long.class));
        }));
    }
    @Test void missedEntireBillingPeriodCreatesReviewWorkWithoutBackBilling() throws Exception {
        var account = AccountId.create(); var time = new Time();
        new PaymentFlowTestEnvironment(root).withDatabase(db -> db.boot.run(context -> {
            var f = fixture(context, time, account);
            f.flow.checkout(account, PaymentOperationId.create(), LOCAL, PlanCatalog.id(PAID)); settle(f, time);
            time.value.set(Instant.parse("2026-04-01T00:00:00Z"));
            assertTrue(f.flow.scheduleRenewalOne()); assertFalse(f.flow.processOne(URL, URL));
            assertEquals(1L, f.jdbc.queryForObject("SELECT count(*) FROM \"org.vernac.saas.payments.persistence\".\"PaymentOrder\" WHERE \"status\"='REVIEW'", Map.of(), Long.class));
            assertEquals(1L, f.jdbc.queryForObject("SELECT count(*) FROM \"org.vernac.saas.payments.local\".\"LocalPayment\"", Map.of(), Long.class));
            assertNotEquals(PAID.benefits(), f.subscriptions.benefits(account));
        }));
    }

}
