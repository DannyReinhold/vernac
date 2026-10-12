# HTTP inbound adapter contract

Status: implemented initial HTTP slice for 0.3.0. The application usecase is the
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

No exception-matching DSL, retries, OpenAPI, generated security policy, streaming or
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
