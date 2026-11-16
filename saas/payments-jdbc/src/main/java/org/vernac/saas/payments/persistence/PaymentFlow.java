// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.payments.persistence;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.vernac.runtime.authorization.AuthorizationGuard;
import org.vernac.runtime.tenancy.TenantContext;
import org.vernac.saas.identity.domain.AccountId;
import org.vernac.saas.payments.*;
import org.vernac.saas.payments.domain.*;
import org.vernac.saas.payments.persistence.domain.*;
import org.vernac.saas.payments.persistence.adapter.outbound.jdbc.*;
import org.vernac.saas.subscriptions.*;
import org.vernac.saas.subscriptions.domain.*;

/** Explicit global payment application boundary. Provider work always occurs outside its writers. */
public final class PaymentFlow {
    @FunctionalInterface public interface BillingAuthority { void requireCurrentAdministrator(AccountId account); }
    /** Merchant refund authority is intentionally independent of customer account administration. */
    @FunctionalInterface public interface RefundAuthority { void requireMerchantRefundAuthority(AccountId account); }
    /** Runs inside writers before account payment locks; coordinates with an account closure writer. */
    @FunctionalInterface public interface AccountStatusGuard { void requireActiveAccount(AccountId account); }
    @FunctionalInterface public interface WebhookAdmission { void admit(PaymentProfileKey profile); }
    public record OrderView(PaymentOrderId id, PaymentOrderStatus status, OfferTerms terms, SubscriptionPeriod period,
            PaymentState payment, MinorUnits refunded, Optional<PaymentUri> checkout, PaymentReason reason) {
        @Override public String toString() { return "PaymentOrderView[REDACTED]"; }
    }
    /** Trusted worker read model for invoicing, never a public controller response. */
    public record VerifiedPayment(PaymentOrderId order, AccountId account, OfferTerms terms, SubscriptionPeriod period,
            PaymentPostedAt postedAt, MinorUnits refunded, PaymentProfileKey profile, PaymentProviderEnvironment environment) {
        @Override public String toString() { return "VerifiedPayment[REDACTED]"; }
    }
    public VerifiedPayment verifiedPayment(PaymentOrderId id) {
        unscoped(); return write(() -> verified(orders.byId(id)));
    }
    /** Poll in bounded passes, returning to the beginning at end so later refunds are revisited. */
    public List<VerifiedPayment> verifiedPayments(Optional<PaymentOrderId> after, int limit) {
        unscoped(); Objects.requireNonNull(after); if (limit < 1 || limit > 100) throw fail(PaymentProcessingFailure.Code.INVALID_STATE);
        return write(() -> {
            var parameters = new HashMap<String, Object>(); parameters.put("limit", limit); after.ifPresent(id -> parameters.put("after", id.value()));
            var ids = jdbc.queryForList("SELECT o.\"id\" FROM " + S + "\"PaymentOrder\" o JOIN " + S + "\"PaymentAttempt\" a ON o.\"paidAttempt\"=a.\"id\" WHERE a.\"postedAt\" IS NOT NULL" + (after.isPresent() ? " AND o.\"id\">:after" : "") + " ORDER BY o.\"id\" LIMIT :limit", parameters, UUID.class);
            return ids.stream().map(id -> verified(orders.byId(PaymentOrderId.of(id)))).toList();
        });
    }
    private VerifiedPayment verified(PaymentOrder order) {
        var attempt = order.paidAttempt().map(attempts::byId).orElseThrow(() -> fail(PaymentProcessingFailure.Code.INVALID_STATE));
        if (attempt.state() != PaymentState.PAID && attempt.state() != PaymentState.REFUNDED) throw fail(PaymentProcessingFailure.Code.INVALID_STATE);
        return new VerifiedPayment(order.id(), order.account(), order.terms(), order.period(), attempt.postedAt().orElseThrow(() -> fail(PaymentProcessingFailure.Code.INVALID_STATE)), attempt.refunded(), attempt.profile(), provider(attempt.profile()).environment());
    }
    private record Claim(PaymentAttemptId attempt, UUID lease) {}
    private record Work(PaymentAttempt attempt, PaymentOrder order) {}
    private static final String S = "\"org.vernac.saas.payments.persistence\".";
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate writer;
    private final PaymentOrderRepository orders;
    private final PaymentAttemptRepository attempts;
    private final ProviderRegistrationRepository registrations;
    private final BillingAgreementRepository agreements;
    private final PaymentRefundRepository refunds;
    private final RegisteredRefundResourceRepository refundResources;
    private final RegisteredPaymentResourceRepository resources;
    private final Map<PaymentProfileKey, PaymentProvider> providers;
    private final PlanCatalog catalog;
    private final SubscriptionLedger subscriptions;
    private final BillingAuthority authority;
    private final RefundAuthority refundAuthority;
    private final AccountStatusGuard accountStatus;
    private final WebhookAdmission admission;
    private final Clock clock;

    public PaymentFlow(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager,
            Map<PaymentProfileKey, PaymentProvider> providers, PlanCatalog catalog, SubscriptionLedger subscriptions,
            BillingAuthority authority, WebhookAdmission admission, Clock clock) {
        this(jdbc,manager,providers,catalog,subscriptions,authority,admission,clock,
                account -> { throw fail(PaymentProcessingFailure.Code.ACCESS_DENIED); }, account -> {});
    }
    public PaymentFlow(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager,
            Map<PaymentProfileKey, PaymentProvider> providers, PlanCatalog catalog, SubscriptionLedger subscriptions,
            BillingAuthority authority, WebhookAdmission admission, Clock clock,
            RefundAuthority refundAuthority, AccountStatusGuard accountStatus) {
        this.refundAuthority=Objects.requireNonNull(refundAuthority);this.accountStatus=Objects.requireNonNull(accountStatus);
        unscoped(); this.jdbc = Objects.requireNonNull(jdbc); this.catalog = Objects.requireNonNull(catalog); this.subscriptions = Objects.requireNonNull(subscriptions);
        this.authority = Objects.requireNonNull(authority); this.admission = Objects.requireNonNull(admission); this.clock = Objects.requireNonNull(clock);
        this.providers = Map.copyOf(providers);
        if (providers.isEmpty() || providers.size() > 8 || !(manager instanceof DataSourceTransactionManager tx) || tx.getDataSource() != jdbc.getJdbcTemplate().getDataSource()) throw fail(PaymentProcessingFailure.Code.CONFIGURATION);
        writer = new TransactionTemplate(manager); writer.setTimeout(5); writer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        orders = new JdbcPaymentOrderRepository(jdbc); attempts = new JdbcPaymentAttemptRepository(jdbc);
        resources = new JdbcRegisteredPaymentResourceRepository(jdbc);
        refundResources = new JdbcRegisteredRefundResourceRepository(jdbc);
        registrations = new JdbcProviderRegistrationRepository(jdbc); agreements = new JdbcBillingAgreementRepository(jdbc); refunds = new JdbcPaymentRefundRepository(jdbc);
        write(() -> {
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended('vernac.payments/profiles',0))", Map.of());
            for (var entry : this.providers.entrySet()) {
                var provider = entry.getValue(); var existing = registrations.forKey(entry.getKey());
                if (existing.isPresent()) {
                    var row = existing.get(); if (row.kind() != provider.kind() || row.environment() != provider.environment()) throw fail(PaymentProcessingFailure.Code.CONFIGURATION);
                } else registrations.save(ProviderRegistration.create(entry.getKey(), provider.kind(), provider.environment()));
            }
            return Boolean.TRUE;
        });
    }
    /** Stable form/request identity is mandatory: repeated submission returns the original registered order. */
    public OrderView checkout(AccountId account, PaymentOperationId request, PaymentProfileKey profile, PlanCatalog.OfferId offer) {
        administrator(account); provider(profile);
        var recorded = write(() -> orders.forRequest(PaymentKeys.request(account, request)).map(order -> {
            var attempt = latest(order);
            if (!order.terms().plan().equals(offer.plan()) || !order.terms().revision().equals(offer.revision()) || !attempt.profile().equals(profile)) throw fail(PaymentProcessingFailure.Code.CONFLICT);
            return view(order, visible(order));
        }));
        if (recorded.isPresent()) return recorded.get();
        var terms = catalog.offer(offer);
        if (terms.price().value() == 0) throw fail(PaymentProcessingFailure.Code.INVALID_STATE);
        var current = subscriptions.ensureFree(account);
        return write(() -> {
            accountStatus.requireActiveAccount(account); lock(account); var requestKey = PaymentKeys.request(account, request); var existing = orders.forRequest(requestKey);
            if (existing.isPresent()) {
                var order = existing.get(); var attempt = latest(order);
                if (!order.terms().equals(terms) || !attempt.profile().equals(profile)) throw fail(PaymentProcessingFailure.Code.CONFLICT);
                return view(order, visible(order));
            }
            if (!jdbc.queryForList("SELECT 1 FROM " + S + "\"PaymentOrder\" WHERE \"account\"=:account AND (\"status\" IN ('OPEN','PAID','REVIEW') OR \"reason\"<>'NONE') LIMIT 1", Map.of("account", account.value()), Integer.class).isEmpty()) throw fail(PaymentProcessingFailure.Code.CONFLICT);
            Instant start = current.terms().price().value() == 0 || now().isAfter(current.period().end()) ? now() : current.period().end();
            var period = SubscriptionPeriod.of(start, BillingCalendar.end(start, terms.interval()));
            var order = orders.save(PaymentOrder.create(PaymentKeys.cycle(account, current.id(), period), requestKey, account, current.id(), terms, period, PaymentOrderStatus.OPEN, null, PaymentCounter.of(0), PaymentReason.NONE));
            var attempt = firstAttempt(order, profile, PaymentAttemptKind.FIRST, null, null);
            return view(orders.byId(order.id()), attempt);
        });
    }
    public OrderView order(AccountId account, PaymentOrderId order) {
        administrator(account); return write(() -> { var row = owned(account, order); return view(row, visible(row)); });
    }
    /** Bounded keyset history, always scoped by fresh account administration. */
    public List<OrderView> orders(AccountId account, Optional<PaymentOrderId> after, int limit) {
        administrator(account); Objects.requireNonNull(after);
        if (limit < 1 || limit > 100) throw fail(PaymentProcessingFailure.Code.INVALID_STATE);
        return write(() -> {
            var parameters = new HashMap<String, Object>(); parameters.put("account", account.value()); parameters.put("limit", limit);
            after.ifPresent(id -> parameters.put("after", id.value()));
            var ids = jdbc.queryForList("SELECT \"id\" FROM " + S + "\"PaymentOrder\" WHERE \"account\"=:account" + (after.isPresent() ? " AND \"id\">:after" : "") + " ORDER BY \"id\" LIMIT :limit", parameters, UUID.class);
            return ids.stream().map(id -> { var row = orders.byId(PaymentOrderId.of(id)); return view(row, visible(row)); }).toList();
        });
    }
    /** Read-only recovery: retrieves a provider resource; it never retries an uncertain financial POST. */
    public void reconcilePayment(AccountId account, PaymentOrderId orderId, RemotePaymentId candidate) {
        administrator(account);
        var registered = write(() -> latest(owned(account, orderId))); var provider = provider(registered.profile());
        var proof = provider.inspect(candidate);
        var located = write(() -> attempts.forOperation(proof.operation()).orElseThrow(() -> fail(PaymentProcessingFailure.Code.UNVERIFIED_RESOURCE)));
        if (!located.order().equals(orderId) || !located.account().equals(account) || !located.profile().equals(registered.profile()) || located.financialStarted().isEmpty()) throw fail(PaymentProcessingFailure.Code.UNVERIFIED_RESOURCE);
        var verified = located.remote().filter(id -> !id.equals(proof.id())).map(provider::inspect).orElse(proof);
        write(() -> { lock(account); var row = attempts.byId(located.id()); observe(row, verified); attempts.save(row); return Boolean.TRUE; });
    }
    public OrderView retry(AccountId account, PaymentOrderId order) {
        administrator(account);
        return write(() -> {
            accountStatus.requireActiveAccount(account); lock(account); var row = owned(account, order); var previous = latest(row);
            if (row.paidAttempt().isPresent() || row.status() == PaymentOrderStatus.CLOSED || !Set.of(PaymentState.FAILED, PaymentState.CANCELLED, PaymentState.EXPIRED).contains(previous.state()) || row.attempts().value() >= 5) throw fail(PaymentProcessingFailure.Code.INVALID_STATE);
            var attempt = firstAttempt(row, previous.profile(), previous.kind(), previous.kind() == PaymentAttemptKind.RECURRING ? previous.customer().orElseThrow() : null, previous.kind() == PaymentAttemptKind.RECURRING ? previous.mandate().orElseThrow() : null); return view(orders.byId(row.id()), attempt);
        });
    }
    /** Registers at most one due renewal from a previously verified mandate; it never catches up old charges. */
    public boolean scheduleRenewalOne() {
        unscoped();
        var selected = write(() -> {
            var ids = jdbc.queryForList("SELECT \"id\" FROM " + S + "\"BillingAgreement\" WHERE \"nextBillingAt\"<=:now ORDER BY \"nextBillingAt\",\"id\" LIMIT 1 FOR UPDATE SKIP LOCKED", Map.of("now", java.sql.Timestamp.from(now())), UUID.class);
            if (ids.isEmpty()) return Optional.<BillingAgreement>empty();
            var row = agreements.byId(SubscriptionId.of(ids.getFirst())); row.next(due(now().plusSeconds(30))); agreements.save(row); return Optional.of(row);
        });
        if (selected.isEmpty()) return false;
        var agreement = selected.get(); var current = subscriptions.current(agreement.account());
        if (!current.id().equals(agreement.id())) throw fail(PaymentProcessingFailure.Code.INVALID_STATE);
        if (current.status() == SubscriptionStatus.ENDED || current.renewal() == RenewalStatus.CANCELLED) {
            if (current.status() != SubscriptionStatus.ENDED && !now().isBefore(current.period().end())) subscriptions.expire(agreement.account());
            postponeAgreement(agreement, now().isBefore(current.period().end()) && current.status() != SubscriptionStatus.ENDED ? current.period().end() : Instant.parse("9999-01-01T00:00:00Z")); return true;
        }
        if (now().isBefore(current.period().end())) { postponeAgreement(agreement, current.period().end()); return true; }
        var terms = current.renewalTerms();
        // A scheduled Free change is a nonfinancial subscription transition, never a zero-value charge.
        if (terms.price().value() == 0) {
            var free = subscriptions.activateScheduledFree(agreement.account());
            postponeAgreement(agreement, free.period().end()); return true;
        }
        var period = SubscriptionPeriod.of(current.period().end(), BillingCalendar.end(current.period().end(), terms.interval()));
        write(() -> {
            accountStatus.requireActiveAccount(agreement.account()); lock(agreement.account());
            if (!jdbc.queryForList("SELECT 1 FROM " + S + "\"PaymentOrder\" WHERE \"account\"=:account AND (\"status\" IN ('OPEN','PAID','REVIEW') OR \"reason\"<>'NONE') LIMIT 1", Map.of("account", agreement.account().value()), Integer.class).isEmpty()) return Boolean.FALSE;
            var cycle = PaymentKeys.cycle(agreement.account(), current.id(), period);
            if (!jdbc.queryForList("SELECT 1 FROM " + S + "\"PaymentOrder\" WHERE \"cycle\"=:cycle AND \"status\"<>'CLOSED' LIMIT 1", Map.of("cycle", cycle.encoded()), Integer.class).isEmpty()) return Boolean.FALSE;
            var order = orders.save(PaymentOrder.create(cycle, PaymentKeys.request(agreement.account(), PaymentOperationId.create()), agreement.account(), current.id(), terms, period, PaymentOrderStatus.OPEN, null, PaymentCounter.of(0), PaymentReason.NONE));
            var attempt = firstAttempt(order, agreement.profile(), PaymentAttemptKind.RECURRING, agreement.customer(), agreement.mandate());
            if (!now().isBefore(period.end())) {
                attempt.review(PaymentReason.SETTLEMENT_CONFLICT); attempts.save(attempt); order = orders.byId(order.id()); order.review(PaymentReason.SETTLEMENT_CONFLICT); orders.save(order);
            }
            return Boolean.TRUE;
        });
        return true;
    }
    private void postponeAgreement(BillingAgreement agreement, Instant until) {
        write(() -> { lock(agreement.account()); var current = agreements.byId(agreement.id());
            // A concurrent new paid agreement owns its own next boundary.
            if (current.profile().equals(agreement.profile()) && current.customer().equals(agreement.customer()) && current.mandate().equals(agreement.mandate()) && current.nextBillingAt().equals(agreement.nextBillingAt())) {
                current.next(due(until)); agreements.save(current);
            }
            return Boolean.TRUE;
        });
    }
    public record RefundView(PaymentRefundId id, MinorUnits amount, RefundState state, PaymentReason reason) {
        @Override public String toString() { return "RefundView[REDACTED]"; }
    }
    private record RefundClaim(PaymentRefundId id, UUID token) {}
    private record RefundWork(PaymentRefund refund, PaymentAttempt attempt, PaymentOrder order) {}

    /** Reserves the refund amount before any external effect. Unknown outcomes retain their reservation. */
    public RefundView refund(AccountId account, PaymentOrderId orderId, PaymentOperationId operation, MinorUnits amount) {
        merchant(account);
        if (amount.value() <= 0) throw fail(PaymentProcessingFailure.Code.INVALID_STATE);
        return write(() -> {
            lock(account); var order = owned(account, orderId); var recorded = refunds.forOperation(operation);
            if (recorded.isPresent()) {
                var existing = recorded.get(); var attempt = attempts.byId(existing.attempt());
                if (!existing.account().equals(account) || !attempt.order().equals(orderId) || !existing.amount().equals(amount)) throw fail(PaymentProcessingFailure.Code.CONFLICT);
                return refundView(existing);
            }
            var attempt = order.paidAttempt().map(attempts::byId).orElseThrow(() -> fail(PaymentProcessingFailure.Code.INVALID_STATE));
            long reserved = jdbc.queryForObject("SELECT coalesce(sum(\"amount\"),0) FROM " + S + "\"PaymentRefund\" WHERE \"attempt\"=:attempt AND \"state\"<>'FAILED'", Map.of("attempt", attempt.id().value()), Long.class);
            long confirmed = jdbc.queryForObject("SELECT coalesce(sum(\"amount\"),0) FROM " + S + "\"PaymentRefund\" WHERE \"attempt\"=:attempt AND \"state\"='REFUNDED'", Map.of("attempt", attempt.id().value()), Long.class);
            // Account for refunds performed outside this process without double-counting our confirmed receipts.
            long unavailable = reserved + Math.max(0, attempt.refunded().value() - confirmed);
            if (unavailable > order.terms().price().value() || amount.value() > order.terms().price().value() - unavailable) throw fail(PaymentProcessingFailure.Code.CONFLICT);
            var row = refunds.save(PaymentRefund.create(attempt.id(), account, operation, amount, null, RefundState.PENDING, due(now()), null, null, PaymentCounter.of(0), PaymentReason.NONE));
            return refundView(row);
        });
    }
    public RefundView refund(AccountId account, PaymentRefundId id) {
        merchant(account); return write(() -> {
            var row = refunds.byId(id); if (!row.account().equals(account)) throw fail(PaymentProcessingFailure.Code.ACCESS_DENIED);
            return refundView(row);
        });
    }
    /** One durable refund step. No timeout is interpreted as proof of a failed financial operation. */
    public boolean processRefundOne() {
        unscoped(); var claim = claimRefund(); if (claim == null) return false;
        var work = write(() -> { var row = refunds.byId(claim.id()); var attempt = attempts.byId(row.attempt()); return new RefundWork(row, attempt, orders.byId(attempt.order())); });
        var row = work.refund(); var attempt = work.attempt(); var provider = provider(attempt.profile());
        try {
            if (row.state() != RefundState.REFUNDED) {
                var remote = attempt.remote().orElseThrow(() -> fail(PaymentProcessingFailure.Code.INVALID_STATE));
                var proof = row.remote().isPresent() ? provider.inspectRefund(remote, row.remote().get())
                        : provider.refund(RefundRequest.of(row.operation(), remote, row.amount(), work.order().terms().currency()));
                if (!remote.equals(proof.payment()) || !row.amount().equals(proof.amount()) || work.order().terms().currency() != proof.currency()
                        || row.remote().filter(id -> !id.equals(proof.id())).isPresent()) throw fail(PaymentProcessingFailure.Code.UNVERIFIED_RESOURCE);
                acknowledgeRefund(claim, current -> {
                    var route = PaymentRouteKey.of(attempt.profile().value() + "/" + proof.id().encoded()); var bound = refundResources.forRoute(route);
                    if (bound.filter(other -> !other.refund().equals(current.id())).isPresent()) throw fail(PaymentProcessingFailure.Code.UNVERIFIED_RESOURCE);
                    if (bound.isEmpty()) refundResources.save(RegisteredRefundResource.create(route, current.id()));
                    current.observe(proof.id(), proof.state(), due(now().plusSeconds(30)));
                    if (proof.state() == RefundState.REFUNDED) current.defer(due(now()), PaymentReason.OUTCOME_UNKNOWN);
                });
            } else {
                // Refresh total financial truth before queuing entitlement reversal. This step survives a crash
                // after provider confirmation because the confirmed receipt remains reconciliation work.
                var proof = provider.inspect(attempt.remote().orElseThrow());
                acknowledgeRefund(claim, current -> {
                    long confirmed = jdbc.queryForObject("SELECT coalesce(sum(\"amount\"),0) FROM " + S + "\"PaymentRefund\" WHERE \"attempt\"=:attempt AND \"state\"='REFUNDED'", Map.of("attempt", attempt.id().value()), Long.class);
                    if (proof.refunded().value() < confirmed) throw fail(PaymentProcessingFailure.Code.UNAVAILABLE);
                    var fresh = attempts.byId(attempt.id()); observe(fresh, proof); attempts.save(fresh);
                    current.observe(current.remote().orElseThrow(), RefundState.REFUNDED, due(now()));
                });
            }
        } catch (PaymentFailure failure) {
            acknowledgeRefund(claim, current -> {
                if (failure.code() == PaymentFailure.Code.REJECTED || failure.code() == PaymentFailure.Code.INVALID_PROVIDER_RESPONSE || failure.code() == PaymentFailure.Code.OPERATION_CONFLICT) current.review();
                else current.defer(due(now().plusSeconds(30)), PaymentReason.OUTCOME_UNKNOWN);
            });
        } catch (PaymentProcessingFailure failure) {
            acknowledgeRefund(claim, current -> {
                if (failure.code() == PaymentProcessingFailure.Code.UNAVAILABLE) current.defer(due(now().plusSeconds(30)), PaymentReason.OUTCOME_UNKNOWN);
                else current.review();
            });
        }
        return true;
    }
    private RefundClaim claimRefund() {
        return write(() -> {
            var time = now(); var ids = jdbc.queryForList("SELECT \"id\" FROM " + S + "\"PaymentRefund\" WHERE (\"state\" IN ('PENDING','UNKNOWN') OR (\"state\"='REFUNDED' AND \"reason\"='OUTCOME_UNKNOWN')) AND \"reason\"<>'REFUND_UNCERTAIN' AND \"due\"<=:now AND (\"lease.token\" IS NULL OR \"lease.until\"<=:now) ORDER BY \"due\",\"id\" LIMIT 1 FOR UPDATE SKIP LOCKED", Map.of("now", java.sql.Timestamp.from(time)), UUID.class);
            if (ids.isEmpty()) return null;
            var row = refunds.byId(PaymentRefundId.of(ids.getFirst()));
            if (row.remote().isEmpty() && (row.calls().value() >= 5 || row.financialStarted().filter(start -> !time.isBefore(start.value().plus(Duration.ofMinutes(30)))).isPresent())) {
                row.review(); refunds.save(row); return null;
            }
            var token = UUID.randomUUID(); row.claim(PaymentLease.of(token, time.plusSeconds(120)), PaymentStartedAt.of(time)); refunds.save(row); return new RefundClaim(row.id(), token);
        });
    }
    private void acknowledgeRefund(RefundClaim claim, Consumer<PaymentRefund> action) {
        write(() -> {
            var first = refunds.byId(claim.id()); lock(first.account()); var row = refunds.byId(claim.id());
            if (row.lease().filter(lease -> lease.token().equals(claim.token()) && now().isBefore(lease.until())).isEmpty()) return Boolean.FALSE;
            action.accept(row); refunds.save(row); return Boolean.TRUE;
        });
    }
    private static RefundView refundView(PaymentRefund row) { return new RefundView(row.id(), row.amount(), row.state(), row.reason()); }
    /** Public ingress adapter must pass the raw bounded notice and its configured profile, never a tenant claim. */
    public void webhook(PaymentProfileKey profile, PaymentNoticeBody body, PaymentSignature signature) {
        unscoped(); admission.admit(profile); var provider = provider(profile);
        RemotePaymentId id = provider.notification(body, signature); ProviderPayment proof = provider.inspect(id);
        var proofOperation = proof.operation();
        var located = write(() -> attempts.forOperation(proofOperation).orElseThrow(() -> fail(PaymentProcessingFailure.Code.UNVERIFIED_RESOURCE)));
        if (!located.profile().equals(profile) || located.financialStarted().isEmpty()) throw fail(PaymentProcessingFailure.Code.UNVERIFIED_RESOURCE);
        // A different authenticated alias is only a trigger to inspect the already registered primary resource.
        // No callback may replace a known binding or confer authority through matching metadata alone.
        if (located.remote().isPresent() && !located.remote().get().equals(proof.id())) proof = provider.inspect(located.remote().get());
        final ProviderPayment verified = proof;
        write(() -> { lock(located.account()); var current = attempts.byId(located.id()); observe(current, verified); attempts.save(current); return Boolean.TRUE; });
    }
    /** Bounded explicit scheduler entrypoint; no background thread or provider work is installed automatically. */
    public boolean processOne(PaymentUri returnTo, PaymentUri webhook) {
        unscoped(); Claim claim = claim(); if (claim == null) return false;
        Work work = write(() -> { var attempt = attempts.byId(claim.attempt()); return new Work(attempt, orders.byId(attempt.order())); });
        var attempt = work.attempt(); var order = work.order(); var provider = provider(attempt.profile());
        try {
            switch (attempt.stage()) {
                case PREPARING -> {
                    try {
                        validSchedule(order, attempt.kind());
                        if (attempt.kind() == PaymentAttemptKind.FIRST) {
                            if (!catalog.offer(PlanCatalog.id(order.terms())).equals(order.terms())) throw fail(PaymentProcessingFailure.Code.CONFLICT);
                        }
                        acknowledge(claim, row -> row.prepared(due(now())));
                    } catch (SubscriptionFailure | PaymentProcessingFailure invalidSchedule) {
                        acknowledge(claim, row -> row.review(PaymentReason.SETTLEMENT_CONFLICT));
                    }
                }
                case NEW -> {
                    if (order.paidAttempt().filter(id -> !id.equals(attempt.id())).isPresent()) { acknowledge(claim, PaymentAttempt::finish); break; }
                    RemoteCustomerId customer = provider.createCustomer(attempt.customerOperation());
                    acknowledge(claim, row -> row.customerReady(customer, due(now())));
                }
                case CUSTOMER_READY -> {
                    if (order.paidAttempt().filter(id -> !id.equals(attempt.id())).isPresent()) { acknowledge(claim, row -> row.review(PaymentReason.OUTCOME_UNKNOWN)); break; }
                    try { validSchedule(order, attempt.kind()); }
                    catch (SubscriptionFailure | PaymentProcessingFailure invalidSchedule) { acknowledge(claim, row -> row.review(PaymentReason.SETTLEMENT_CONFLICT)); break; }
                    var customer = attempt.customer().orElseThrow(() -> fail(PaymentProcessingFailure.Code.INVALID_STATE));
                    ProviderPayment proof = attempt.kind() == PaymentAttemptKind.FIRST
                            ? provider.checkout(CheckoutRequest.of(attempt.operation(), customer, order.terms().price(), order.terms().currency(), returnTo, webhook))
                            : provider.charge(RecurringChargeRequest.of(attempt.operation(), customer, attempt.mandate().orElseThrow(), order.terms().price(), order.terms().currency(), webhook));
                    acknowledge(claim, row -> observe(row, proof));
                }
                case WAITING -> { var proof = provider.inspect(attempt.remote().orElseThrow()); acknowledge(claim, row -> observe(row, proof)); }
                case APPLY -> apply(claim, work);
                case REVOKE -> revoke(claim, work);
                default -> acknowledge(claim, PaymentAttempt::finish);
            }
        } catch (PaymentFailure failure) {
            PaymentReason reason = failure.code() == PaymentFailure.Code.OUTCOME_UNKNOWN ? PaymentReason.OUTCOME_UNKNOWN : failure.code() == PaymentFailure.Code.INVALID_PROVIDER_RESPONSE ? PaymentReason.INVALID_PROVIDER_FACTS : PaymentReason.PROVIDER_UNAVAILABLE;
            acknowledge(claim, row -> {
                if (failure.code() == PaymentFailure.Code.REJECTED && row.stage() == PaymentAttemptStage.CUSTOMER_READY) {
                    row.review(PaymentReason.INVALID_PROVIDER_FACTS); // Do not invent an unregistered failed resource/charge result.
                } else row.defer(due(now().plusSeconds(30)), reason);
            });
        } catch (PaymentProcessingFailure failure) {
            acknowledge(claim, row -> {
                if (failure.code() == PaymentProcessingFailure.Code.UNAVAILABLE) row.defer(due(now().plusSeconds(30)), PaymentReason.PROVIDER_UNAVAILABLE);
                else row.review(PaymentReason.INVALID_PROVIDER_FACTS);
            });
        }
        return true;
    }
    private void apply(Claim claim, Work work) {
        var order = work.order();
        try {
            if (work.attempt().kind() == PaymentAttemptKind.FIRST)
                subscriptions.confirmAuthorizedPurchase(order.account(), SettlementKey.of("payment-order/" + order.id().asString()), order.terms(), order.period());
            else subscriptions.confirm(order.account(), SettlementKey.of("payment-order/" + order.id().asString()), order.terms(), order.period());
            acknowledge(claim, row -> {
                var current = orders.byId(order.id());
                if (current.paidAttempt().filter(row.id()::equals).isEmpty()) { row.review(PaymentReason.DUPLICATE_PAID); return; }
                current.applied(); orders.save(current); row.finish();
                if (row.mandate().isPresent()) {
                    var found = agreement(current.subscription());
                    if (found.isPresent()) { var value = found.get(); value.replace(row.profile(), row.customer().orElseThrow(), row.mandate().get(), due(current.period().end())); agreements.save(value); }
                    else { var time = now(); agreements.save(BillingAgreement.reconstitute(current.subscription(), current.account(), row.profile(), row.customer().orElseThrow(), row.mandate().get(), due(current.period().end()), time, time, 0)); }
                }
            });
        } catch (SubscriptionFailure failure) {
            acknowledge(claim, row -> {
                if (failure.code() == SubscriptionFailure.Code.NOT_DUE) row.defer(due(order.period().start()), PaymentReason.NONE);
                else if (failure.code() == SubscriptionFailure.Code.UNAVAILABLE) row.defer(due(now().plusSeconds(30)), PaymentReason.PROVIDER_UNAVAILABLE);
                else { row.review(PaymentReason.SETTLEMENT_CONFLICT); var current = orders.byId(order.id()); current.review(PaymentReason.SETTLEMENT_CONFLICT); orders.save(current); }
            });
        }
    }
    /** A reversal tombstone fences both a previously applied and a delayed first settlement. */
    private void revoke(Claim claim, Work work) {
        var order = work.order();
        if (order.paidAttempt().filter(work.attempt().id()::equals).isPresent()) {
            try {
                subscriptions.revokeVerifiedSettlement(order.account(), SettlementKey.of("payment-order/" + order.id().asString()), order.terms(), order.period());
            } catch (SubscriptionFailure failure) {
                acknowledge(claim, row -> {
                    if (failure.code() == SubscriptionFailure.Code.UNAVAILABLE) row.defer(due(now().plusSeconds(30)), PaymentReason.PROVIDER_UNAVAILABLE);
                    else row.review(PaymentReason.SETTLEMENT_CONFLICT);
                });
                return;
            }
        }
        acknowledge(claim, row -> {
            var current = orders.byId(order.id());
            if (current.paidAttempt().filter(row.id()::equals).isPresent()) { current.close(); orders.save(current); }
            row.finish();
        });
    }
    private Claim claim() {
        return write(() -> {
            Instant time = now();
            var rows = jdbc.queryForList("SELECT \"id\" FROM " + S + "\"PaymentAttempt\" WHERE \"stage\" NOT IN ('DONE','REVIEW') AND \"due\"<=:now AND (\"lease.token\" IS NULL OR \"lease.until\"<=:now) ORDER BY \"due\",\"id\" LIMIT 1", Map.of("now", java.sql.Timestamp.from(time)), UUID.class);
            if (rows.isEmpty()) return null;
            var selected = attempts.byId(PaymentAttemptId.of(rows.getFirst()));
            if(Set.of(PaymentAttemptStage.PREPARING,PaymentAttemptStage.NEW,PaymentAttemptStage.CUSTOMER_READY).contains(selected.stage()))
                accountStatus.requireActiveAccount(selected.account());
            lock(selected.account());
            var eligible = jdbc.queryForList("SELECT \"id\" FROM " + S + "\"PaymentAttempt\" WHERE \"id\"=:id AND \"stage\" NOT IN ('DONE','REVIEW') AND \"due\"<=:now AND (\"lease.token\" IS NULL OR \"lease.until\"<=:now) FOR UPDATE SKIP LOCKED", Map.of("id",selected.id().value(),"now",java.sql.Timestamp.from(time)), UUID.class);
            if(eligible.isEmpty())return null;
            var row = attempts.byId(selected.id());
            if ((row.stage() == PaymentAttemptStage.NEW || row.stage() == PaymentAttemptStage.CUSTOMER_READY) && (row.calls().value() >= 5 || row.financialStarted().filter(start -> !time.isBefore(start.value().plus(Duration.ofMinutes(30)))).isPresent())) {
                row.review(PaymentReason.OUTCOME_UNKNOWN); attempts.save(row); return null;
            }
            UUID token = UUID.randomUUID(); row.claim(PaymentLease.of(token, time.plusSeconds(120)), PaymentStartedAt.of(time)); attempts.save(row); return new Claim(row.id(), token);
        });
    }
    private void acknowledge(Claim claim, Consumer<PaymentAttempt> action) {
        write(() -> {
            var first = attempts.byId(claim.attempt()); lock(first.account()); var row = attempts.byId(claim.attempt());
            if (row.lease().filter(lease -> lease.token().equals(claim.lease()) && now().isBefore(lease.until())).isEmpty()) return Boolean.FALSE;
            action.accept(row); attempts.save(row); return Boolean.TRUE;
        });
    }
    private void observe(PaymentAttempt attempt, ProviderPayment proof) {
        var order = orders.byId(attempt.order());
        if (!attempt.operation().equals(proof.operation()) || !attempt.customer().filter(proof.customer()::equals).isPresent()
                || attempt.postedAt().filter(time -> !proof.postedAt().filter(time::equals).isPresent()).isPresent()
                || !order.terms().price().equals(proof.amount()) || order.terms().currency() != proof.currency()
                || attempt.remote().filter(remote -> !remote.equals(proof.id())).isPresent() || proof.refunded().value() < attempt.refunded().value()) throw fail(PaymentProcessingFailure.Code.UNVERIFIED_RESOURCE);
        var route = PaymentKeys.route(attempt.profile(), proof.id()); var existing = resources.forRoute(route);
        if (existing.filter(other -> !other.attempt().equals(attempt.id())).isPresent()) throw fail(PaymentProcessingFailure.Code.UNVERIFIED_RESOURCE);
        if (existing.isEmpty()) resources.save(RegisteredPaymentResource.create(route, attempt.id(), attempt.profile(), proof.id()));
        PaymentAttemptStage next = PaymentAttemptStage.WAITING; PaymentReason reason = PaymentReason.NONE;
        if (attempt.state() == PaymentState.REFUNDED && proof.state() != PaymentState.REFUNDED) return;
        if (proof.state() == PaymentState.REFUNDED) {
            // Full refund is also proof of a prior settlement. Register it before recording its reversal,
            // even when the callback overtakes the worker's first PAID observation.
            if (proof.refunded().value() != order.terms().price().value()) throw fail(PaymentProcessingFailure.Code.UNVERIFIED_RESOURCE);
            if (order.acceptPaid(attempt.id())) { orders.save(order); next = PaymentAttemptStage.REVOKE; }
            else next = PaymentAttemptStage.DONE; // Reversing an extra paid attempt cannot revoke the winner.
        } else if (proof.state() == PaymentState.PAID) {
            if (order.acceptPaid(attempt.id())) { orders.save(order); next = order.status() == PaymentOrderStatus.APPLIED ? PaymentAttemptStage.DONE : PaymentAttemptStage.APPLY; }
            else { next = PaymentAttemptStage.REVIEW; reason = PaymentReason.DUPLICATE_PAID; order.review(reason); orders.save(order); }
        } else if (Set.of(PaymentState.FAILED, PaymentState.CANCELLED, PaymentState.EXPIRED, PaymentState.REFUNDED).contains(proof.state())) next = PaymentAttemptStage.DONE;
        else if (attempt.state() == PaymentState.PAID || attempt.state() == PaymentState.REFUNDED) return; // Stale nonpaid reads cannot remove a proven settlement.
        attempt.observed(proof.id(), proof.checkout().orElse(null), proof.mandate().orElse(null), proof.state(), proof.refunded(), proof.postedAt().orElse(null), next, due((next == PaymentAttemptStage.APPLY || next == PaymentAttemptStage.REVOKE) ? now() : now().plusSeconds(30)), reason);
    }
    private PaymentAttempt firstAttempt(PaymentOrder order, PaymentProfileKey profile, PaymentAttemptKind kind, RemoteCustomerId customer, RemoteMandateId mandate) {
        order.attempted(); orders.save(order);
        return attempts.save(PaymentAttempt.create(order.id(), order.account(), profile, kind, PaymentKeys.attempt(order.id(), order.attempts().value()), PaymentOperationId.create(), PaymentOperationId.create(),
                customer, null, null, mandate, PaymentState.OPEN, MinorUnits.of(0), PaymentAttemptStage.PREPARING, due(now()), null, null, PaymentCounter.of(0), PaymentReason.NONE, null));
    }
    private void validSchedule(PaymentOrder order, PaymentAttemptKind kind) {
        var current = subscriptions.current(order.account());
        boolean period = current.terms().price().value() == 0
                ? !order.period().start().isBefore(current.period().start()) && order.period().start().isBefore(current.period().end())
                : order.period().start().equals(current.period().end()) || kind == PaymentAttemptKind.FIRST && order.period().start().isAfter(current.period().end()) && !order.period().start().isAfter(now());
        boolean status = current.status() == SubscriptionStatus.ACTIVE || current.status() == SubscriptionStatus.SUSPENDED && current.terms().price().value() > 0;
        if (kind == PaymentAttemptKind.RECURRING && !now().isBefore(order.period().end())) throw fail(PaymentProcessingFailure.Code.INVALID_STATE);
        if (!current.id().equals(order.subscription()) || !status || current.renewal() == RenewalStatus.CANCELLED || !period || kind == PaymentAttemptKind.RECURRING && !current.renewalTerms().equals(order.terms())) throw fail(PaymentProcessingFailure.Code.INVALID_STATE);
    }
    public void closeUnpaid(AccountId account, PaymentOrderId id) {
        administrator(account);
        write(() -> {
            lock(account); var order = owned(account, id); var attempt = latest(order);
            boolean noCharge = attempt.financialStarted().isEmpty() || Set.of(PaymentState.FAILED, PaymentState.CANCELLED, PaymentState.EXPIRED).contains(attempt.state());
            if (order.paidAttempt().isPresent() || !noCharge) throw fail(PaymentProcessingFailure.Code.INVALID_STATE);
            order.close(); orders.save(order); attempt.finish(); attempts.save(attempt); return Boolean.TRUE;
        });
    }
    private Optional<BillingAgreement> agreement(SubscriptionId subscription) {
        try { return Optional.of(agreements.byId(subscription)); } catch (org.vernac.runtime.AggregateNotFoundException absent) { return Optional.empty(); }
    }
    private PaymentAttempt visible(PaymentOrder order) { return order.paidAttempt().map(attempts::byId).orElseGet(() -> latest(order)); }
    private PaymentAttempt latest(PaymentOrder order) { return attempts.forKey(PaymentKeys.attempt(order.id(), order.attempts().value())).orElseThrow(() -> fail(PaymentProcessingFailure.Code.INVALID_STATE)); }
    private PaymentOrder owned(AccountId account, PaymentOrderId id) { var row = orders.byId(id); if (!row.account().equals(account)) throw fail(PaymentProcessingFailure.Code.ACCESS_DENIED); return row; }
    private static OrderView view(PaymentOrder order, PaymentAttempt attempt) { return new OrderView(order.id(), order.status(), order.terms(), order.period(), attempt.state(), attempt.refunded(), attempt.checkout(), order.reason() == PaymentReason.NONE ? attempt.reason() : order.reason()); }
    private PaymentProvider provider(PaymentProfileKey key) { var provider = providers.get(key); if (provider == null) throw fail(PaymentProcessingFailure.Code.CONFIGURATION); return provider; }
    private void administrator(AccountId account) { unscoped(); try { authority.requireCurrentAdministrator(Objects.requireNonNull(account)); } catch (RuntimeException denied) { throw fail(PaymentProcessingFailure.Code.ACCESS_DENIED); } }
    private void merchant(AccountId account) { unscoped(); try { refundAuthority.requireMerchantRefundAuthority(Objects.requireNonNull(account)); } catch(RuntimeException denied) { throw fail(PaymentProcessingFailure.Code.ACCESS_DENIED); } }
    private void lock(AccountId account) { jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))", Map.of("key", "vernac.payments/account/" + account.asString())); }
    private <T> T write(Supplier<T> action) { unscoped(); try { return writer.execute(status -> action.get()); } catch (PaymentProcessingFailure safe) { throw safe; } catch (RuntimeException unavailable) { throw fail(PaymentProcessingFailure.Code.UNAVAILABLE); } }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static PaymentDueAt due(Instant value) { return PaymentDueAt.of(value); }
    private static void unscoped() { if (TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isSynchronizationActive() || !TransactionSynchronizationManager.getResourceMap().isEmpty() || TenantContext.currentTenant().isPresent() || AuthorizationGuard.currentActor().isPresent()) throw fail(PaymentProcessingFailure.Code.CONFIGURATION); }
    private static PaymentProcessingFailure fail(PaymentProcessingFailure.Code code) { return new PaymentProcessingFailure(code); }
    @Override public String toString() { return "PaymentFlow[REDACTED]"; }
}
