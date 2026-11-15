// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.payments.local;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.vernac.saas.payments.*;
import org.vernac.saas.payments.domain.*;
import org.vernac.saas.payments.local.domain.*;
import org.vernac.saas.payments.local.adapter.outbound.jdbc.*;
import org.vernac.saas.subscriptions.domain.*;

/** Persistent local simulator; uses normal provider resources, inspection and application result processing. */
public final class LocalPayments {
    public enum Step { PAID, PENDING, FAILED, CANCELLED, EXPIRED, UNAVAILABLE, TIMEOUT_PAID }
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate writer;
    private final LocalCustomerRepository customers;
    private final LocalPaymentRepository payments;
    private final LocalRefundRepository refunds;
    private final List<Step> scenario;
    private final Clock clock;
    public LocalPayments(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager, List<Step> scenario, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc); this.clock = Objects.requireNonNull(clock); this.scenario = List.copyOf(scenario);
        if (scenario.isEmpty() || scenario.size() > 16 || !(manager instanceof DataSourceTransactionManager tx) || tx.getDataSource() != jdbc.getJdbcTemplate().getDataSource()) throw fail(PaymentFailure.Code.CONFIGURATION);
        writer = new TransactionTemplate(manager); writer.setTimeout(5); writer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        customers = new JdbcLocalCustomerRepository(jdbc); payments = new JdbcLocalPaymentRepository(jdbc); refunds = new JdbcLocalRefundRepository(jdbc);
    }
    public PaymentProviderEnvironment environment() { return PaymentProviderEnvironment.SIMULATED; }
    public ProviderCustomer inspectCustomer(RemoteCustomerId customer) {
        return transaction(() -> { var operation = parse(customer.encoded(), "localc_"); customers.byId(operation); return ProviderCustomer.of(customer, operation); });
    }
    public ProviderRefund inspectRefund(RemotePaymentId payment, RemoteRefundId refund) {
        return transaction(() -> {
            var row = refunds.byId(parse(refund.encoded(), "localr_"));
            if (!row.paymentOperation().equals(parse(payment.encoded(), "localp_"))) throw fail(PaymentFailure.Code.INVALID_PROVIDER_RESPONSE);
            return ProviderRefund.of(refund, payment, row.amount(), BillingCurrency.EUR, RefundState.REFUNDED);
        });
    }
    public RemoteCustomerId createCustomer(PaymentOperationId operation) {
        return transaction(() -> {
            lock("customer/" + operation.asString());
            try { customers.byId(operation); }
            catch (org.vernac.runtime.AggregateNotFoundException absent) {
                var now = clock.instant(); customers.save(LocalCustomer.reconstitute(operation, LocalMandateStatus.ABSENT, now, now, 0));
            }
            return customer(operation);
        });
    }
    public ProviderPayment checkout(CheckoutRequest request) {
        return create(request.operation(), request.customer(), request.amount(), LocalRequestKind.CHECKOUT, request.returnTo(), null);
    }
    public ProviderPayment charge(RecurringChargeRequest request) {
        return create(request.operation(), request.customer(), request.amount(), LocalRequestKind.RECURRING, null, request.mandate());
    }
    private ProviderPayment create(PaymentOperationId operation, RemoteCustomerId customer, MinorUnits amount, LocalRequestKind kind, PaymentUri checkout, RemoteMandateId mandate) {
        return transaction(() -> {
            var customerOperation = parse(customer.encoded(), "localc_"); lock("customer/" + customerOperation.asString()); lock("payment/" + operation.asString());
            var owner = customers.byId(customerOperation);
            if (kind == LocalRequestKind.RECURRING && (owner.mandate() != LocalMandateStatus.VALID || !mandate(customerOperation).equals(mandate))) throw fail(PaymentFailure.Code.REJECTED);
            LocalPayment row;
            try { row = payments.byId(operation); }
            catch (org.vernac.runtime.AggregateNotFoundException absent) {
                var now = clock.instant(); row = LocalPayment.reconstitute(operation, customerOperation, customer, kind, amount, checkout, mandate, PaymentState.OPEN, MinorUnits.of(0), LocalCursor.of(0), now, now, 0);
                row = payments.save(row);
            }
            if (!row.customer().equals(customer) || row.kind() != kind || !row.amount().equals(amount) || !row.checkout().equals(Optional.ofNullable(checkout))) throw fail(PaymentFailure.Code.OPERATION_CONFLICT);
            return view(row);
        });
    }
    private record Inspection(ProviderPayment payment, Step simulatedFailure) {}
    public ProviderPayment inspect(RemotePaymentId id) {
        var result = transaction(() -> {
            var operation = parse(id.encoded(), "localp_");
            // Inspect the immutable customer locator before locking in the same order as create/charge.
            var original = payments.byId(operation); lock("customer/" + original.customerOperation().asString()); lock("payment/" + operation.asString());
            var row = payments.byId(operation);
            if (Set.of(PaymentState.PAID, PaymentState.FAILED, PaymentState.CANCELLED, PaymentState.EXPIRED, PaymentState.REFUNDED).contains(row.status())) return new Inspection(view(row), null);
            Step step = scenario.get(Math.min(row.cursor().value(), scenario.size() - 1));
            PaymentState state = switch (step) { case PAID, TIMEOUT_PAID -> PaymentState.PAID; case PENDING -> PaymentState.PENDING; case FAILED -> PaymentState.FAILED; case CANCELLED -> PaymentState.CANCELLED; case EXPIRED -> PaymentState.EXPIRED; case UNAVAILABLE -> row.status(); };
            RemoteMandateId mandate = row.mandate().orElse(null);
            if (state == PaymentState.PAID) {
                var customer = customers.byId(row.customerOperation()); customer.grantMandate(); customers.save(customer); mandate = mandate(row.customerOperation());
            }
            row.advance(state, LocalCursor.of(Math.min(scenario.size(), row.cursor().value() + 1)), mandate); row = payments.save(row);
            return new Inspection(view(row), step == Step.UNAVAILABLE || step == Step.TIMEOUT_PAID ? step : null);
        });
        if (result.simulatedFailure() != null) throw fail(result.simulatedFailure() == Step.TIMEOUT_PAID ? PaymentFailure.Code.OUTCOME_UNKNOWN : PaymentFailure.Code.UNAVAILABLE);
        return result.payment();
    }
    public ProviderRefund refund(RefundRequest request) {
        return transaction(() -> {
            var paymentOperation = parse(request.payment().encoded(), "localp_"); lock("payment/" + paymentOperation.asString()); lock("refund/" + request.operation().asString());
            LocalRefund refund;
            try { refund = refunds.byId(request.operation()); }
            catch (org.vernac.runtime.AggregateNotFoundException absent) {
                var payment = payments.byId(paymentOperation);
                long total;
                try { total = Math.addExact(payment.refunded().value(), request.amount().value()); } catch (ArithmeticException invalid) { throw fail(PaymentFailure.Code.REJECTED); }
                if (total > payment.amount().value()) throw fail(PaymentFailure.Code.REJECTED);
                payment.refund(MinorUnits.of(total)); payments.save(payment);
                var now = clock.instant(); refund = refunds.save(LocalRefund.reconstitute(request.operation(), paymentOperation, request.amount(), now, now, 0));
            }
            if (!refund.paymentOperation().equals(paymentOperation) || !refund.amount().equals(request.amount())) throw fail(PaymentFailure.Code.OPERATION_CONFLICT);
            return ProviderRefund.of(RemoteRefundId.of("localr_" + compact(request.operation())), request.payment(), request.amount(), BillingCurrency.EUR, RefundState.REFUNDED);
        });
    }
    public RemotePaymentId notification(PaymentNoticeBody body, PaymentSignature ignored) {
        if (!body.encoded().matches("id=localp_[0-9a-f]{32}")) throw fail(PaymentFailure.Code.INVALID_NOTIFICATION);
        var id = RemotePaymentId.of(body.encoded().substring(3)); parse(id.encoded(), "localp_"); return id;
    }
    private ProviderPayment view(LocalPayment row) {
        return ProviderPayment.of(RemotePaymentId.of("localp_" + compact(row.id())), row.id(), row.customer(), row.amount(), BillingCurrency.EUR, row.status(), row.refunded(), row.checkout().orElse(null), row.mandate().orElse(null));
    }
    private <T> T transaction(Supplier<T> action) {
        if (TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isSynchronizationActive()) throw fail(PaymentFailure.Code.CONFIGURATION);
        try { return writer.execute(status -> action.get()); }
        catch (PaymentFailure safe) { throw safe; } catch (RuntimeException unavailable) { throw fail(PaymentFailure.Code.UNAVAILABLE); }
    }
    private void lock(String key) { jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))", Map.of("key", "vernac.local-payment/" + key)); }
    private static PaymentOperationId parse(String encoded, String prefix) {
        if (!encoded.matches(prefix + "[0-9a-f]{32}")) throw fail(PaymentFailure.Code.INVALID_PROVIDER_RESPONSE);
        String raw = encoded.substring(prefix.length());
        return PaymentOperationId.of(UUID.fromString(raw.substring(0,8)+"-"+raw.substring(8,12)+"-"+raw.substring(12,16)+"-"+raw.substring(16,20)+"-"+raw.substring(20)));
    }
    private static String compact(PaymentOperationId operation) { return operation.asString().replace("-", ""); }
    private static RemoteCustomerId customer(PaymentOperationId operation) { return RemoteCustomerId.of("localc_" + compact(operation)); }
    private static RemoteMandateId mandate(PaymentOperationId operation) { return RemoteMandateId.of("localm_" + compact(operation)); }
    public static void requireRefund(boolean valid) { if (!valid) throw fail(PaymentFailure.Code.REJECTED); }
    private static PaymentFailure fail(PaymentFailure.Code code) { return new PaymentFailure(code); }
    @Override public String toString() { return "LocalPayments[SIMULATED]"; }
}
