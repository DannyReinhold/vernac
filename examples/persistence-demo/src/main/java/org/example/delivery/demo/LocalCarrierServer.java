package org.example.delivery.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Deterministic demonstration fixture, not an application endpoint or production adapter. */
final class LocalCarrierServer implements AutoCloseable {
    private static final String QUOTE = """
        {"quoteId":"Q-42","price":{"amount":12.30,"currency":"EUR","extra":true},"newField":"ignored"}
        """;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> deletedPath = new AtomicReference<>("");

    LocalCarrierServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/quotes", this::handle);
        server.start();
    }
    URI baseUri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/"); }
    int requests() { return requests.get(); }
    String deletedPath() { return deletedPath.get(); }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            requests.incrementAndGet();
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getRawPath();
            if (method.equals("POST") && path.equals("/api/quotes")) {
                if (!"application/json".equals(exchange.getRequestHeaders().getFirst("Content-Type"))) {
                    reply(exchange, 415, "{}"); return;
                }
                JsonNode request = json.readTree(exchange.getRequestBody());
                // Assert the outgoing ACL mapping through the server's real HTTP response.
                if (request == null || request.size() != 2 || !request.path("destination").isTextual()
                        || !request.path("weightKg").isNumber()
                        || request.path("weightKg").decimalValue().compareTo(new java.math.BigDecimal("1.250")) != 0) {
                    reply(exchange, 400, "{}"); return;
                }
                switch (request.path("destination").asText()) {
                    case "Bremen" -> reply(exchange, 200, QUOTE);
                    case "unsupported" -> reply(exchange, 422, "{\"code\":\"UNSUPPORTED_DESTINATION\"}");
                    case "blocked" -> reply(exchange, 403, "{\"code\":\"ACCOUNT_BLOCKED\"}");
                    case "credentials" -> reply(exchange, 401, "not JSON");
                    case "forbidden" -> reply(exchange, 403, "{\"code\":\"UNKNOWN\"}");
                    case "unavailable" -> reply(exchange, 503, "{}");
                    case "broken-json" -> reply(exchange, 200, "not JSON");
                    case "missing-field" -> reply(exchange, 200, "{\"quoteId\":\"Q-42\"}");
                    case "invalid-price" -> reply(exchange, 200, QUOTE.replace("12.30", "-1.00"));
                    case "broken-error" -> reply(exchange, 422, "not JSON");
                    case "missing-code" -> reply(exchange, 422, "{}");
                    default -> reply(exchange, 400, "{}");
                }
            } else if (method.equals("GET")) {
                if (path.equals("/api/quotes/Q-42")) reply(exchange, 200, QUOTE);
                else reply(exchange, 404, "{}");
            } else if (method.equals("DELETE")) {
                deletedPath.set(path);
                exchange.sendResponseHeaders(204, -1);
            } else reply(exchange, 405, "{}");
        }
    }
    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (status == 503) exchange.getResponseHeaders().set("Retry-After", "30");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
    @Override public void close() { server.stop(0); }
}
