# Declare an outbound REST adapter

The domain port stays transport-independent. Its adapter declares HTTP requests
and uses the same DTOs and mappings as Java-backed adapters. This is outbound HTTP;
it does not generate Spring controllers.

Add `org.vernac:vernac-runtime-http` at the same version as the compiler. Put the
following model in `src/main/vernac/example/catalog/catalog.vernac`:

```vernac
namespace example.catalog;

value Title(String) validates {
    require(!self.string().isBlank(), "Title must not be blank");
};
value Lookup(Title key, Title? locale);
value CatalogRejection = BLOCKED;

port CatalogSource {
    Title load(Lookup lookup);
    Title? lookup(Title key);
    void rename(Title title);
    void patchTitle(Title title);
}

adapter RestCatalog implements CatalogSource {
    rest {
        client http;
        base baseUri;

        Title load(Lookup lookup) {
            GET "/items/{key}";
            path "key" <- lookup.key;
            query "locale" <- lookup.locale;
            header "X-Locale" <- lookup.locale;
            response Payload using toTitle;
            errors implemented by example.catalog.CatalogErrors;
        }

        Title? lookup(Title key) {
            GET "/items/{key}";
            path "key" <- key;
            response Payload using toTitle;
            on 404 absent;
        }

        void rename(Title title) {
            PUT "/items/current";
            body <- title using toPayload;
        }

        void patchTitle(Title title) {
            PATCH "/items/current";
            body <- title using toPatch;
        }
    }

    schema Payload(String title);
    schema TitlePatch(patch String title);
    schema CarrierError(String code);

    mapping toTitle: Title <- Payload { string <- source.title; }
    mapping toPayload: Payload <- Title { title <- source.string; }
    mapping toPatch: TitlePatch <- Title {
        java imports {
            org.vernac.runtime.ports.PatchField;
            example.catalog.adapter.outbound.dto.RestCatalogDtos.TitlePatch;
        }
        java { return TitlePatch.of(PatchField.set(source.string())); }
    }
}
```

Definitions can precede or follow the REST block. The adapter implements every
port operation. Method signatures and inferred parameter names follow the normal
port conventions; explicit names are useful when parameters have different roles.

The `client http` declaration generates a constructor dependency and an access
method named `http()` of type `HttpAdapterClient`. `base baseUri` does the same for
`java.net.URI`. These names identify dependencies, not URLs or configuration
values. Provide the two Spring beans as shown in the
[HTTP runtime tutorial](http-adapter-runtime.md). The URI might be
`http://localhost:8088/api/v1/`; `/items/{key}` then addresses `/api/v1/items/...`.
A leading slash on the route does **not** discard the configured base path.

`Lookup.key` unwraps its single-value `Title` automatically. An absent locale
omits both the query parameter and the header. Paths require present values.
Each value is separately URL encoded: slash, question mark, ampersand, plus,
percent signs and Unicode remain data. Do not pre-encode domain values.

`rename` maps the domain value to a DTO and encodes it as JSON. `Content-Type` is
supplied automatically. `patchTitle` really sends PATCH; its DTO carries explicit
PATCH intent. An ordinary optional field is never guessed to mean clear or
unchanged. The example's Java mapping explicitly chooses `PatchField.set`.

## Supply the error translation

Place this handwritten Java class outside generated sources:

```java
package example.catalog;

import example.catalog.domain.CatalogRejection;
import example.catalog.domain.Lookup;
import example.catalog.adapter.outbound.access.RestCatalogAccess;
import example.catalog.adapter.outbound.dto.RestCatalogDtos.CarrierError;
import org.vernac.runtime.VernacException;
import org.vernac.runtime.http.HttpFailures;
import org.vernac.runtime.http.HttpJson;
import org.vernac.runtime.http.HttpResponseData;
import org.vernac.runtime.ports.PortRejectedException;

public final class CatalogErrors {
    private CatalogErrors() {}

    public static VernacException load(
            RestCatalogAccess self, Lookup lookup, HttpResponseData response) {
        var error = HttpJson.errorBody(response, CarrierError.class);
        if (response.status() == 403
                && error.test(value -> value.code().equals("ACCOUNT_BLOCKED")).matches()) {
            return new PortRejectedException(self.invocation(), CatalogRejection.BLOCKED,
                    "Catalog account is blocked", response.failureDetails("X-Correlation-ID"), null);
        }
        var failure = HttpFailures.defaultFailure(self.invocation(), response);
        error.decodingFailure().ifPresent(failure::addSuppressed);
        return failure;
    }
}
```

The static hook has the operation's name and receives `self`, its parameters and
then the response. It returns the exception to throw. Optional operation parameters
are Optional in this hook, as in ordinary adapter behavior. It is not a Spring
bean. Domain `uses` dependencies and invocation metadata remain available on self.
Do not log or capture whole requests automatically.

`lookup` has the explicit 404 absence policy; other non-2xx responses use the
runtime defaults. A required result never turns 404 into absence. If an operation
combines `on 404 absent` with an external error hook, 404 is handled first and does
not reach the hook. Successful malformed responses still fail as INVALID_RESPONSE.

Use [declarative error rules](declarative-errors.md) for common status/body mappings.
The Java hook remains available for custom policy without exposing HTTP in the
port's domain signatures.

## What the compiler checks

Missing or extra path bindings, duplicate binding names (case-insensitive for
headers), unknown source fields, optional path/body values, unsupported scalar
projections, incompatible mappings, missing response mappings, non-optional 404
absence and transport-managed headers produce Vernac diagnostics before javac.
The compiler never guesses enum wire codes or uses a collection's toString.
Select a scalar field or use explicit Java behavior for more complex protocols.

The initial declaration order inside an operation is HTTP verb/route, path/query/
header bindings, optional body, optional response, optional 404 rule, optional error
hook. Schemas/mappings are order-independent across the adapter. See the
[REST contract](../contracts/declarative-rest.md) for the exact boundaries.

## Verification

`RestAdapterExecutionTest` compiles this model and the Java error hook and exercises
them against a loopback server. It checks UTF-8/URL encoding, optional omission,
validated DTO conversion, PUT, PATCH, typed rejection and 404 behavior without Docker.
`RestProjectTest` covers invalid declarations. The LSP supports source-path, mapping
and response-schema completion/navigation, including cross-file domain fields.

Continue with [declarative error translation](declarative-errors.md) to replace Java error hooks with typed rules.
