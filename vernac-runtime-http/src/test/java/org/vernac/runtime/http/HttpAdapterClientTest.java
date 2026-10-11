// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.http;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.vernac.runtime.DomainValidationException;
import org.vernac.runtime.json.*;
import org.vernac.runtime.ports.*;
import static org.junit.jupiter.api.Assertions.*;

class HttpAdapterClientTest {
    private HttpServer server;
    private ExecutorService serverThreads;
    private HttpClient transport;
    private HttpAdapterClient client;
    private URI base;
    private final AtomicInteger requests = new AtomicInteger();
    private final AdapterInvocation invocation = AdapterInvocation.create("Catalog", "HttpCatalog", "load");
    enum Rejection { BLOCKED }

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverThreads = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverThreads);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                byte[] requestBody = exchange.getRequestBody().readAllBytes();
                String value = switch (path) {
                    case "/echo" -> exchange.getRequestMethod() + ":" + new String(requestBody, StandardCharsets.UTF_8);
                    case "/malformed" -> "{ confidential-response";
                    case "/large" -> "x".repeat(4096);
                    default -> "\"ok\"";
                };
                int status = path.startsWith("/status/") ? Integer.parseInt(path.substring(8)) : 200;
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Retry-After", "5");
                exchange.getResponseHeaders().set("X-Correlation-ID", "remote-123");
                exchange.getResponseHeaders().set("Set-Cookie", "secret-cookie");
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
                if (status != 204) exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        transport = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        client = new HttpAdapterClient(transport, Duration.ofSeconds(3), 1024);
    }
    @AfterEach void stop() {
        server.stop(0);
        serverThreads.shutdownNow();
        transport.close();
    }
    private HttpRequest request(String path) { return HttpRequest.newBuilder(base.resolve(path)).GET().build(); }
    private String read(HttpResponseData response) { return HttpJson.read(response, String.class); }

    @Test void preservesHttpMethodsAndRequestBodiesIncludingPatch() {
        for (String method : List.of("GET", "POST", "PUT", "PATCH", "DELETE")) {
            String result = client.required(invocation,
                    () -> HttpRequest.newBuilder(base.resolve("/echo")).header("Content-Type", "application/json")
                            .method(method, HttpRequest.BodyPublishers.ofString("{\"name\":null}")).build(),
                    response -> new String(response.body(), StandardCharsets.UTF_8), HttpFailures::defaultFailure);
            assertEquals(method + ":{\"name\":null}", result);
        }
        assertEquals(5, requests.get());
    }

    @Test void absenceRequiresExplicit404Policy() {
        assertTrue(client.optionalOn404(invocation, () -> request("/status/404"), this::read,
                (i, r) -> { fail("404 policy should run first"); return null; }).isEmpty());
        var missing = assertThrows(PortInvocationException.class,
                () -> client.required(invocation, () -> request("/status/404"), this::read, HttpFailures::defaultFailure));
        assertEquals("404", missing.transportDetails().get("http.status"));
        assertEquals("ok", client.optionalOn404(invocation, () -> request("/"), this::read,
                HttpFailures::defaultFailure).orElseThrow());
        assertThrows(RemoteIntegrationException.class, () -> client.optionalOn404(invocation,
                () -> request("/status/204"), this::read, HttpFailures::defaultFailure));
        client.execute(invocation, () -> request("/status/204"), HttpFailures::defaultFailure);
    }

    @Test void statusesDoNotGuessBusinessMeaningOrLocalResponsibility() {
        for (int status : List.of(400, 401, 403, 404, 429, 500, 503, 302)) {
            var error = assertThrows(PortInvocationException.class,
                    () -> client.required(invocation, () -> request("/status/" + status), this::read, HttpFailures::defaultFailure));
            assertSame(invocation, error.invocation());
            assertEquals(Integer.toString(status), error.transportDetails().get("http.status"));
            assertEquals("5", error.transportDetails().get("http.retryAfter"));
            assertFalse(error.transportDetails().containsValue("secret-cookie"));
            assertFalse(error.transportDetails().containsKey("http.correlationId"));
            assertEquals(status >= 500 ? RemoteIntegrationException.class : PortInvocationException.class, error.getClass());
        }
        assertEquals(8, requests.get()); // no Vernac retry or redirect
    }

    @Test void explicitBusinessMappingIsPreservedWithTypedCodeAndCorrelation() {
        var failure = assertThrows(PortRejectedException.class, () -> client.required(invocation,
                () -> request("/status/403"), this::read,
                (i, response) -> new PortRejectedException(i, Rejection.BLOCKED, "Account blocked",
                        response.failureDetails("X-Correlation-ID"), null)));
        assertEquals(Rejection.BLOCKED, failure.code(Rejection.class).orElseThrow());
        assertEquals("remote-123", failure.transportDetails().get("http.correlationId"));
    }

    @Test void mappingFailuresHaveDirectionalResponsibilityAndKeepCause() {
        var cause = new MappingContractException("toPayload", "$.name", new DomainValidationException("invalid name"));
        var local = assertThrows(LocalIntegrationException.class, () -> client.required(invocation,
                () -> { throw cause; }, this::read, HttpFailures::defaultFailure));
        assertSame(cause, local.getCause());
        assertEquals(IntegrationCategory.INVALID_REQUEST, local.category());
        assertEquals(0, requests.get());
        var remote = assertThrows(RemoteIntegrationException.class, () -> client.required(invocation,
                () -> request("/"), response -> { throw cause; }, HttpFailures::defaultFailure));
        assertSame(cause, remote.getCause());
        assertEquals(IntegrationCategory.INVALID_RESPONSE, remote.category());
    }

    @Test void programmingErrorsAndBusinessFailuresAreNotRelabelled() {
        var defect = new IllegalStateException("broken implementation");
        assertSame(defect, assertThrows(IllegalStateException.class, () -> client.required(invocation,
                () -> request("/"), response -> { throw defect; }, HttpFailures::defaultFailure)));
        var business = new PortRejectedException(invocation, Rejection.BLOCKED, "Rejected");
        assertSame(business, assertThrows(PortRejectedException.class, () -> client.required(invocation,
                () -> request("/"), response -> { throw business; }, HttpFailures::defaultFailure)));
        assertThrows(RemoteIntegrationException.class, () -> client.required(invocation,
                () -> request("/"), response -> null, HttpFailures::defaultFailure));
    }

    @Test void malformedJsonIsSanitizedAndOversizedResponsesAreBounded() {
        var invalid = assertThrows(RemoteIntegrationException.class, () -> client.required(invocation,
                () -> request("/malformed"), this::read, HttpFailures::defaultFailure));
        assertInstanceOf(DtoContractException.class, invalid.getCause());
        assertFalse(invalid.getCause().toString().contains("confidential-response"));
        assertNull(invalid.getCause().getCause());
        var large = assertThrows(RemoteIntegrationException.class, () -> client.required(invocation,
                () -> request("/large"), this::read, HttpFailures::defaultFailure));
        assertEquals(IntegrationCategory.INVALID_RESPONSE, large.category());
        assertEquals("200", large.transportDetails().get("http.status"));
    }

    @Test void concurrentCallsRetainTheirOwnInvocation() throws Exception {
        int count = 8;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            List<Future<UUID>> results = new ArrayList<>();
            for (int n = 0; n < count; n++) {
                var own = AdapterInvocation.create("Catalog", "HttpCatalog", "load").withDetail("request", Integer.toString(n));
                results.add(executor.submit(() -> {
                    start.await();
                    var error = assertThrows(PortInvocationException.class, () -> client.required(own,
                            () -> request("/status/503"), this::read, HttpFailures::defaultFailure));
                    assertSame(own, error.invocation());
                    return error.invocation().invocationId();
                }));
            }
            start.countDown();
            var ids = new HashSet<UUID>();
            for (var result : results) ids.add(result.get(10, TimeUnit.SECONDS));
            assertEquals(count, ids.size());
        }
    }

    @Test void timeoutAndInterruptionRemainCommunicationFailures() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        server.createContext("/blocked", exchange -> {
            try (exchange) {
                entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }
        });
        try {
            var timeoutClient = new HttpAdapterClient(transport, Duration.ofMillis(150), 1024);
            var timeout = assertThrows(IntegrationCommunicationException.class, () -> timeoutClient.required(invocation,
                    () -> request("/blocked"), this::read, HttpFailures::defaultFailure));
            assertEquals(IntegrationCategory.TIMEOUT, timeout.category());
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Thread.currentThread().interrupt();
            var interrupted = assertThrows(IntegrationCommunicationException.class, () -> client.required(invocation,
                    () -> request("/blocked"), this::read, HttpFailures::defaultFailure));
            assertEquals(IntegrationCategory.INTERRUPTED, interrupted.category());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
            release.countDown();
        }
    }

    @Test void timeoutAlsoBoundsAStalledResponseBody() throws Exception {
        var release = new CountDownLatch(1);
        server.createContext("/stalled-body", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('x');
                exchange.getResponseBody().flush();
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }
        });
        try {
            var timeoutClient = new HttpAdapterClient(transport, Duration.ofMillis(300), 1024);
            var failure = assertThrows(IntegrationCommunicationException.class, () -> timeoutClient.required(invocation,
                    () -> request("/stalled-body"), this::read, HttpFailures::defaultFailure));
            assertEquals(IntegrationCategory.TIMEOUT, failure.category());
        } finally { release.countDown(); }
    }

    @Test void rejectsAutomaticRedirectConfiguration() {
        try (var redirectClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()) {
            assertThrows(IllegalArgumentException.class,
                    () -> new HttpAdapterClient(redirectClient, Duration.ofSeconds(1), 1024));
        }
    }
}
