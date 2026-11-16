// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.payments.http;
import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.vernac.saas.payments.PaymentFailure;
class PaymentHttpTest {
    @Test void uncertainPostIsNotRetriedAndRequestIdAndSecretsStayOutOfFailures() throws Exception {
        var count = new AtomicInteger(); var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/payments", request -> {
            count.incrementAndGet(); assertEquals("Bearer test_secret123", request.getRequestHeaders().getFirst("Authorization"));
            assertNotNull(request.getRequestHeaders().getFirst("Idempotency-Key")); request.getRequestBody().readAllBytes();
            request.getResponseHeaders().add("Retry-After", "0"); request.sendResponseHeaders(503, -1); request.close();
        }); server.start();
        try (var client = PaymentHttp.loopbackForTests(PaymentHttp.Provider.MOLLIE, "test_secret123", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), Duration.ofSeconds(1))) {
            var failed = assertThrows(PaymentFailure.class, () -> client.post("payments", UUID.randomUUID(), Map.of("amount", "1.00")));
            assertEquals(PaymentFailure.Code.OUTCOME_UNKNOWN, failed.code()); assertEquals(1, count.get());
            assertNull(failed.getCause()); assertFalse(failed.toString().contains("secret123")); assertFalse(client.toString().contains("secret123"));
        } finally { server.stop(0); }
    }
    @Test void oversizedMalformedAndDuplicateSuccessfulBodiesCannotBecomePaymentFacts() throws Exception {
        var body = new AtomicReference<>("{\"id\":\"first\",\"id\":\"second\"}");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/payments", request -> { byte[] bytes = body.get().getBytes(java.nio.charset.StandardCharsets.UTF_8); request.sendResponseHeaders(200, bytes.length); request.getResponseBody().write(bytes); request.close(); }); server.start();
        try (var client = PaymentHttp.loopbackForTests(PaymentHttp.Provider.MOLLIE, "test_key", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), Duration.ofSeconds(1))) {
            assertThrows(PaymentFailure.class, () -> client.get("payments")); body.set("{\"data\":\"" + "x".repeat(300000) + "\"}");
            assertEquals(PaymentFailure.Code.INVALID_PROVIDER_RESPONSE, assertThrows(PaymentFailure.class, () -> client.get("payments")).code());
            body.set("[]"); assertThrows(PaymentFailure.class, () -> client.get("payments"));
        } finally { server.stop(0); }
    }
    @Test void loopbackSeamNeverAcceptsRemoteOriginsOrRedirectPaths() {
        assertThrows(PaymentFailure.class, () -> PaymentHttp.loopbackForTests(PaymentHttp.Provider.STRIPE, "sk_test_x", URI.create("https://example.com/"), Duration.ofSeconds(1)));
        assertThrows(PaymentFailure.class, () -> new PaymentHttp(PaymentHttp.Provider.MOLLIE, "secret\r\ninjected"));
        try (var client = PaymentHttp.loopbackForTests(PaymentHttp.Provider.MOLLIE, "test_key", URI.create("http://127.0.0.1:1/"), Duration.ofMillis(10))) {
            assertEquals(PaymentFailure.Code.CONFIGURATION, assertThrows(PaymentFailure.class, () -> client.get("//evil.example/resource")).code());
        }
    }
}
