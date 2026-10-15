# HTTP inbound adapter contract

Status: normative contract for HTTP inbound adapters, including 0.4.0 error policies. The application usecase is the
inbound operation contract; this slice adds no duplicate inbound port interface.

## Declaration and responsibilities

```vernac
adapter TourApi inbound http uses CreateTour createTour, ReleaseTour releaseTour {
    base "/api/tours";
    create {
        POST "/";
        body NewTour;
        execute createTour {
            title <- body.title;
            capacity <- body.capacity;
        }
        respond 201 CreatedTour using toCreated;
    }
    release {
        POST "/{id}/release";
        path UUID tourId named "id";
        execute releaseTour { tourId <- path.tourId; }
        respond 204;
    }
    schema NewTour(String title, int capacity);
    schema CreatedTour(UUID id);
    mapping toCreated: CreatedTour <- TourId { id <- source; }
}
```

The example assumes CreateTour(Title, Capacity) returns TourId and
ReleaseTour(TourId) has no result. Dependencies use the shared inferred/explicit
name rules; `uses` has no parentheses and accepts only required usecases. Each
endpoint has a unique explicit Java-compatible name and invokes exactly one of
those dependencies. It cannot load repositories or execute arbitrary Java statements.
Schemas/mappings can appear before or after endpoints. No custom packages.

The generator produces a constructor-injected Spring RestController in
`<namespace>.adapter.inbound.http`, a controller-scoped error advice, a `<Name>Dtos`
holder in its `.dto` subpackage, and `<Name>Mappings` in `.mapping`. The controller
has no transaction annotation; usecase REQUIRED transactions remain authoritative.
Input conversion precedes the usecase invocation. Response mapping occurs after
the usecase returns; its transaction may already have committed. A failed response
does not undo that operation. No automatic retry or idempotency is generated.
Web applications must include Spring MVC, Jackson, `vernac-runtime-http` and its
JSON runtime dependency at matching versions. Controllers require Spring component
scanning that includes their namespace. No authentication/authorization is implied.

## Endpoint clause order

1. Explicit endpoint name and opening brace.
2. GET, POST, PUT, PATCH or DELETE plus a literal route and semicolon.
3. Zero or more path/query/header binding declarations.
4. Optional `body Schema;` (one required JSON body).
5. `execute dependency { parameter <- source [using mapping]; ... }`.
6. `respond status;` or `respond status Schema using mapping;`.
7. Optional `on absent respond status;`, then closing brace.

Base/route paths start with `/`, contain no scheme/host, query, fragment, whitespace,
control characters or literal dot segments. Wildcards and regex path constraints are not supported. Base paths have no placeholders. Route
`/` means the base resource itself, without appending a trailing slash. Placeholder
names and bindings must match exactly. Route duplicates are checked across the
project, including placeholders renamed without changing a route's shape. Runtime
Spring mapping still governs overlaps with handwritten controllers or other modules.

## Inputs and ACL

Bindings are typed transport values, not domain objects:

```vernac
path UUID tourId named "id";
query String? search named "q";
header String tenant named "X-Tenant";
```

Names may be omitted when the shared type-derived default is useful and unambiguous.
`named` overrides the external HTTP name. Names are scoped to path/query/header;
headers are compared case-insensitively for duplicate detection. Paths are always
required. Query/header values are required unless optional; optional primitive
bindings must use their boxed type. Collections/domain enums are not wire bindings.

Supported wire types are the approved scalar catalog except EventId. UUID uses the
full canonical hyphenated shape (hex is case-insensitive); boolean accepts only
`true`/`false`. Numbers use their Java scalar parsers, currency uses ISO codes, time
values use Java ISO parsers, and Month/DayOfWeek use their Java uppercase names.
ZoneId/ZoneOffset use Java IDs. String is unchanged; char is one UTF-16 code unit.
Values are decoded once by Spring; there is no additional normalization. Invalid
wire input is 400 and the runtime exception does not capture the supplied value.

Sources in execute assignments are `path.name`, `query.name`, `header.name`, `body`
or `body.field...`. Targets are actual usecase parameter names. Every required
parameter is assigned once; omitted optional parameters are absent. Unknown fields,
duplicate assignments, incompatible types and optional-to-required assignments fail
compilation. PATCH instructions need an explicit application-value mapping.

Assignments reuse the existing ACL conversion rules: exact types, exact primitive/
boxed counterparts, and required single-value wrapping/unwrapping. UUID to an ID
uses Id.of; String to a String VO uses its validated factory. No text-to-UUID,
unit, enum-code or numeric conversion is inferred by an assignment. Declaring the
path as UUID makes its transport parsing explicit. Complex/nested transformations
use an adapter-local mapping, inline Java or `implemented by` under the existing
[mapping contract](adapter-mappings.md).

DTO codecs tolerate additional JSON fields, reject malformed/missing required values,
and preserve optional/PATCH semantics. GET bodies are rejected. A body schema is
always required; optional whole bodies are not part of this slice.

## Results

There is no inferred adapter Result. The result is exactly the type declared by the
usecase. Void operations have no response mapping; value-returning operations require
one. Success statuses are 2xx. Status 204/205 cannot carry a body.

For `returns (TaskId, Title? note)`, the explicit type `<Usecase>.Result` (or its fully
qualified Vernac name ending `.Result`) is available as a mapping **source** when that
usecase is injected into this adapter. Its components are read through their normal
getters; optional components remain optional. Result is not an ordinary domain type,
DTO declaration, mapping target or something inferred from the Java execute body.

An optional single result requires `on absent respond 204;`, `404;` or `410;`.
Absence does not call the response mapper. A present result is mapped normally. A
malformed present value never becomes absence. No Location header is inferred for 201.

## Error defaults and extension

Every generated adapter has its own scoped RestControllerAdvice. Defaults do not
install a global handler for arbitrary handwritten controllers. Generated advice
has Spring order 0; application advice with a higher priority can deliberately
override it. Request/response schemas explicitly select application/json.

| Failure | Status / stable code |
| --- | --- |
| Invalid JSON, missing required binding, invalid typed wire value | 400 / INVALID_REQUEST |
| DomainValidationException during input conversion or usecase execution | 422 / DOMAIN_VALIDATION |
| AggregateNotFoundException | 404 / NOT_FOUND |
| OptimisticLockingFailureException | 409 / OPTIMISTIC_LOCK_CONFLICT |
| PortRejectedException | 422 / the declared rejection enum constant name |
| Other VernacDomainException | 422 / DOMAIN_REJECTED |
| Response-mapping failure, BehaviorContractException, other technical/programming error | 500 / INTERNAL_ERROR |

Bodies contain `code` and a safe `message`; exception messages and stack traces are
not automatically published. 5xx failures are logged with their cause. Domain rejection
messages are deliberately generic by default. Custom policies may deliberately supply
public, application-specific text. Authentication/authorization failures handled by
Spring Security filters remain the application's security configuration responsibility.
Framework failures before a matching controller is selected retain Spring behavior.

At adapter scope, the optional final clause
`errors implemented by example.web.ApiErrors;` replaces the policy. That class must
provide a public static method:

```java
public static InboundHttpErrors.Reply handle(String endpoint, Exception error)
```

Use `org.vernac.runtime.http.inbound.InboundHttpErrors`. Reply contains status (400–599),
code and public message, all required. Return `InboundHttpErrors.defaultReply(error)`
to retain defaults for unhandled cases. A failing/null custom handler yields a safe 500.
Endpoint names identify failures inside an endpoint; pre-invocation MVC binding errors
use `request`. Request headers/bodies are not automatically supplied to the policy.
Response-mapping errors are exposed as ResponseMappingFailure with their original
cause, so a default fallback cannot mislabel them as a domain rejection.

No retries, OpenAPI, generated security policy, streaming or
asynchronous endpoints are added in this slice. Handwritten Spring adapters remain
available when an application's protocol needs more control.

## Verification

Compiler tests reject invalid dependencies, routes, bindings, arguments, result
mappings and optional-result policies. Spring MockMvc tests compile the generated
controllers/codecs/mappings and execute valid requests, absence, error defaults and
an external error policy. They require no database. Existing usecase/persistence
tests remain responsible for transaction and database behavior. LSP tests cover local
DTO, input and mapping completion/navigation; generated Java origin navigation and
inline mapping projections are covered by compiler tooling tests.

## Completed pages and cursor failures

Usecase results may contain materialized `offset`, `counted offset` and `cursor`
value-list components. Map them to corresponding page fields in a named local DTO;
`using <mapping>` applies to entries while metadata is copied unchanged. Query/header
numbers and strings still map into explicitly declared domain inputs. No lazy query
or serialization-time database work is introduced.

InvalidCursorException maps to HTTP 400 with code INVALID_CURSOR; its safe reply asks
for a new traversal and does not expose a token, key or internal exception message.
Ordinary domain validation stays 422 and optimistic locking stays 409. This applies
to blank, malformed or incompatible cursors, not to arbitrary domain exceptions.
See the [pagination tutorial](../tutorials/pagination.md) for a complete board/GET model.

## Declarative inbound error policies (0.4.0)

An adapter or endpoint may declare `errors { ... }` instead of
`errors implemented by fully.qualified.JavaClass;`. At endpoint scope, the clause
follows `respond` and optional `on absent`; the adapter clause follows all endpoints,
schemas and mappings. At least one rule is required. The two policy forms are
mutually exclusive at each scope. Endpoint Java hooks use the same static
`handle(String endpoint, Exception error)` signature as adapter hooks.

```vernac
errors {
    when rejection ShippingRejection.DESTINATION_NOT_SUPPORTED then respond 422;
    when integration remote TIMEOUT then respond 503
        "DEPENDENCY_UNAVAILABLE" "A required external service is temporarily unavailable.";
    when integration remote INVALID_RESPONSE then respond 502
        "DEPENDENCY_INVALID_RESPONSE" "A required external service returned an invalid response.";
    when optimistic conflict then respond 409;
    otherwise default;
}
```

| Selector | Matches |
| --- | --- |
| `rejection EnumType.CONSTANT` | PortRejectedException carrying that exact enum value; enum type is part of identity |
| `integration local CATEGORY` | LocalIntegrationException with that IntegrationCategory |
| `integration remote CATEGORY` | RemoteIntegrationException with that IntegrationCategory |
| `integration unknown CATEGORY` | PortInvocationException with unassigned responsibility and that category |
| `invalid request` | Malformed JSON, binding or typed wire value |
| `invalid cursor` | InvalidCursorException |
| `validation` | DomainValidationException, excluding InvalidCursorException |
| `not found` | AggregateNotFoundException |
| `optimistic conflict` | OptimisticLockingFailureException |
| `domain rejection` | Any VernacDomainException, including validation, absence and typed rejection |

Rules are tested in declaration order. The first match wins. Endpoint rules run
before adapter rules; when none matches, evaluation continues at adapter scope,
then to the existing safe defaults. An endpoint implemented-by policy is authoritative
and returns the final reply; it does not implicitly fall through to adapter rules. Optional `otherwise default;` explicitly names
that final fallback and does not prevent inheritance. Duplicate selectors within a
scope are errors. Overlap between different selectors is intentional; put specific
rules before broad `domain rejection` rules. No automatic specificity reordering.

`then respond STATUS;` changes only the status of the safe default reply. Alternatively,
`then respond STATUS "PUBLIC_CODE" "Public message";` replaces all three values.
Status must be 400–599. Code and message must both be supplied, nonblank, or both
omitted. String escapes follow the existing DSL string-literal rules. Replies retain
the existing JSON `{ "code": "...", "message": "..." }` format; no arbitrary payload,
expression, header, retry or exception-class selector is added.

Enum types/constants resolve through namespace/import rules. Categories are the
existing IntegrationCategory constants, not implicitly declared codes. A typed
rejection currently requires PortRejectedException and its AdapterInvocation; do not
invent an adapter context for a rejection originating directly in a domain model.
A general typed domain-rejection mechanism is outside this feature.

HTTP status does not determine failure responsibility. For example, an explicitly
remote timeout can be represented by 503 while retaining its original exception,
category and adapter invocation for diagnosis. No blanket mapping of integration
failures to 503 is added. Public messages must not contain secrets or upstream
payloads. Internal exception messages are never copied automatically. 5xx responses
retain the existing logging of their cause; no incident identifier is introduced.

Only boundary-owned invocation and recognized input-mapping wrappers are unwrapped
for classification. ResponseMappingFailure is never traversed: a domain exception
raised while producing a response remains a technical response failure. Arbitrary
Java cause chains are not searched. InvalidCursorException is separate from generic
validation but can be intentionally matched by broad `domain rejection`.

HTTP advice translates errors after usecase invocation has failed. It neither retries
nor changes REQUIRED transaction boundaries or rollback rules. Input failures occur
before the usecase; response failures may occur after commit. MVC errors before an
endpoint invocation use endpoint `request`, so only adapter rules apply. Application
security filters remain outside these scoped controller policies.

Compiler diagnostics, ordered generated policies, safe runtime classification,
Java 21 compilation, MockMvc execution and enum/category editor references are tested.

## Server-rendered MVC boundaries (0.5.0 development)

`adapter ... inbound mvc` shares this contract's routes, explicit usecase dependencies,
scalar/VO input conversion and error selectors, while returning templates or redirects
instead of JSON DTOs. It adds URL-encoded scalar form bindings, defaults, sensitive
input exclusion and explicit page redisplay. See the [MVC contract](inbound-mvc.md).
The JSON `inbound http` form continues to reject MVC-only clauses.
