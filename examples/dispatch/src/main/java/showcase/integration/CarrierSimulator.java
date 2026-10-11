package showcase.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local, explicitly simulated carrier. No real booking or external credentials. */
public final class CarrierSimulator implements AutoCloseable {
  private final HttpServer server;
  private final AtomicBoolean failNext = new AtomicBoolean();

  public CarrierSimulator() throws java.io.IOException {
    this(0);
  }

  public CarrierSimulator(int port) throws java.io.IOException {
    var json = new ObjectMapper();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
    server.createContext(
        "/quotes",
        exchange -> {
          try (exchange) {
            boolean failing = failNext.getAndSet(false);
            var request = json.readTree(exchange.getRequestBody());
            var payload =
                failing
                    ? Map.of("error", "temporary carrier outage")
                    : Map.of(
                        "quoteId",
                        "LOCAL-" + request.path("destination").asText(),
                        "price",
                        Map.of(
                            "amount",
                            request
                                .path("weightKg")
                                .decimalValue()
                                .multiply(new java.math.BigDecimal("0.35")),
                            "currency",
                            "EUR"),
                        "carrierAddedThisField",
                        true);
            byte[] bytes = json.writeValueAsBytes(payload);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(failing ? 503 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
          }
        });
    server.start();
  }

  public URI baseUri() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  public void failNextRequest() {
    failNext.set(true);
  }

  public void close() {
    server.stop(0);
  }
}
