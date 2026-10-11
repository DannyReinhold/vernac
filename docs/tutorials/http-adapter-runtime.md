# Call an HTTP API through a domain port

This step uses existing adapter behavior, generated DTOs/mappings and the new
HTTP runtime. A [declarative REST alternative](declarative-rest.md) is now available.
Ordered declarative error rules are **not implemented yet**. The domain-facing port below remains independent of HTTP.

Add `org.vernac:vernac-runtime-http` at the same version as your Vernac compiler
to the application dependencies. It brings the DTO/JSON runtime transitively.
No HTTP or Jackson dependency is added to the domain runtime.

Place this file under `src/main/vernac/example/catalog/catalog.vernac`:

```vernac
namespace example.catalog;

value Title(String) validates {
    require(!self.string().isBlank(), "Title must not be blank");
};
value CatalogRejection = BLOCKED;

port CatalogSource {
    Title load();
    Title? lookup();
    void rename(Title);
}

adapter HttpCatalog implements CatalogSource {
    behavior {
        java imports {
            java.net.http.HttpRequest;
            org.vernac.runtime.http.HttpJson;
            org.vernac.runtime.http.HttpFailures;
            org.vernac.runtime.ports.PortRejectedException;
            example.catalog.adapter.outbound.dto.HttpCatalogDtos.Payload;
            example.catalog.adapter.outbound.dto.HttpCatalogDtos.CarrierError;
            example.catalog.adapter.outbound.mapping.HttpCatalogMappings;
        }
        java uses {
            org.vernac.runtime.http.HttpAdapterClient http;
            java.net.URI base;
        }

        public Title load() {
            return self.http().required(self.invocation(),
                () -> HttpRequest.newBuilder(self.base().resolve("/catalog")).GET().build(),
                response -> HttpCatalogMappings.toTitle(HttpJson.read(response, Payload.class)),
                (invocation, response) -> {
                    var error = HttpJson.errorBody(response, CarrierError.class);
                    if (response.status() == 403
                            && error.test(value -> value.code().equals("ACCOUNT_BLOCKED")).matches()) {
                        return new PortRejectedException(invocation, CatalogRejection.BLOCKED,
                            "Catalog account is blocked", response.failureDetails("X-Correlation-ID"), null);
                    }
                    var failure = HttpFailures.defaultFailure(invocation, response);
                    error.decodingFailure().ifPresent(failure::addSuppressed);
                    return failure;
                });
        }

        public Title? lookup() {
            return self.http().optionalOn404(self.invocation(),
                () -> HttpRequest.newBuilder(self.base().resolve("/catalog")).GET().build(),
                response -> HttpCatalogMappings.toTitle(HttpJson.read(response, Payload.class)),
                HttpFailures::defaultFailure);
        }

        public void rename(Title title) {
            self.http().execute(self.invocation(),
                () -> HttpRequest.newBuilder(self.base().resolve("/catalog"))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(
                        HttpJson.write(HttpCatalogMappings.toPayload(title))))
                    .build(),
                HttpFailures::defaultFailure);
        }
    }

    schema Payload(String title);
    schema CarrierError(String code);

    mapping toTitle: Title <- Payload { string <- source.title; }
    mapping toPayload: Payload <- Title { title <- source.string; }
}
```

Provide two technical Spring beans in your application configuration:

```java
@Bean(destroyMethod = "close")
HttpClient catalogTransport() {
    return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
}

@Bean
HttpAdapterClient catalogHttp(HttpClient catalogTransport) {
    return new HttpAdapterClient(catalogTransport, Duration.ofSeconds(10), 1_048_576);
}

@Bean
URI catalogBase() {
    return URI.create("http://localhost:8088");
}
```

The snippets use `java.net.URI`, `java.net.http.HttpClient`, `java.time.Duration`,
`org.springframework.context.annotation.Bean` and
`org.vernac.runtime.http.HttpAdapterClient`. Choose an application property for
real endpoint configuration. The application owns and closes the JDK client.
With multiple clients/base URIs, use explicit Spring wiring/selection; Vernac does
not guess the matching bean. The runtime itself is not a Spring bean automatically.

Inject `CatalogSource` into your application. A response like
`{"title":"Notebook","newRemoteField":42}` returns `Title.of("Notebook")`.
The generated DTO ignores the added field. Missing title, wrong JSON types,
malformed JSON and a blank title all fail as invalid responses. A domain
validation failure during the response mapping retains its cause/path.

`lookup()` returns `Optional.empty()` only for the explicitly requested 404 policy.
`load()` instead fails on 404. A successful empty response, JSON null, or invalid
body never becomes absence. `rename()` maps in the opposite direction, sends PUT
with a JSON payload, and accepts any 2xx status (including 204) as void success.
Do not use a payload decoder for a 204 response.

## Recognize a business rejection

The error body uses its own generated DTO. A 403 with code `ACCOUNT_BLOCKED`
becomes `PortRejectedException` with the actual `CatalogRejection.BLOCKED` enum.
The external string is not the domain code. Consumers can use
`failure.code(CatalogRejection.class)` without parsing messages.

Malformed/absent error bodies produce unavailable data, not an empty DTO. The
HTTP status remains available to status-only rules. Negating an unavailable
body comparison does not make it match. The example attaches sanitized decoding
diagnostics as suppressed exceptions to the original status failure.

Neither 401 nor 403 automatically proves a business rejection or a local
configuration mistake. Without explicit translation these are technical failures
with unassigned responsibility. See the [HTTP runtime contract](../contracts/http-adapter-runtime.md).

## Request details and failures

`self.invocation()` is already isolated per call. To deliberately add a safe
identifier, pass `self.invocation().withDetail("catalog", "public")` instead.
Do not add credentials or whole payloads. No request URL, authorization header,
cookies or raw response is automatically attached to exceptions. The explicit
correlation-header selection in this example is for a known non-secret header.

The shared exception hierarchy also works for robot, filesystem or other adapters:

```java
throw new LocalIntegrationException(self.invocation(),
        IntegrationCategory.CONFIGURATION, "Robot calibration is unavailable");
```

These types are in `org.vernac.runtime.ports`; they have no HTTP dependencies.

## Try it and verify it

`HttpAdapterExecutionTest` extracts the first Vernac block from this tutorial,
generates/compiles the Java classes, and calls the adapter against a local HTTP
server. It exercises round-trip DTO mapping, unknown fields, failed validation,
explicit absence and business/technical error translation. No Docker or external
service is needed. `HttpAdapterClientTest` additionally covers HTTP methods,
concurrent calls, bounded response bodies, timeout and interruption.

Continue with [declarative REST generation](declarative-rest.md) to reduce the
request/response boilerplate while retaining the same domain port and failure
semantics.
