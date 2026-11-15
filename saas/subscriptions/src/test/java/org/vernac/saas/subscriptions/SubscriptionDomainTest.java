// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.subscriptions;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.vernac.saas.identity.domain.AccountId;
import org.vernac.saas.subscriptions.domain.*;

class SubscriptionDomainTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-02-01T00:00:00Z");
    private static final BenefitKey EXPORTS = BenefitKey.of("exports");
    static OfferTerms offer(String key, String revision, long price, long allowance) {
        return OfferTerms.of(PlanKey.of(key), OfferRevision.of(revision), MinorUnits.of(price), BillingCurrency.EUR,
                BillingInterval.MONTHLY, Benefits.of(Map.of(EXPORTS, Allowance.of(allowance))));
    }
    static Subscription awaiting(OfferTerms terms) {
        return Subscription.create(AccountId.create(), terms, SubscriptionStatus.AWAITING_PAYMENT,
                SubscriptionPeriod.of(START, END), RenewalStatus.ENABLED, terms);
    }
    @Test void freeAndPaidActivationAreDistinctAndEffectiveWindowsAreHalfOpen() {
        var free = awaiting(offer("free", "v1", 0, 3));
        assertFalse(free.effectiveAt(START)); free.activateFree();
        assertFalse(free.effectiveAt(START.minusNanos(1))); assertTrue(free.effectiveAt(START));
        assertTrue(free.effectiveAt(END.minusNanos(1))); assertFalse(free.effectiveAt(END));
        assertEquals(0, Benefits.allowance(free.benefitsAt(END), EXPORTS).maximum());
        var terms = offer("pro", "v1", 1200, -1); var paid = awaiting(terms);
        assertThrows(SubscriptionFailure.class, paid::activateFree);
        assertThrows(SubscriptionFailure.class, () -> paid.settleInitial(terms, START, END.plusSeconds(1)));
        assertEquals(SubscriptionStatus.AWAITING_PAYMENT, paid.status());
        paid.settleInitial(terms, START, END);
        assertEquals(-1, Benefits.allowance(paid.benefitsAt(START), EXPORTS).maximum());
        assertThrows(SubscriptionFailure.class, () -> paid.settleInitial(terms, START, END));
    }
    @Test void catalogDeploymentsNeverRewritePurchasedTermsAndChangesWaitForSettledBoundary() {
        var old = offer("pro", "v1", 1200, 100); var next = offer("pro", "v2", 1500, 200);
        var catalog = new PlanCatalog(List.of(offer("free", "v1", 0, 3), next),
                new PlanCatalog.OfferId(PlanKey.of("free"), OfferRevision.of("v1")));
        catalog.requireCompatible(old); // Historical revision may be withdrawn from sale.
        var sub = awaiting(old); sub.settleInitial(old, START, END); sub.scheduleChange(next);
        assertEquals(old, sub.terms()); assertEquals(100, Benefits.allowance(sub.benefitsAt(START), EXPORTS).maximum());
        assertThrows(SubscriptionFailure.class, () -> sub.settleRenewal(old, END, END.plusSeconds(100)));
        assertEquals(old, sub.terms());
        sub.settleRenewal(next, END, Instant.parse("2026-03-01T00:00:00Z"));
        assertEquals(next, sub.terms()); assertFalse(sub.changeScheduled());
        assertThrows(SubscriptionFailure.class, () -> sub.settleRenewal(next, END, END.plusSeconds(100)));
    }
    @Test void cancellationPreservesPaidAccessButNeverRenewsOrFallsBackImplicitly() {
        var terms = offer("pro", "v1", 1200, 100); var sub = awaiting(terms);
        sub.settleInitial(terms, START, END); sub.scheduleChange(offer("free", "v1", 0, 3)); sub.cancelRenewal();
        assertFalse(sub.changeScheduled()); assertTrue(sub.effectiveAt(END.minusSeconds(1)));
        assertThrows(SubscriptionFailure.class, () -> sub.lapse(END.minusNanos(1)));
        assertThrows(SubscriptionFailure.class, () -> sub.settleRenewal(terms, END, END.plusSeconds(100)));
        sub.lapse(END); assertEquals(SubscriptionStatus.ENDED, sub.status());
        assertThrows(SubscriptionFailure.class, () -> sub.resumeRenewal(END));
        assertEquals(0, Benefits.allowance(sub.benefitsAt(END), EXPORTS).maximum());
    }
    @Test void nonpaymentSuspendsAndLateVerifiedSettlementRestoresOnlyItsExactPeriod() {
        var terms = offer("pro", "v1", 1200, 100); var sub = awaiting(terms); sub.settleInitial(terms, START, END);
        sub.lapse(END); assertEquals(SubscriptionStatus.SUSPENDED, sub.status()); assertFalse(sub.effectiveAt(END));
        sub.settleRenewal(terms, END, Instant.parse("2026-03-01T00:00:00Z"));
        assertTrue(sub.effectiveAt(END)); sub.end(); assertFalse(sub.effectiveAt(END));
        assertThrows(SubscriptionFailure.class, () -> sub.settleRenewal(terms, sub.period().end(), sub.period().end().plusSeconds(100)));
    }
    @Test void freeToPaidKeepsFreeBenefitsUntilExactConfirmedFirstPeriod() {
        var free = offer("free", "v1", 0, 3); var paid = offer("pro", "v1", 1200, 100);
        var sub = awaiting(free); sub.activateFree(); sub.scheduleChange(paid);
        assertEquals(3, Benefits.allowance(sub.benefitsAt(START), EXPORTS).maximum());
        assertThrows(SubscriptionFailure.class, () -> sub.settleUpgrade(paid, START.minusSeconds(1), END));
        assertThrows(SubscriptionFailure.class, () -> sub.settleUpgrade(paid, START, END.plusSeconds(1)));
        sub.settleUpgrade(paid, START, END);
        assertEquals(100, Benefits.allowance(sub.benefitsAt(START), EXPORTS).maximum());
        assertThrows(SubscriptionFailure.class, () -> sub.settleUpgrade(paid, START, END));
    }
    @Test void billingCalendarUsesUtcCalendarBoundariesIncludingLeapYears() {
        var january = Instant.parse("2028-01-31T12:34:56Z");
        assertEquals(Instant.parse("2028-02-29T12:34:56Z"), BillingCalendar.end(january, BillingInterval.MONTHLY));
        assertEquals(Instant.parse("2029-02-28T12:34:56Z"), BillingCalendar.end(Instant.parse("2028-02-29T12:34:56Z"), BillingInterval.YEARLY));
        assertThrows(RuntimeException.class, () -> SubscriptionPeriod.of(END, START));
    }
    @Test void benefitsAreCanonicalFiniteImmutableAndMissingMeansZeroRatherThanUnlimited() {
        var snapshot = Benefits.of(Map.of(BenefitKey.of("z"), Allowance.of(-1), BenefitKey.of("a"), Allowance.of(Long.MAX_VALUE)));
        assertEquals("a=9223372036854775807,z=-1", snapshot.encoded());
        assertEquals(0, Benefits.allowance(snapshot, BenefitKey.of("missing")).maximum());
        assertThrows(UnsupportedOperationException.class, () -> Benefits.entries(snapshot).clear());
        for (String invalid : List.of("a=1,a=2", "z=1,a=2", "a=-2", "a=01", "a=9223372036854775808", "a=1,", "A=1", "a=+1"))
            assertFalse(Benefits.valid(invalid), invalid);
        assertTrue(Benefits.valid("")); assertFalse(Benefits.valid(null));
    }
    @Test void catalogRejectsDuplicatesMissingFreeAndReusedRevisionWithoutEchoingInput() {
        var free = offer("free", "v1", 0, 3); var id = PlanCatalog.id(free);
        assertThrows(SubscriptionFailure.class, () -> new PlanCatalog(List.of(free, free), id));
        assertThrows(SubscriptionFailure.class, () -> new PlanCatalog(List.of(offer("pro", "v1", 1, 3)), id));
        var catalog = new PlanCatalog(List.of(free), id);
        assertThrows(SubscriptionFailure.class, () -> catalog.requireCompatible(offer("free", "v1", 0, 5)));
        assertThrows(UnsupportedOperationException.class, () -> catalog.offerIds().clear());
    }
}
