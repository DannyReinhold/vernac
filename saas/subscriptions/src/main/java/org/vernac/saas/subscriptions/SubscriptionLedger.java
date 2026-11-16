// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.subscriptions;

import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.vernac.runtime.authorization.AuthorizationGuard;
import org.vernac.runtime.tenancy.TenantContext;
import org.vernac.saas.identity.domain.AccountId;
import org.vernac.saas.subscriptions.domain.*;
import org.vernac.saas.subscriptions.adapter.outbound.jdbc.*;

/**
 * Explicit trusted global subscription writer. Invoke only after current account authorization
 * or verified payment routing. This object is not an HTTP API or a membership/tenant selector.
 */
public final class SubscriptionLedger {
    private static final String SCHEMA = "\"org.vernac.saas.subscriptions\"";
    public record View(SubscriptionId id, SubscriptionStatus status, OfferTerms terms,
            SubscriptionPeriod period, RenewalStatus renewal, OfferTerms renewalTerms) {
        @Override public String toString() { return "SubscriptionView[REDACTED]"; }
    }
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate writer, reader;
    private final SubscriptionRepository subscriptions;
    private final SubscriptionSettlementRepository settlements;
    private final PlanCatalog catalog;
    private final Clock clock;
    /** Runs inside the subscription writer after its account lock, before a user-directed change. */
    @FunctionalInterface public interface LifecyclePolicy { void requireChangeAllowed(AccountId account); }
    private final LifecyclePolicy lifecycle;


    public SubscriptionLedger(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager,
            PlanCatalog catalog, Clock clock) {
        this(jdbc, manager, catalog, clock, account -> {});
    }
    public SubscriptionLedger(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager,
            PlanCatalog catalog, Clock clock, LifecyclePolicy lifecycle) {
        unscoped();
        this.lifecycle = Objects.requireNonNull(lifecycle);
        this.jdbc = Objects.requireNonNull(jdbc); this.catalog = Objects.requireNonNull(catalog); this.clock = Objects.requireNonNull(clock);
        if (!(manager instanceof DataSourceTransactionManager tx) || tx.getDataSource() != jdbc.getJdbcTemplate().getDataSource()) throw fail(SubscriptionFailure.Code.CONFIGURATION);
        writer = new TransactionTemplate(manager); writer.setTimeout(5);
        writer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        reader = new TransactionTemplate(manager); reader.setReadOnly(true); reader.setTimeout(5);
        subscriptions = new JdbcSubscriptionRepository(jdbc);
        settlements = new JdbcSubscriptionSettlementRepository(jdbc);
        try {
            Boolean restricted = jdbc.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls AND NOT has_schema_privilege(current_user,:schema,'CREATE') FROM pg_roles WHERE rolname=current_user",
                    Map.of("schema", "org.vernac.saas.subscriptions"), Boolean.class);
            if (!Boolean.TRUE.equals(restricted)) throw fail(SubscriptionFailure.Code.CONFIGURATION);
            // Validate every retained revision in SQL with bounded aggregate output; never load all customers.
            for (var id : catalog.offerIds()) {
                var terms = catalog.offer(id);
                String current = mismatch("terms"), renewal = mismatch("renewalTerms");
                Boolean drift = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM " + SCHEMA + ".\"Subscription\" WHERE " + current + " OR " + renewal + ") OR EXISTS (SELECT 1 FROM " + SCHEMA + ".\"SubscriptionSettlement\" WHERE " + current + ")",
                        Map.of("plan", terms.plan().value(), "revision", terms.revision().value(), "price", terms.price().value(), "currency", terms.currency().name(), "interval", terms.interval().name(), "benefits", terms.benefits().encoded()), Boolean.class);
                if (!Boolean.FALSE.equals(drift)) throw fail(SubscriptionFailure.Code.CONFIGURATION);
            }
        } catch (RuntimeException invalid) { throw fail(SubscriptionFailure.Code.CONFIGURATION); }
    }
    public View ensureFree(AccountId verifiedAccount) {
        return transact(verifiedAccount, existing -> {
            if (existing.isPresent()) return view(existing.get());
            var terms = catalog.initialFreeOffer();
            var period = SubscriptionPeriod.of(clock.instant(), BillingCalendar.freeEnd());
            var created = Subscription.create(verifiedAccount, terms, SubscriptionStatus.AWAITING_PAYMENT, period, RenewalStatus.ENABLED, terms);
            created.activateFree(); return view(subscriptions.save(created));
        });
    }
    public View current(AccountId verifiedAccount) {
        return transact(verifiedAccount, existing -> view(required(existing)));
    }
    /** Read-only facts may join a product transaction. The caller supplies its already verified account. */
    public BenefitSnapshot benefits(AccountId verifiedAccount) {
        Objects.requireNonNull(verifiedAccount);
        try {
            return Objects.requireNonNull(reader.execute(status -> subscriptions.forAccount(verifiedAccount)
                    .map(s -> s.benefitsAt(clock.instant())).orElseGet(() -> BenefitSnapshot.of(""))));
        } catch (RuntimeException unavailable) { throw fail(SubscriptionFailure.Code.UNAVAILABLE); }
    }
    public View schedule(AccountId verifiedAccount, PlanCatalog.OfferId offer) {
        OfferTerms next = catalog.offer(offer);
        return mutate(verifiedAccount, subscription -> subscription.scheduleChange(next));
    }
    /** Applies only an explicitly scheduled Free offer at its boundary; no payment or ongoing billing job is needed. */
    public View activateScheduledFree(AccountId verifiedAccount) {
        return transact(verifiedAccount, existing -> {
            var subscription = required(existing);
            if (subscription.status() == SubscriptionStatus.ACTIVE && subscription.terms().price().value() == 0 && subscription.period().end().equals(BillingCalendar.freeEnd())) return view(subscription);
            subscription.activateScheduledFree(clock.instant()); return view(subscriptions.save(subscription));
        });
    }
    public View cancel(AccountId verifiedAccount) { return mutate(verifiedAccount, Subscription::cancelRenewal); }
    public View resume(AccountId verifiedAccount) { return mutate(verifiedAccount, subscription -> subscription.resumeRenewal(clock.instant())); }
    public View expire(AccountId verifiedAccount) { return mutate(verifiedAccount, subscription -> subscription.lapse(clock.instant())); }
    /** Explicit new agreement after cancellation; never an automatic downgrade or reopening of an account. */
    public View reopenFree(AccountId verifiedAccount) {
        return mutate(verifiedAccount, subscription -> subscription.restartFree(catalog.initialFreeOffer(),
                SubscriptionPeriod.of(clock.instant(), BillingCalendar.freeEnd())));
    }
    public View close(AccountId verifiedAccount) { return mutate(verifiedAccount, Subscription::end); }

    /** Same logical operation must retain exactly the same subscription, terms and period across retries. */
    public View confirm(AccountId verifiedAccount, SettlementKey operation, OfferTerms verifiedTerms, SubscriptionPeriod paidPeriod) {
        return settle(verifiedAccount, operation, verifiedTerms, paidPeriod, false);
    }
    /** Trusted explicit purchase: its registered, verified terms are installed atomically with settlement. */
    public View confirmAuthorizedPurchase(AccountId verifiedAccount, SettlementKey operation, OfferTerms verifiedTerms, SubscriptionPeriod paidPeriod) {
        return settle(verifiedAccount, operation, verifiedTerms, paidPeriod, true);
    }
    private View settle(AccountId verifiedAccount, SettlementKey operation, OfferTerms verifiedTerms, SubscriptionPeriod paidPeriod, boolean explicitPurchase) {
        Objects.requireNonNull(operation); Objects.requireNonNull(verifiedTerms); Objects.requireNonNull(paidPeriod);
        return transact(verifiedAccount, existing -> {
            var subscription = required(existing);
            var seen = settlements.forOperation(operation);
            if (seen.isPresent()) {
                var receipt = seen.get();
                if (receipt.status() == SettlementStatus.REVOKED) throw fail(SubscriptionFailure.Code.INVALID_TRANSITION);
                if (!receipt.subscription().equals(subscription.id()) || !receipt.terms().equals(verifiedTerms) || !receipt.period().equals(paidPeriod))
                    throw fail(SubscriptionFailure.Code.SETTLEMENT_CONFLICT);
                return view(subscription);
            }
            if (paidPeriod.start().isAfter(clock.instant())) throw fail(SubscriptionFailure.Code.NOT_DUE);
            if (explicitPurchase && subscription.status() != SubscriptionStatus.AWAITING_PAYMENT) subscription.scheduleChange(verifiedTerms);
            if (subscription.status() == SubscriptionStatus.AWAITING_PAYMENT)
                subscription.settleInitial(verifiedTerms, paidPeriod.start(), paidPeriod.end());
            else if (subscription.terms().price().value() == 0 && verifiedTerms.price().value() > 0)
                subscription.settleUpgrade(verifiedTerms, paidPeriod.start(), paidPeriod.end());
            else if (paidPeriod.start().isAfter(subscription.period().end())) subscription.settleResumption(verifiedTerms, paidPeriod.start(), paidPeriod.end());
            else subscription.settleRenewal(verifiedTerms, paidPeriod.start(), paidPeriod.end());
            var saved = subscriptions.save(subscription);
            settlements.save(SubscriptionSettlement.create(saved.id(), operation, verifiedTerms, paidPeriod, SettlementStatus.SETTLED));
            return view(saved);
        });
    }
    /** Authoritative full reversal. The durable tombstone also fences a delayed first confirmation. */
    public View revokeVerifiedSettlement(AccountId verifiedAccount, SettlementKey operation, OfferTerms verifiedTerms, SubscriptionPeriod paidPeriod) {
        Objects.requireNonNull(operation); Objects.requireNonNull(verifiedTerms); Objects.requireNonNull(paidPeriod);
        return transact(verifiedAccount, existing -> {
            var subscription = required(existing); var seen = settlements.forOperation(operation);
            if (seen.isPresent()) {
                var receipt = seen.get();
                if (!receipt.subscription().equals(subscription.id()) || !receipt.terms().equals(verifiedTerms) || !receipt.period().equals(paidPeriod))
                    throw fail(SubscriptionFailure.Code.SETTLEMENT_CONFLICT);
                receipt.revoke(); settlements.save(receipt);
            } else settlements.save(SubscriptionSettlement.create(subscription.id(), operation, verifiedTerms, paidPeriod, SettlementStatus.REVOKED));
            if (subscription.period().equals(paidPeriod) && subscription.terms().equals(verifiedTerms)) {
                subscription.end(); subscription = subscriptions.save(subscription);
            }
            return view(subscription);
        });
    }
    private View mutate(AccountId account, java.util.function.Consumer<Subscription> operation) {
        return transact(account, existing -> { lifecycle.requireChangeAllowed(account); var subscription = required(existing); operation.accept(subscription); return view(subscriptions.save(subscription)); });
    }
    private <T> T transact(AccountId account, Function<Optional<Subscription>, T> operation) {
        unscoped(); Objects.requireNonNull(account);
        try {
            return Objects.requireNonNull(writer.execute(status -> {
                // Serializes creation as well as mutation. A hash collision only adds serialization.
                jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))", Map.of("key", "vernac.subscription/" + account.asString()));
                return operation.apply(subscriptions.forAccount(account));
            }));
        } catch (SubscriptionFailure safe) { throw safe; }
        catch (RuntimeException unavailable) { throw fail(SubscriptionFailure.Code.UNAVAILABLE); }
    }
    private static String mismatch(String prefix) {
        return "(\"" + prefix + ".plan\"=:plan AND \"" + prefix + ".revision\"=:revision AND (\"" + prefix + ".price\"<>:price OR \"" + prefix + ".currency\"<>:currency OR \"" + prefix + ".interval\"<>:interval OR \"" + prefix + ".benefits\"<>:benefits))";
    }
    private static Subscription required(Optional<Subscription> subscription) {
        return subscription.orElseThrow(() -> fail(SubscriptionFailure.Code.NOT_FOUND));
    }
    private static View view(Subscription s) { return new View(s.id(), s.status(), s.terms(), s.period(), s.renewal(), s.renewalTerms()); }
    private static void unscoped() {
        if (TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.getResourceMap().isEmpty() || TenantContext.currentTenant().isPresent() || AuthorizationGuard.currentActor().isPresent())
            throw fail(SubscriptionFailure.Code.CONFIGURATION);
    }
    private static SubscriptionFailure fail(SubscriptionFailure.Code code) { return new SubscriptionFailure(code); }
    @Override public String toString() { return "SubscriptionLedger[REDACTED]"; }
}
