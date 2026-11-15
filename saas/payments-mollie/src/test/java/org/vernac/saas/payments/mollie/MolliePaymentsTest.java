// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.payments.mollie;
import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.vernac.saas.payments.*;
import org.vernac.saas.payments.domain.*;
import org.vernac.saas.payments.http.PaymentHttp;
import org.vernac.saas.subscriptions.domain.*;
class MolliePaymentsTest {
    @Test void firstAndRecurringPaymentsUseStableOperationsAndAuthenticatedInspectionRatherThanNotificationClaims() throws Exception {
        var operation = PaymentOperationId.create(); var requestBody = new AtomicReference<String>(); var responseMode = new AtomicReference<>("test");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/payments", exchange -> {
            assertEquals("Bearer test_key", exchange.getRequestHeaders().getFirst("Authorization"));
            if (exchange.getRequestMethod().equals("POST")) { assertEquals(operation.asString(), exchange.getRequestHeaders().getFirst("Idempotency-Key")); requestBody.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)); }
            var json = "{\"id\":\"tr_one\",\"mode\":\"" + responseMode.get() + "\",\"customerId\":\"cst_one\",\"status\":\"paid\",\"amount\":{\"currency\":\"EUR\",\"value\":\"12.00\"},\"amountRefunded\":{\"currency\":\"EUR\",\"value\":\"0.00\"},\"metadata\":{\"vernac_operation\":\"" + operation.asString() + "\"},\"mandateId\":\"mdt_one\"}";
            byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        var http = PaymentHttp.loopbackForTests(PaymentHttp.Provider.MOLLIE, "test_key", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), Duration.ofSeconds(1));
        try (var provider = new MolliePayments(http, "test")) {
            var uri = PaymentUri.of("https://example.test/payment"); var customer = RemoteCustomerId.of("cst_one");
            var payment = provider.checkout(CheckoutRequest.of(operation, customer, MinorUnits.of(1200), BillingCurrency.EUR, uri, uri));
            assertEquals(PaymentState.PAID, payment.state()); assertTrue(requestBody.get().contains("\"sequenceType\":\"first\""));
            assertTrue(requestBody.get().contains("\"value\":\"12.00\"")); assertFalse(requestBody.get().contains("account"));
            provider.charge(RecurringChargeRequest.of(operation, customer, RemoteMandateId.of("mdt_one"), MinorUnits.of(1200), BillingCurrency.EUR, uri));
            assertTrue(requestBody.get().contains("\"sequenceType\":\"recurring\""));
            var hint = provider.notification(PaymentNoticeBody.of("id=tr_one"), PaymentSignature.of("")); assertEquals(payment.id(), hint);
            assertEquals(payment, provider.inspect(hint)); responseMode.set("live");
            assertThrows(PaymentFailure.class, () -> provider.inspect(hint));
            assertThrows(PaymentFailure.class, () -> provider.notification(PaymentNoticeBody.of("id=tr_one&id=tr_other"), PaymentSignature.of("")));
        } finally { server.stop(0); }
    }
    @Test void missingConfigurationDoesNotLeakCredentialsOrUseLocalSuccess() {
        var failure = assertThrows(PaymentFailure.class, () -> new MolliePayments("unselected-secret"));
        assertEquals(PaymentFailure.Code.CONFIGURATION, failure.code()); assertNull(failure.getCause()); assertFalse(failure.toString().contains("unselected-secret"));
    }
}
