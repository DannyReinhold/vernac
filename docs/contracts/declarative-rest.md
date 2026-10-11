# Declarative outbound REST adapters

Status: implemented request/response bindings, ordered declarative error rules
and external Java error translation. This extends the
[port/adapter contract](ports-adapters-and-acl.md) and uses the
[HTTP runtime contract](http-adapter-runtime.md).

## Adapter structure

An adapter has either one `behavior` block or one `rest` block. Both implement the
entire port and produce the same Spring bean, access view and behavior companion
in `<namespace>.adapter.outbound`. No custom package or second type-resolution
convention is introduced. DTOs and mappings remain adapter-local and may appear
before or after either block.

```vernac
adapter CatalogHttp implements CatalogSource {
    rest {
        client http;
        base baseUri;
        Title? lookup(ProductId productId) {
            GET "/products/{id}";
            path "id" <- productId;
            response ProductPayload using toTitle;
            on 404 absent;
        }
    }
    // Adapter-local schemas and mappings belong here.
}
```

`client` and `base` declare named, required technical dependencies of types
`org.vernac.runtime.http.HttpAdapterClient` and `java.net.URI`. The application
supplies them through Spring constructor injection. They do not configure a
literal endpoint inside the domain model. Names are checked together with the
adapter's ordinary `uses` dependencies and generated members. Both are accessible
through self in external error handlers. Dependency names are explicit in this
initial transport configuration.

Each operation repeats the port signature, with ordinary inferred/explicit
parameter names and exact type/optionality checking. Public visibility is implicit.
Overloaded operations follow the normal port signature rules. Private REST helpers
and mixed Java/REST operation bodies are not provided; choose a Java-backed adapter
when a protocol needs custom orchestration.

## Operation grammar and order

1. Signature and opening brace.
2. HTTP verb and quoted route, followed by semicolon.
3. Zero or more `path`, `query` and `header` bindings, in any order.
4. Optional `body <- parameter.path using mapping;`.
5. Required response declaration for a value result:
   `response Schema using mapping;`.
6. Optional `on 404 absent;`.
7. Optional `errors { ... }` or `errors implemented by fully.qualified.JavaClass;`.
8. Closing brace.

The supported verbs are GET, POST, PUT, PATCH and DELETE. GET bodies are rejected.
Void results must omit response mappings. Value results require a DTO response and
a mapping whose output exactly matches the port result. An optional port result
without `on 404 absent` still treats 404 as an error and wraps successful mapped
results in Optional. Only optional results can declare absence. Other status codes
cannot be declared absent in this slice.

## Bindings and wire values

Bindings consistently use target on the left:

```vernac
path "id" <- productId;
query "locale" <- request.locale;
header "X-Tenant" <- tenant;
```

The first source segment identifies an operation parameter; subsequent segments
are actual domain fields. Unknown parameters/fields are compiler errors. Required
single-value objects and IDs unwrap automatically to their scalar leaf. Optional
fields/parameters propagate absence; query/header bindings omit absent values.
Path and body sources must be present.

Multi-value objects require explicit field selection for path/query/header use.
Collections and enums are not implicitly serialized or turned into strings. No
automatic enum name/code conversion or unit conversion is inferred. Selected
scalar values use their standard Java textual representation. More specialized
wire formats currently require Java-backed behavior.

Every route placeholder must have exactly one binding and every path binding must
have a placeholder. Repeated use of the same placeholder in a route is permitted.
Names of query/path bindings are case-sensitive; header names are case-insensitive.
Duplicate bindings are rejected. HTTP-managed headers Host, Content-Length,
Connection, Expect and Upgrade cannot be declared. A JSON body supplies
Content-Type automatically, so a conflicting binding is rejected.

Routes are relative paths without authority/scheme, query, fragment or literal
`.`/`..` segments. Base URIs require HTTP(S), a host and no user-info/query/fragment.
The runtime joins the base path with the route even when the route starts with `/`:
base `/api/v1/` plus route `/products/{id}` retains `/api/v1/products/...`.
Use query bindings instead of embedding dynamic query syntax in the route.

Path substitutions are encoded as a single UTF-8 segment; each query name/value is
encoded separately. Domain values must not be pre-encoded. Empty/dot/dot-dot path
values fail at runtime. Invalid dynamic header values fail before sending, without
copying the offending value into the diagnostic. These are local INVALID_REQUEST
failures. This is request correctness, not a general URL authorization policy.

## Bodies, responses and mapping

Request mappings accept exactly the selected source type and return an adapter-local
DTO. Response mappings accept exactly the declared local DTO and return the domain
port result. Mappings can be declarative or Java/implemented-by, using the existing
mapping contract. The REST generator consumes the resolved plan and never guesses
imports/types from strings.

The runtime uses generated DTO JSON codecs. Unknown response fields are tolerated,
wrong/missing required values are not. Optional and PATCH fields retain the existing
DTO semantics. An explicit PATCH operation sends PATCH, but the remote API must
support the chosen DTO's omitted/null/set wire meanings.

Request creation/mapping/encoding occurs inside the runtime's request boundary.
Known failures therefore become local INVALID_REQUEST. Successful response
validation/mapping failures become remote INVALID_RESPONSE and retain their useful
causes. Arbitrary programming defects are not relabelled. Required/optional results
never silently turn malformed success bodies into absence.

## External error translation

The referenced class provides a public static method with the operation's name:

```java
public static VernacException lookup(
        CatalogHttpAccess self, ProductId productId, HttpResponseData response)
```

Parameters appear in operation order. Optional inputs use Optional inside the hook,
consistent with normal adapter behavior. The hook returns a non-null exception to
throw and is not a Spring bean. `self.invocation()` provides the per-call context;
ordinary domain dependencies and technical client/base dependencies remain available.
The generated behavior companion invokes it, preserving the established self model.

The hook only receives non-2xx responses. If `on 404 absent` is present, that explicit
policy runs first; 404 does not invoke the hook. Defaults apply when no hook is
provided. Successful response decoding has its own runtime boundary and is not
rerouted through the HTTP-status hook. The Java hook cannot be inspected for business
correctness; signature errors are reported by javac in the usual Java integration step.

## Declarative error rules

```vernac
errors {
    response CarrierError;
    when status = 422 and response.code = "DESTINATION_NOT_SUPPORTED"
    then reject ShippingRejection.DESTINATION_NOT_SUPPORTED
        "The destination is not supported";
    when status = 401
    then fail local AUTHENTICATION "Check configured credentials";
    otherwise default;
}
```

The response declaration is optional, names an adapter-local schema, and is required
when any rule reads response fields. It is independent of the successful response
schema. Rules run only for non-2xx responses after any explicit 404 shortcut.
At least one rule is required in an errors block. `otherwise default;` is optional;
the default HTTP classification always applies when no rule matches.

Conditions use `not`, `and`, `or` in decreasing precedence; parentheses override
precedence. Comparison/presence predicates bind more tightly than logical operators.
There is no embedded Java in a declarative condition.

| Operand | Supported predicates |
| --- | --- |
| `status` | `=`, `!=`, `<`, `<=`, `>`, `>=` with integer literal 100–599 |
| String DTO field | `=`, `!=` with a string literal; case-sensitive, no normalization |
| Boolean DTO field | `=`, `!=` with `true` or `false` |
| Integral or BigDecimal DTO field | All six comparisons with exact decimal/integer literals, optionally negative |
| DTO field/path | `is absent`, `is present` |

Nested DTO paths are supported. No implicit conversion of strings to numbers, enum
names to codes, or dates to timestamps occurs. Floating-point, date/time and
collection comparisons, header predicates, dynamic messages and PATCH-field
predicates require a Java handler for now. Numeric wire predicates compare exact
numeric values, independent of BigDecimal scale, without conversion to double;
this is wire-number comparison, not domain-VO equality. Messages and string
literals support Java escapes such as `\n`, `\t`, `\\` and `\"`; actual Unicode
characters may be written directly. Invalid escapes produce a source diagnostic.

The runtime decodes the declared error DTO once per failed response. Unknown JSON
properties remain tolerated; invalid JSON/types/missing required fields make the
whole DTO unavailable. Optional missing/null fields are absent in a valid DTO.
Nested absence propagates through the path. Empty strings and empty collections
are present values; `is absent` never means empty.

Only TRUE selects a rule:

- Comparing an absent optional field yields UNAVAILABLE, even for `!=`.
- `is absent`/`is present` explicitly test paths in an available DTO.
- An unavailable whole DTO makes **every** response predicate UNAVAILABLE,
  including `is absent` and `is present`.
- NOT preserves UNAVAILABLE. FALSE AND UNAVAILABLE is FALSE;
  TRUE OR UNAVAILABLE is TRUE. Other unknown combinations remain UNAVAILABLE.
- Thus an independently true status branch may still select a rule, but a malformed
  body cannot turn into a match merely through negation or an absence check.

Every selected exception (including the default) retains sanitized error-decoding
diagnostics as suppressed exceptions. Status, Retry-After and invocation context
remain available. Response payloads and request arguments are not copied into the
failure. There is no automatic logging or retry.

`reject Type.CONSTANT "message"` requires a declared Vernac enum and an existing
constant, resolved through the ordinary namespace/import rules. It produces
PortRejectedException carrying that actual enum value. Codes are never declared
implicitly. `fail local|remote|unknown CATEGORY "message"` produces respectively
LocalIntegrationException, RemoteIntegrationException or PortInvocationException.
CATEGORY must be an existing IntegrationCategory. Choosing responsibility is an
explicit application policy, not a deduction from the HTTP status.

First match wins; overlapping rules are intentional and are not reordered.
With `on 404 absent`, rules must exclude status 404. The compiler conservatively
checks status expressions while treating body predicates as possibly true or false.
For example `status != 404 and response.code = "X"` is accepted; a body-only rule
is rejected because it might otherwise be shadowed. The diagnostic suggests
excluding 404 or removing the shortcut. This check does not inspect external Java
hooks and is not general-purpose proof of rule reachability.

Declarative and implemented-by error handling are mutually exclusive per operation.
No rule changes the classification of successful-response decoding/mapping errors
or transport failures that have no HTTP response.

## Tooling and verification

LSP diagnostics use the compiler contract. Completion/navigation supports operation
source paths (including cross-file value fields), adapter-local mappings and response
schemas. Error rules additionally support nested DTO field and enum-code
completion/navigation, error-schema navigation and technical-category completion.
Generic type completion filters REST signatures like port signatures.
Existing Java mapping injection and generated-Java-to-Vernac operation navigation
also work with REST adapters. The plugin requires rebuilding/reinstalling to bundle
the updated compiler and LSP.

The [tutorial](../tutorials/declarative-rest.md) is compiled and executed by
RestAdapterExecutionTest against a loopback server. RestProjectTest covers invalid
contracts and generated Java navigation; RestSymbolsTest checks editor references.
HttpRequestsTest covers UTF-8/URL encoding, base paths, absence and safe diagnostics.
RestErrorProjectTest and ErrorValuesTest cover rule validation and comparison
semantics. RestErrorExecutionTest compiles the complete
[error tutorial](../tutorials/declarative-errors.md) and executes generated code
against HTTP, including malformed bodies, priority, optional results and void
operations. No Docker or remote service is needed for this test set.
