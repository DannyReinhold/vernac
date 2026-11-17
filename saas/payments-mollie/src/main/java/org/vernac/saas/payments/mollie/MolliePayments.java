// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.payments.mollie;

import java.math.BigDecimal;
import java.net.URI;
import java.util.*;
import tools.jackson.databind.JsonNode;
import org.vernac.saas.payments.*;
import org.vernac.saas.payments.domain.*;
import org.vernac.saas.payments.http.PaymentHttp;
import org.vernac.saas.subscriptions.domain.*;
import static org.vernac.saas.payments.http.PaymentHttp.*;

/** Mollie first/recurring payments. Notification bodies are hints; inspect authenticates all payment facts. */
public final class MolliePayments implements AutoCloseable {
    private final PaymentHttp http;
    private final String expectedMode;
    public MolliePayments(String apiKey) { this(new PaymentHttp(PaymentHttp.Provider.MOLLIE, checked(apiKey)), apiKey.startsWith("test_") ? "test" : "live"); }
    MolliePayments(PaymentHttp http, String expectedMode) { this.http = Objects.requireNonNull(http); if (!Set.of("test", "live").contains(expectedMode)) throw fail(); this.expectedMode = expectedMode; }
    public PaymentProviderEnvironment environment() { return expectedMode.equals("test") ? PaymentProviderEnvironment.TEST : PaymentProviderEnvironment.PRODUCTION; }
    public ProviderCustomer inspectCustomer(RemoteCustomerId customer) {
        var json = http.get("customers/" + customer(customer.encoded()).encoded());
        try {
            var id = customer(requiredText(json, "id"));
            if (!id.equals(customer)) throw fail();
            return ProviderCustomer.of(id, PaymentOperationId.of(UUID.fromString(requiredText(json.path("metadata"), "vernac_operation"))));
        } catch (PaymentFailure safe) { throw safe; } catch (RuntimeException invalid) { throw fail(); }
    }
    public ProviderRefund inspectRefund(RemotePaymentId payment, RemoteRefundId refund) {
        String id = refund.encoded(); if (!id.matches("re_[a-zA-Z0-9]{1,190}")) throw fail();
        return refundResult(http.get("payments/" + paymentId(payment.encoded()).encoded() + "/refunds/" + id), payment);
    }
    public RemoteCustomerId createCustomer(PaymentOperationId operation) {
        return customer(requiredText(http.post("customers", operation.value(), Map.of("name", "Vernac billing customer", "metadata", metadata(operation))), "id"));
    }
    public ProviderPayment checkout(CheckoutRequest request) {
        var values = common(request.operation(), request.customer(), request.amount(), request.currency(), request.webhook());
        values.put("sequenceType", "first"); values.put("redirectUrl", request.returnTo().encoded());
        return payment(http.post("payments", request.operation().value(), values));
    }
    public ProviderPayment charge(RecurringChargeRequest request) {
        var values = common(request.operation(), request.customer(), request.amount(), request.currency(), request.webhook());
        values.put("sequenceType", "recurring"); values.put("mandateId", request.mandate().encoded());
        return payment(http.post("payments", request.operation().value(), values));
    }
    public ProviderPayment inspect(RemotePaymentId payment) { return payment(http.get("payments/" + paymentId(payment.encoded()).encoded())); }
    public ProviderRefund refund(RefundRequest request) {
        var json = http.post("payments/" + paymentId(request.payment().encoded()).encoded() + "/refunds", request.operation().value(),
                Map.of("amount", amount(request.amount(), request.currency()), "description", "Vernac customer refund"));
        var result = refundResult(json, request.payment());
        if (!result.amount().equals(request.amount())) throw fail();
        return result;
    }
    private ProviderRefund refundResult(JsonNode json, RemotePaymentId payment) {
        try {
            var original = paymentId(requiredText(json, "paymentId")); var value = money(json.get("amount"));
            if (!original.equals(payment)) throw fail();
            var state = switch (requiredText(json, "status")) { case "refunded" -> RefundState.REFUNDED; case "failed", "canceled" -> RefundState.FAILED; case "queued", "pending", "processing" -> RefundState.PENDING; default -> RefundState.UNKNOWN; };
            String id = requiredText(json, "id"); if (!id.matches("re_[a-zA-Z0-9]{1,190}")) throw fail();
            return ProviderRefund.of(RemoteRefundId.of(id), original, value, BillingCurrency.EUR, state);
        } catch (PaymentFailure safe) { throw safe; } catch (RuntimeException invalid) { throw fail(); }
    }

    public RemotePaymentId notification(PaymentNoticeBody body, PaymentSignature ignored) {
        // Classic Payments API posts one ASCII form field. It is never a payment proof.
        if (!body.encoded().matches("id=tr_[a-zA-Z0-9]{1,190}")) throw new PaymentFailure(PaymentFailure.Code.INVALID_NOTIFICATION);
        return paymentId(body.encoded().substring(3));
    }
    private Map<String, Object> common(PaymentOperationId operation, RemoteCustomerId customer, MinorUnits price, BillingCurrency currency, PaymentUri webhook) {
        customer(customer.encoded());
        var values = new LinkedHashMap<String, Object>();
        values.put("amount", amount(price, currency)); values.put("customerId", customer.encoded());
        values.put("description", "Vernac subscription payment"); values.put("webhookUrl", webhook.encoded()); values.put("metadata", metadata(operation));
        return values;
    }
    private ProviderPayment payment(JsonNode json) {
        try {
            if (!expectedMode.equals(requiredText(json, "mode"))) throw fail();
            var id = paymentId(requiredText(json, "id")); var customer = customer(requiredText(json, "customerId"));
            var operation = PaymentOperationId.of(UUID.fromString(requiredText(json.path("metadata"), "vernac_operation")));
            var value = money(json.get("amount"));
            var refunded = json.hasNonNull("amountRefunded") ? money(json.get("amountRefunded")) : MinorUnits.of(0);
            if (refunded.value() > value.value()) throw fail();
            var state = switch (requiredText(json, "status")) { case "open" -> PaymentState.OPEN; case "pending", "authorized" -> PaymentState.PENDING; case "paid" -> PaymentState.PAID; case "failed" -> PaymentState.FAILED; case "canceled" -> PaymentState.CANCELLED; case "expired" -> PaymentState.EXPIRED; default -> PaymentState.UNKNOWN; };
            if (state == PaymentState.PAID && refunded.value() == value.value()) state = PaymentState.REFUNDED;
            PaymentUri checkout = null; var link = json.path("_links").path("checkout").get("href");
            if (link != null && !link.isNull()) {
                String uri = requiredText(json.path("_links").path("checkout"), "href");
                if (!Set.of("www.mollie.com", "pay.mollie.com").contains(URI.create(uri).getHost())) throw fail();
                checkout = PaymentUri.of(uri);
            }
            RemoteMandateId mandate = null;
            if (json.hasNonNull("mandateId")) { String raw = requiredText(json, "mandateId"); if (!raw.startsWith("mdt_")) throw fail(); mandate = RemoteMandateId.of(raw); }
            return ProviderPayment.of(id, operation, customer, value, BillingCurrency.EUR, state, refunded, checkout, mandate, state == PaymentState.PAID || state == PaymentState.REFUNDED ? PaymentPostedAt.of(java.time.Instant.parse(requiredText(json, "paidAt"))) : null);
        } catch (PaymentFailure safe) { throw safe; } catch (RuntimeException invalid) { throw fail(); }
    }
    private static Map<String, String> metadata(PaymentOperationId operation) { return Map.of("vernac_operation", operation.asString()); }
    private static Map<String, String> amount(MinorUnits value, BillingCurrency currency) { return Map.of("currency", currency.name(), "value", BigDecimal.valueOf(value.value(), 2).toPlainString()); }
    private static MinorUnits money(JsonNode node) {
        try {
            if (node == null || !"EUR".equals(requiredText(node, "currency"))) throw fail();
            String value = requiredText(node, "value"); if (!value.matches("[0-9]{1,14}\\.[0-9]{2}")) throw fail();
            return MinorUnits.of(new BigDecimal(value).movePointRight(2).longValueExact());
        } catch (PaymentFailure safe) { throw safe; } catch (RuntimeException invalid) { throw fail(); }
    }
    private static RemotePaymentId paymentId(String id) { if (!id.matches("tr_[a-zA-Z0-9]{1,190}")) throw fail(); return RemotePaymentId.of(id); }
    private static RemoteCustomerId customer(String id) { if (!id.matches("cst_[a-zA-Z0-9]{1,190}")) throw fail(); return RemoteCustomerId.of(id); }
    private static String checked(String key) { if (key == null || !(key.startsWith("test_") || key.startsWith("live_"))) throw new PaymentFailure(PaymentFailure.Code.CONFIGURATION); return key; }
    private static PaymentFailure fail() { return new PaymentFailure(PaymentFailure.Code.INVALID_PROVIDER_RESPONSE); }
    @Override public void close() { http.close(); }
    @Override public String toString() { return "MolliePayments[REDACTED]"; }
}
