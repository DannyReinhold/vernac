package org.example.delivery.demo;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.example.carrier.adapter.outbound.CarrierHttp;
import org.example.carrier.domain.*;
import org.vernac.runtime.http.HttpAdapterClient;
import org.vernac.runtime.ports.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Same executable walkthrough for the application runner and the database-free test. */
@Component
public final class PortDemoWalkthrough {
    private static final Logger log = LoggerFactory.getLogger(PortDemoWalkthrough.class);
    public record Report(List<String> verified, int httpRequests) {
        public Report { verified = List.copyOf(verified); }
    }
    public Report run() throws Exception {
        List<String> verified = new ArrayList<>();
        log.info("Starting ports/ACL walkthrough with a local carrier; no real external service is called");
        // Fixture ownership is local to this run, so there is no server/thread left behind.
        try (var server = new LocalCarrierServer(); var transport = HttpClient.newHttpClient()) {
            var http = new HttpAdapterClient(transport, Duration.ofSeconds(3), 4096);
            ShippingCarrier carrier = new CarrierHttp(http, server.baseUri());
            ShippingQuote quote = carrier.quote(shipment("Bremen"));
            verify(verified, quote.reference().equals(QuoteReference.of("Q-42"))
                            && quote.price().amount().equals(new BigDecimal("12.30"))
                            && quote.price().currency().equals(CurrencyCode.of("EUR")),
                    "POST maps domain values into JSON and the nested response into validated values; extra fields are ignored");
            verify(verified, carrier.lookup(quote.reference()).orElseThrow().equals(quote),
                    "GET returns the same quote through the optional domain result");
            verify(verified, carrier.lookup(QuoteReference.of("missing")).isEmpty(),
                    "Explicit on 404 absent returns Optional.empty");
            carrier.cancel(QuoteReference.of("Q /ä?+"));
            verify(verified, server.deletedPath().equals("/api/quotes/Q%20%2F%C3%A4%3F%2B"),
                    "DELETE accepts 204; Unicode and reserved path characters remain one encoded reference");

            var unsupported = expect(PortRejectedException.class, () -> carrier.quote(shipment("unsupported")));
            verify(verified, unsupported.code() == ShippingRejection.DESTINATION_NOT_SUPPORTED,
                    "422 becomes the declared DESTINATION_NOT_SUPPORTED business rejection");
            logFailure(unsupported);
            var blocked = expect(PortRejectedException.class, () -> carrier.quote(shipment("blocked")));
            verify(verified, blocked.code() == ShippingRejection.ACCOUNT_BLOCKED,
                    "A recognized 403 is a business rejection rather than a technical credentials error");
            logFailure(blocked);

            var local = expect(LocalIntegrationException.class, () -> carrier.quote(shipment("credentials")));
            verify(verified, local.category() == IntegrationCategory.AUTHENTICATION && local.getSuppressed().length == 1,
                    "Explicit 401 policy reports local authentication responsibility even with an unreadable error body");
            logFailure(local);
            var unresolved = expect(PortInvocationException.class, () -> carrier.quote(shipment("forbidden")));
            verify(verified, unresolved.getClass() == PortInvocationException.class
                            && unresolved.category() == IntegrationCategory.AUTHORIZATION,
                    "An unrecognized 403 retains unresolved responsibility");
            logFailure(unresolved);
            var remote = expect(RemoteIntegrationException.class, () -> carrier.quote(shipment("unavailable")));
            verify(verified, remote.category() == IntegrationCategory.REMOTE_FAILURE
                            && "30".equals(remote.transportDetails().get("http.retryAfter")),
                    "503 reports a remote failure and preserves Retry-After without retrying");
            logFailure(remote);

            for (String scenario : List.of("broken-json", "missing-field", "invalid-price")) {
                var failure = expect(RemoteIntegrationException.class, () -> carrier.quote(shipment(scenario)));
                verify(verified, failure.category() == IntegrationCategory.INVALID_RESPONSE && failure.getCause() != null,
                        "Invalid success response is rejected with a retained cause: " + scenario);
            }
            var unreadable = expect(PortInvocationException.class, () -> carrier.quote(shipment("broken-error")));
            verify(verified, unreadable.category() == IntegrationCategory.UNEXPECTED_STATUS
                            && "422".equals(unreadable.transportDetails().get("http.status"))
                            && unreadable.getSuppressed().length == 1,
                    "Malformed error JSON preserves HTTP status and adds decoding diagnostics; no false business rejection");
            var missing = expect(PortInvocationException.class, () -> carrier.quote(shipment("missing-code")));
            verify(verified, missing.category() == IntegrationCategory.UNEXPECTED_STATUS && missing.getSuppressed().length == 0,
                    "Absent optional error code produces the default technical failure");
            verify(verified, !unsupported.invocation().invocationId().equals(blocked.invocation().invocationId())
                            && unsupported.invocation().operation().equals("quote")
                            && unsupported.invocation().details().isEmpty(),
                    "Failures carry distinct invocation identities and the operation, without capturing request payloads");
            verify(verified, server.requests() == 14, "Exactly 14 HTTP requests: no implicit retries");
            log.info("Ports/ACL walkthrough passed: {} checks, {} HTTP requests", verified.size(), server.requests());
            return new Report(verified, server.requests());
        }
    }
    private static Shipment shipment(String destination) {
        return Shipment.of(Destination.of(destination), ParcelWeight.of(new BigDecimal("1.250")));
    }
    private static <T extends RuntimeException> T expect(Class<T> type, Runnable call) {
        try { call.run(); }
        catch (RuntimeException failure) {
            if (type.isInstance(failure)) return type.cast(failure);
            throw new IllegalStateException("Expected " + type.getSimpleName() + " but received " + failure.getClass().getSimpleName(), failure);
        }
        throw new IllegalStateException("Expected " + type.getSimpleName() + " but the call succeeded");
    }
    private static void verify(List<String> verified, boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Ports/ACL walkthrough failed: " + message);
        verified.add(message);
        log.info("Verified ports/ACL: {}", message);
    }
    private static void logFailure(PortFailure failure) {
        String code = failure instanceof PortRejectedException rejected ? rejected.code().name()
                : ((PortInvocationException)failure).category().name();
        log.info("Expected {}: code={}, status={}, operation={}, invocation={}", failure.getClass().getSimpleName(), code,
                failure.transportDetails().get("http.status"), failure.invocation().operation(), failure.invocation().invocationId());
    }
}
