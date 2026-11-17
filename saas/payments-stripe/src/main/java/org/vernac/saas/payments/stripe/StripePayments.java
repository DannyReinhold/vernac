// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.payments.stripe;

import java.net.URI;
import java.util.*;
import tools.jackson.databind.JsonNode;
import com.stripe.Stripe;
import com.stripe.net.Webhook;
import org.vernac.saas.payments.*;
import org.vernac.saas.payments.domain.*;
import org.vernac.saas.payments.http.PaymentHttp;
import org.vernac.saas.subscriptions.domain.*;
import static org.vernac.saas.payments.http.PaymentHttp.*;

/** API calls are bounded and explicit. Signature verification uses the official pinned Stripe SDK. */
public final class StripePayments implements AutoCloseable {
    private final PaymentHttp http;
    private final String signingSecret;
    private final boolean live;
    public StripePayments(String apiKey, String signingSecret) {
        this(new PaymentHttp(PaymentHttp.Provider.STRIPE, checkedKey(apiKey)), signingSecret, apiKey.startsWith("sk_live_") || apiKey.startsWith("rk_live_"));
    }
    StripePayments(PaymentHttp http, String signingSecret, boolean live) {
        if (signingSecret == null || !signingSecret.matches("whsec_[a-zA-Z0-9]{1,4000}")) throw new PaymentFailure(PaymentFailure.Code.CONFIGURATION);
        this.http = Objects.requireNonNull(http); this.signingSecret = signingSecret; this.live = live;
    }
    public PaymentProviderEnvironment environment() { return live ? PaymentProviderEnvironment.PRODUCTION : PaymentProviderEnvironment.TEST; }
    public ProviderCustomer inspectCustomer(RemoteCustomerId customer) {
        var json = get("customers/" + customer(customer.encoded()).encoded());
        try {
            environment(json); var id = customer(requiredText(json, "id")); if (!id.equals(customer)) throw fail();
            return ProviderCustomer.of(id, operation(json));
        } catch (PaymentFailure safe) { throw safe; } catch (RuntimeException invalid) { throw fail(); }
    }
    public ProviderRefund inspectRefund(RemotePaymentId payment, RemoteRefundId refund) {
        if (!refund.encoded().matches("re_[a-zA-Z0-9]{1,190}")) throw fail();
        return refundResult(get("refunds/" + refund.encoded()), payment, intentId(payment));
    }
    public RemoteCustomerId createCustomer(PaymentOperationId operation) {
        return customer(requiredText(post("customers", operation, Map.of("description", "Vernac billing customer", "metadata[vernac_operation]", operation.asString())), "id"));
    }
    public ProviderPayment checkout(CheckoutRequest request) {
        customer(request.customer().encoded());
        var fields = new LinkedHashMap<String, String>(); fields.put("mode", "payment"); fields.put("customer", request.customer().encoded());
        fields.put("success_url", request.returnTo().encoded()); fields.put("cancel_url", request.returnTo().encoded());
        fields.put("line_items[0][price_data][currency]", "eur"); fields.put("line_items[0][price_data][unit_amount]", Long.toString(request.amount().value()));
        fields.put("line_items[0][price_data][product_data][name]", "Vernac subscription payment"); fields.put("line_items[0][quantity]", "1");
        fields.put("payment_intent_data[setup_future_usage]", "off_session"); fields.put("payment_intent_data[metadata][vernac_operation]", request.operation().asString());
        fields.put("metadata[vernac_operation]", request.operation().asString());
        return session(post("checkout/sessions", request.operation(), fields));
    }
    public ProviderPayment charge(RecurringChargeRequest request) {
        customer(request.customer().encoded()); if (!request.mandate().encoded().startsWith("pm_")) throw fail();
        return intent(post("payment_intents", request.operation(), Map.of("customer", request.customer().encoded(), "payment_method", request.mandate().encoded(),
                "amount", Long.toString(request.amount().value()), "currency", "eur", "confirm", "true", "off_session", "true",
                "automatic_payment_methods[enabled]", "true", "automatic_payment_methods[allow_redirects]", "never", "metadata[vernac_operation]", request.operation().asString())));
    }
    public ProviderPayment inspect(RemotePaymentId payment) {
        String id = payment.encoded();
        if (id.matches("cs_[a-zA-Z0-9_]{1,190}")) return session(get("checkout/sessions/" + id));
        if (id.matches("pi_[a-zA-Z0-9]{1,190}")) return intent(get("payment_intents/" + id));
        throw fail();
    }
    public ProviderRefund refund(RefundRequest request) {
        String paymentIntent = intentId(request.payment());
        var json = post("refunds", request.operation(), Map.of("payment_intent", paymentIntent, "amount", Long.toString(request.amount().value()), "metadata[vernac_operation]", request.operation().asString()));
        var result = refundResult(json, request.payment(), paymentIntent);
        if (!result.amount().equals(request.amount())) throw fail();
        return result;
    }
    private ProviderRefund refundResult(JsonNode json, RemotePaymentId payment, String paymentIntent) {
        try {
            if (!paymentIntent.equals(requiredText(json, "payment_intent")) || !"eur".equals(requiredText(json, "currency"))) throw fail();
            String id = requiredText(json, "id"); if (!id.matches("re_[a-zA-Z0-9]{1,190}")) throw fail();
            var state = switch (requiredText(json, "status")) { case "succeeded" -> RefundState.REFUNDED; case "failed", "canceled" -> RefundState.FAILED; case "pending", "requires_action" -> RefundState.PENDING; default -> RefundState.UNKNOWN; };
            return ProviderRefund.of(RemoteRefundId.of(id), payment, MinorUnits.of(requiredLong(json, "amount")), BillingCurrency.EUR, state);
        } catch (PaymentFailure safe) { throw safe; } catch (RuntimeException invalid) { throw fail(); }
    }

    public RemotePaymentId notification(PaymentNoticeBody body, PaymentSignature signature) {
        try {
            var event = Webhook.constructEvent(body.encoded(), signature.encoded(), signingSecret);
            if (!Set.of("checkout.session.completed", "checkout.session.async_payment_succeeded", "checkout.session.async_payment_failed", "checkout.session.expired", "payment_intent.succeeded", "payment_intent.payment_failed", "payment_intent.processing", "charge.refunded").contains(event.getType()))
                throw new PaymentFailure(PaymentFailure.Code.INVALID_NOTIFICATION);
            var object = parseNotice(body.encoded()).path("data").path("object");
            String id = requiredText(object, event.getType().equals("charge.refunded") ? "payment_intent" : "id");
            if (!id.matches("(cs_[a-zA-Z0-9_]{1,190}|pi_[a-zA-Z0-9]{1,190})")) throw fail();
            return RemotePaymentId.of(id); // The caller must still retrieve and match the registered resource.
        } catch (Exception invalid) { throw new PaymentFailure(PaymentFailure.Code.INVALID_NOTIFICATION); }
    }
    private ProviderPayment session(JsonNode json) {
        try {
            environment(json); String id = requiredText(json, "id"); if (!id.matches("cs_[a-zA-Z0-9_]{1,190}")) throw fail();
            var operation = operation(json); var customer = customer(requiredText(json, "customer")); var amount = MinorUnits.of(requiredLong(json, "amount_total")); currency(json);
            String status = requiredText(json, "status"), paid = requiredText(json, "payment_status");
            PaymentUri checkout = null; if (json.hasNonNull("url")) { String url = requiredText(json, "url"); if (!"checkout.stripe.com".equals(URI.create(url).getHost())) throw fail(); checkout = PaymentUri.of(url); }
            if (paid.equals("paid")) {
                var proof = intent(get("payment_intents/" + requiredText(json, "payment_intent")));
                if (!proof.operation().equals(operation) || !proof.customer().equals(customer) || !proof.amount().equals(amount) || !(proof.state() == PaymentState.PAID || proof.state() == PaymentState.REFUNDED)) throw fail();
                return ProviderPayment.of(RemotePaymentId.of(id), operation, customer, amount, BillingCurrency.EUR, proof.state(), proof.refunded(), checkout, proof.mandate().orElse(null), proof.postedAt().orElseThrow());
            }
            var state = status.equals("expired") ? PaymentState.EXPIRED : status.equals("open") ? PaymentState.OPEN : PaymentState.PENDING;
            return ProviderPayment.of(RemotePaymentId.of(id), operation, customer, amount, BillingCurrency.EUR, state, MinorUnits.of(0), checkout, null, null);
        } catch (PaymentFailure safe) { throw safe; } catch (RuntimeException invalid) { throw fail(); }
    }
    private ProviderPayment intent(JsonNode json) {
        try {
            environment(json); String id = requiredText(json, "id"); if (!id.matches("pi_[a-zA-Z0-9]{1,190}")) throw fail();
            var operation = operation(json); var customer = customer(requiredText(json, "customer")); var amount = MinorUnits.of(requiredLong(json, "amount")); currency(json);
            var state = switch (requiredText(json, "status")) { case "succeeded" -> PaymentState.PAID; case "processing", "requires_action", "requires_confirmation", "requires_capture" -> PaymentState.PENDING; case "canceled" -> PaymentState.CANCELLED; case "requires_payment_method" -> json.hasNonNull("last_payment_error") ? PaymentState.FAILED : PaymentState.OPEN; default -> PaymentState.UNKNOWN; };
            var refunded = MinorUnits.of(0); PaymentPostedAt postedAt = null;
            if (state == PaymentState.PAID) {
                if (requiredLong(json, "amount_received") != amount.value()) throw fail();
                String chargeId = requiredText(json, "latest_charge"); if (!chargeId.matches("ch_[a-zA-Z0-9]{1,190}")) throw fail();
                var charge = get("charges/" + chargeId); environment(charge); currency(charge);
                if (!id.equals(requiredText(charge, "payment_intent")) || requiredLong(charge, "amount") != amount.value()) throw fail();
                String transactionId = requiredText(charge, "balance_transaction"); if (!transactionId.matches("txn_[a-zA-Z0-9]{1,190}")) throw fail();
                var transaction = get("balance_transactions/" + transactionId); currency(transaction);
                if (!transactionId.equals(requiredText(transaction, "id")) || !chargeId.equals(requiredText(transaction, "source")) || requiredLong(transaction, "amount") != amount.value()) throw fail();
                postedAt = PaymentPostedAt.of(java.time.Instant.ofEpochSecond(requiredLong(transaction, "created")));
                refunded = MinorUnits.of(requiredLong(charge, "amount_refunded"));
                if (refunded.value() > amount.value()) throw fail(); if (refunded.value() == amount.value()) state = PaymentState.REFUNDED;
            }
            RemoteMandateId mandate = null;
            if (json.hasNonNull("payment_method")) { String method = requiredText(json, "payment_method"); if (!method.matches("pm_[a-zA-Z0-9]{1,190}")) throw fail(); mandate = RemoteMandateId.of(method); }
            return ProviderPayment.of(RemotePaymentId.of(id), operation, customer, amount, BillingCurrency.EUR, state, refunded, null, mandate, postedAt);
        } catch (PaymentFailure safe) { throw safe; } catch (RuntimeException invalid) { throw fail(); }
    }
    private String intentId(RemotePaymentId id) {
        if (id.encoded().matches("pi_[a-zA-Z0-9]{1,190}")) return id.encoded();
        if (id.encoded().matches("cs_[a-zA-Z0-9_]{1,190}")) {
            var session = get("checkout/sessions/" + id.encoded()); environment(session);
            String intent = requiredText(session, "payment_intent"); if (!intent.matches("pi_[a-zA-Z0-9]{1,190}")) throw fail(); return intent;
        }
        throw fail();
    }
    private void environment(JsonNode json) { if (!json.has("livemode") || !json.get("livemode").isBoolean() || json.get("livemode").booleanValue() != live) throw fail(); }
    private static void currency(JsonNode json) { if (!"eur".equals(requiredText(json, "currency"))) throw fail(); }
    private static PaymentOperationId operation(JsonNode json) { return PaymentOperationId.of(UUID.fromString(requiredText(json.path("metadata"), "vernac_operation"))); }
    private static RemoteCustomerId customer(String id) { if (!id.matches("cus_[a-zA-Z0-9]{1,190}")) throw fail(); return RemoteCustomerId.of(id); }
    private JsonNode get(String path) { return http.stripeGet(path, Stripe.API_VERSION); }
    private JsonNode post(String path, PaymentOperationId operation, Map<String, String> form) { return http.stripePost(path, operation.value(), form, Stripe.API_VERSION); }
    private static String checkedKey(String key) { if (key == null || !key.matches("(sk|rk)_(test|live)_[a-zA-Z0-9]{1,4000}")) throw new PaymentFailure(PaymentFailure.Code.CONFIGURATION); return key; }
    private static PaymentFailure fail() { return new PaymentFailure(PaymentFailure.Code.INVALID_PROVIDER_RESPONSE); }
    @Override public void close() { http.close(); }
    @Override public String toString() { return "StripePayments[REDACTED]"; }
}
