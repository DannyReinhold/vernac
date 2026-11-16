// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.payments.stripe;
import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.HttpServer;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.Test;
import org.vernac.saas.payments.*;
import org.vernac.saas.payments.domain.*;
import org.vernac.saas.payments.http.PaymentHttp;
import org.vernac.saas.subscriptions.domain.*;
class StripePaymentsTest {
    private static final String SECRET = "whsec_unitsecret";
    private static String signature(String body, long time) throws Exception { return "t=" + time + ",v1=" + Webhook.Util.computeHmacSha256(SECRET, time + "." + body); }
    @Test void officialSdkRejectsTamperedExpiredAndWrongSignatureBeforeAnyProviderFactsAreTrusted() throws Exception {
        try (var provider = new StripePayments(PaymentHttp.loopbackForTests(PaymentHttp.Provider.STRIPE, "sk_test_unit", URI.create("http://127.0.0.1:1/"), Duration.ofMillis(10)), SECRET, false)) {
            String body = "{\"id\":\"evt_one\",\"object\":\"event\",\"type\":\"checkout.session.completed\",\"data\":{\"object\":{\"id\":\"cs_test_one\",\"object\":\"checkout.session\"}}}";
            long now = Instant.now().getEpochSecond();
            assertEquals("cs_test_one", provider.notification(PaymentNoticeBody.of(body), PaymentSignature.of(signature(body, now))).encoded());
            assertThrows(PaymentFailure.class, () -> provider.notification(PaymentNoticeBody.of(body.replace("one", "two")), PaymentSignature.of(signature(body, now))));
            assertThrows(PaymentFailure.class, () -> provider.notification(PaymentNoticeBody.of(body), PaymentSignature.of(signature(body, now - 1000))));
            assertThrows(PaymentFailure.class, () -> provider.notification(PaymentNoticeBody.of(body), PaymentSignature.of("t=1,v1=bad")));
        }
    }
    @Test void checkoutUsesSdkApiVersionAndOffSessionMandateWithoutTreatingReturnUrlAsSettlement() throws Exception {
        var operation = PaymentOperationId.create(); var captured = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/checkout/sessions", request -> {
            assertEquals(operation.asString(), request.getRequestHeaders().getFirst("Idempotency-Key"));
            assertEquals(com.stripe.Stripe.API_VERSION, request.getRequestHeaders().getFirst("Stripe-Version"));
            captured.set(URLDecoder.decode(new String(request.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.UTF_8));
            String json = "{\"id\":\"cs_test_one\",\"livemode\":false,\"customer\":\"cus_one\",\"amount_total\":1200,\"currency\":\"eur\",\"status\":\"open\",\"payment_status\":\"unpaid\",\"metadata\":{\"vernac_operation\":\"" + operation.asString() + "\"},\"url\":\"https://checkout.stripe.com/c/pay/unit\"}";
            byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8); request.sendResponseHeaders(200, bytes.length); request.getResponseBody().write(bytes); request.close();
        }); server.start();
        try (var provider = new StripePayments(PaymentHttp.loopbackForTests(PaymentHttp.Provider.STRIPE, "sk_test_unit", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), Duration.ofSeconds(1)), SECRET, false)) {
            var uri = PaymentUri.of("https://example.test/status");
            var response = provider.checkout(CheckoutRequest.of(operation, RemoteCustomerId.of("cus_one"), MinorUnits.of(1200), BillingCurrency.EUR, uri, uri));
            assertEquals(PaymentState.OPEN, response.state()); assertTrue(captured.get().contains("payment_intent_data[setup_future_usage]=off_session"));
            assertTrue(captured.get().contains("line_items[0][price_data][unit_amount]=1200")); assertFalse(captured.get().contains("account"));
        } finally { server.stop(0); }
    }
    @Test void paidInspectionUsesAuthenticatedLedgerPostingAndRetainsOriginalTimeAfterRefund() throws Exception {
        var operation = PaymentOperationId.create(); var refunded = new AtomicLong(); var source = new AtomicReference<>("ch_one");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", request -> {
            String metadata = "\"metadata\":{\"vernac_operation\":\"" + operation.asString() + "\"}";
            String json = switch (request.getRequestURI().getPath()) {
                case "/checkout/sessions/cs_test_one" -> "{\"id\":\"cs_test_one\",\"livemode\":false,\"customer\":\"cus_one\",\"amount_total\":1200,\"currency\":\"eur\",\"status\":\"complete\",\"payment_status\":\"paid\",\"payment_intent\":\"pi_one\"," + metadata + "}";
                case "/payment_intents/pi_one" -> "{\"id\":\"pi_one\",\"livemode\":false,\"customer\":\"cus_one\",\"amount\":1200,\"amount_received\":1200,\"currency\":\"eur\",\"status\":\"succeeded\",\"latest_charge\":\"ch_one\",\"payment_method\":\"pm_one\"," + metadata + "}";
                case "/charges/ch_one" -> "{\"id\":\"ch_one\",\"livemode\":false,\"payment_intent\":\"pi_one\",\"amount\":1200,\"currency\":\"eur\",\"balance_transaction\":\"txn_one\",\"amount_refunded\":" + refunded.get() + "}";
                case "/balance_transactions/txn_one" -> "{\"id\":\"txn_one\",\"amount\":1200,\"currency\":\"eur\",\"source\":\"" + source.get() + "\",\"created\":1767355200}";
                case "/refunds/re_one" -> "{\"id\":\"re_one\",\"payment_intent\":\"pi_one\",\"amount\":1200,\"currency\":\"eur\",\"status\":\"succeeded\"}";
                default -> throw new AssertionError(request.getRequestURI());
            };
            byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8); request.sendResponseHeaders(200, bytes.length); request.getResponseBody().write(bytes); request.close();
        }); server.start();
        try (var provider = new StripePayments(PaymentHttp.loopbackForTests(PaymentHttp.Provider.STRIPE, "sk_test_unit", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), Duration.ofSeconds(1)), SECRET, false)) {
            var id = RemotePaymentId.of("cs_test_one"); var proof = provider.inspect(id);
            assertEquals(PaymentState.PAID, proof.state()); assertEquals(operation, proof.operation());
            assertEquals(Instant.ofEpochSecond(1767355200), proof.postedAt().orElseThrow().value());
            refunded.set(1200); var reversal = provider.inspect(id);
            assertEquals(PaymentState.REFUNDED, reversal.state()); assertEquals(proof.postedAt(), reversal.postedAt());
            assertEquals(RefundState.REFUNDED, provider.inspectRefund(id, RemoteRefundId.of("re_one")).state());
            source.set("ch_foreign"); assertThrows(PaymentFailure.class, () -> provider.inspect(id));
        } finally { server.stop(0); }
    }

}
