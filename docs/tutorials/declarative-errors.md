# Translate remote failures without Java glue

This builds on [declarative REST](declarative-rest.md). Use the same HTTP runtime
and application-provided HttpAdapterClient and URI beans. The following complete
model is compiled and exercised by `RestErrorExecutionTest` against a local HTTP
server; no external API or database is required.

```vernac
namespace example.errors;

value Title(String);
value Rejection = DENIED | MISSING_REASON;

port Catalog { Title load(); Title? lookup(); void ping(); }
adapter CatalogHttp implements Catalog {
    rest {
        client http;
        base baseUri;
        Title load() {
            GET "/catalog";
            response Payload using toTitle;
            errors {
                response Problem;
                when status = 422 and response.code = "DENIED"
                then reject Rejection.DENIED "The request was declined";

                when status = 400 and not (response.code = "KNOWN")
                then reject Rejection.DENIED "Unrecognized business code";

                when status = 409 and response.meta.reason is absent
                then reject Rejection.MISSING_REASON "No reason was supplied";

                when status = 429 and (response.attempts >= 2 or response.permanent = true)
                then fail remote RATE_LIMITED "The remote service is limiting requests";

                when status = 401
                then fail local AUTHENTICATION "Check the configured credentials";

                when status = 403
                then fail unknown AUTHORIZATION "Access was denied; responsibility is unresolved";

                when status = 422
                then fail remote INVALID_RESPONSE "Unrecognized rejection response";

                otherwise default;
            }
        }
        Title? lookup() {
            GET "/catalog";
            response Payload using toTitle;
            on 404 absent;
            errors {
                when status = 401 then fail local AUTHENTICATION "Check credentials";
            }
        }
        void ping() {
            DELETE "/catalog";
            errors {
                when status = 409 then fail remote UNEXPECTED_STATUS "Delete conflict";
            }
        }
    }
    schema Payload(String title);
    schema Problem(String? code, Meta? meta, int? attempts, boolean? permanent);
    schema Meta(String? reason);
    mapping toTitle: Title <- Payload { string <- source.title; }
}
```

The first matching rule wins. The 422 rules demonstrate priority: the known code
becomes a `PortRejectedException` carrying `Rejection.DENIED`; an unfamiliar code
uses the later technical rule. A business code is an actual declared enum value,
not the external string and not a code invented by the mapping.

The explicit 401 rule reflects an application-specific agreement that these
credentials belong to this integration. HTTP 401 alone does not establish that.
403 deliberately leaves responsibility unresolved. Callers can distinguish
`LocalIntegrationException`, `RemoteIntegrationException`,
`PortInvocationException` and `PortRejectedException` without parsing messages.

## Try these responses

| HTTP status and JSON body | Expected outcome |
| --- | --- |
| 200, `{"title":"Notebook"}` | Valid Title |
| 422, `{"code":"DENIED"}` | Business rejection DENIED |
| 422, `{"code":"OTHER"}` | Remote INVALID_RESPONSE |
| 400, `{"code":"OTHER"}` | Business rejection DENIED |
| 400, `{}` | Default unexpected-status failure; missing code does not satisfy NOT |
| 400, invalid JSON | Default status failure with suppressed decoding diagnostic |
| 409, `{}` | Business rejection MISSING_REASON |
| 409, invalid JSON | Default status failure; unreadable is not absent |
| 429, `{"attempts":2}` | Explicit remote RATE_LIMITED |
| 429, `{"permanent":true}` | Explicit remote RATE_LIMITED, even without attempts |
| 401, invalid JSON | Local AUTHENTICATION plus suppressed decoding diagnostic |
| 503, invalid JSON | Default remote failure plus suppressed decoding diagnostic |
| 200, invalid JSON | Remote INVALID_RESPONSE; error rules do not run on success |

Optional fields use three-valued comparisons. Missing data does not compare equal
or unequal to a literal. `not` preserves unknown; `false and unknown` is false,
`true or unknown` is true. Explicit `is absent` checks a successfully decoded DTO.
A malformed whole body makes all response predicates unknown, including absence.

Exception context identifies the port, adapter, operation and invocation. Status
and Retry-After remain available as transport details. No response body, credentials
or request arguments are automatically copied into exceptions or logs. Declarative
messages are fixed text. Use the Java error hook if deliberate, selected enrichment
is needed; choose one error-translation mode per operation.

No rule requests a retry. In particular, rate limiting and communication failures
do not prove that repeating an operation is safe.

For optional results, `on 404 absent` remains explicit. A declarative rule alongside
it must exclude 404 (for example `status != 404 and ...`); otherwise the compiler
reports that the rule could be shadowed. There is no automatic 404-to-empty policy.

See the [REST contract](../contracts/declarative-rest.md) for predicate types,
precedence and limitations. Continue with the [application demo runner](ports-walkthrough.md); its database-free
test and full application mode both exercise the generated HTTP boundary.
