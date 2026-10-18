# Outbound HTTP runtime and integration failures

Status: implemented runtime foundation. [REST declarations](declarative-rest.md)
now generate request/response bindings and support external Java error translation.
Ordered declarative error rules are implemented. Java adapter behavior can also use this API. See the [executable tutorial](../tutorials/http-adapter-runtime.md).

## Dependencies and scope

`vernac-runtime` owns transport-independent integration exceptions.
`vernac-runtime-http` depends on `vernac-runtime-json` and uses the JDK HTTP client.
HTTP/JSON dependencies do not leak into the domain runtime. Applications add the
HTTP module explicitly, at the same version as the compiler. No Spring starter,
autoconfiguration, database transaction, retry scheduler or new DSL keyword is
introduced by this step.

A generated adapter receives an application-managed `HttpAdapterClient` through
`java uses`, just like other technical dependencies. Inline and `implemented by`
behavior retain the same `self` access contract. A client is reusable across
concurrent calls; invocation/response/error data is call-local. The application
owns and closes the underlying JDK `HttpClient`.

## Structured failures

| Type | Meaning |
| --- | --- |
| `PortRejectedException` | Explicitly recognized business rejection; extends `VernacDomainException` |
| `PortInvocationException` | Technical integration failure with responsibility not established |
| `LocalIntegrationException` | Established local integration problem |
| `RemoteIntegrationException` | Remote failure or response-contract violation |
| `IntegrationCommunicationException` | Communication failed; remote outcome/responsibility may be unknown |

The last three extend `PortInvocationException`, which extends
`VernacTechnicalException`. All implement `PortFailure`, exposing `invocation()`
and an immutable `transportDetails()` map. `AdapterInvocation` supplies the
invocation UUID, fully qualified port/adapter names, operation and deliberately
selected request details. Its `withDetail` method returns a new context.

Technical exceptions have `category()` of type `IntegrationCategory`:
CONFIGURATION, INVALID_REQUEST, INVALID_RESPONSE, AUTHENTICATION, AUTHORIZATION,
RATE_LIMITED, REMOTE_FAILURE, UNEXPECTED_STATUS, TIMEOUT, COMMUNICATION, INTERRUPTED.
Observation, responsibility and safe retry remain separate questions.

Business rejections carry an actual enum constant, not an ad-hoc string code.
`code()` retains its declaring enum type. `code(MyRejection.class)` returns an
Optional of that type and is empty for another enum, even with an identical
constant name. Transport-independent failures can be used by filesystem, device,
other-database and other adapters without HTTP classes.

No exception automatically captures parameters, request URI/query, credentials,
cookies, payloads or all response headers. HTTP failure metadata contains only
`http.status` and, when present, `http.retryAfter`. This is raw advisory information,
not a retry instruction. A named correlation header may explicitly be copied to
`http.correlationId`. Select a non-secret header. Application-provided messages,
details and causes remain the application's responsibility.

## HTTP call contracts

`HttpAdapterClient` takes an application-owned JDK client, a positive default
request timeout and a positive maximum buffered response size in bytes. Automatic
redirects must be disabled. Endpoint/authentication/TLS/proxy configuration belongs
to application configuration. No URLs or credentials are embedded in generated code.

| API | Successful response | Non-success response |
| --- | --- | --- |
| `required` | Decode/map a 2xx response to a non-null result | Run error handler |
| `optionalOn404` | Decode/map a 2xx response, wrap in Optional | 404 alone becomes absence; otherwise error handler |
| `execute` | Any 2xx succeeds; ignore bounded body | Run error handler |

These methods accept a request supplier and an error handler. Keeping construction,
DTO creation, mapping and encoding inside the supplier allows the request boundary
to classify known contract failures correctly. GET, POST, PUT, PATCH and DELETE
are forwarded as requested; the runtime does not translate PATCH into POST.

An optional port without a 404 policy can use `Optional.of(required(...))`.
Successful empty bodies, JSON null and malformed data are never implicitly absent.
A void operation deliberately does not decode its response; a 204 cannot satisfy
a required JSON value. This initial runtime accepts all 2xx success statuses;
operation-specific success sets can be considered with REST syntax if needed.

The response limit applies while bytes arrive, also without Content-Length. It
applies to success and error bodies. Exceeding it aborts reception and raises
INVALID_RESPONSE with the received status; normal error-body mapping does not run
for this resource-limit failure. `HttpResponseData` defensively copies body bytes;
its toString includes status/size, never payload or headers.

A request's explicit timeout wins; otherwise the configured default applies.
The whole HTTP future, including the response body, is bounded. Relying only on
JDK `HttpRequest.timeout` did not bound a stalled body in the tested environment.
Timeout cancels the future. Interruption cancels it and restores the thread's
interrupt flag. Both leave the remote operation's outcome uncertain. Request
preparation and application decoding/mapping are outside this network deadline.

The public API is synchronous even though the implementation uses `sendAsync`
plus a bounded wait to cover the body. No application futures escape the runtime.
Vernac makes one transport call and has no retry loop. JDK-level transport recovery
is not an exactly-once delivery guarantee. A database rollback cannot undo a remote
action; use explicit idempotency/protocol design where required.

## JSON and mapping boundaries

`HttpJson` owns a private, preconfigured ObjectMapper; it does not change the
application's mapper. Generated DTO annotations provide strict types, required
fields, unknown-property tolerance, optional and PATCH semantics. No guarantee of
those semantics is made for arbitrary handwritten Jackson classes.

JSON responses require `application/json` or `application/*+json`, ignoring case
and optional media-type parameters. Missing or different media types fail. Empty
input, JSON null and trailing JSON values fail. A generated DTO contract failure
retains its field path. Raw Jackson parser errors are replaced with a sanitized
contract error so source excerpts are not copied into exceptions. A known generated
mapping or DTO failure retains its useful cause; arbitrary programmer exceptions
are not blanket-converted into integration failures.

| Boundary | Known contract failure | Result |
| --- | --- | --- |
| Request supplier | DTO, mapping or domain validation | Local INVALID_REQUEST, original cause |
| Successful response decoder/mapper | DTO, mapping or domain validation | Remote INVALID_RESPONSE, status and original cause |
| Successful decoder returns null | Result-contract failure | Remote INVALID_RESPONSE |
| Error handler | Explicit business/technical exception | Returned exception is thrown unchanged |
| Arbitrary implementation defect | E.g. IllegalStateException | Propagates unchanged |

JSON syntax errors are sanitized as described above rather than preserving raw
parser excerpts. Exceptions emitted deliberately by behavior are not subsequently
converted into a different business/technical category.

## Default status handling and error bodies

| Status | Default |
| --- | --- |
| 401 | Technical AUTHENTICATION, responsibility unassigned |
| 403 | Technical AUTHORIZATION, responsibility unassigned |
| 429 | Technical RATE_LIMITED, responsibility unassigned |
| 5xx | Remote REMOTE_FAILURE |
| Other non-2xx, including 3xx/400/404 | Technical UNEXPECTED_STATUS, responsibility unassigned |

Only explicit error translation creates a business rejection or assigns an
authentication/authorization failure to local configuration. `HttpFailures.defaultFailure`
provides these defaults. It does not parse error bodies and never retries.

`HttpJson.errorBody` returns `DecodedError<T>`. A malformed/missing/unusable body
has no value and supplies a sanitized decoding failure. It does not replace the
original HTTP status failure. A Java handler can attach the decoding failure as a
suppressed exception and still match status-only conditions. Unrelated application
programming errors propagate rather than being treated as unavailable wire data.

`ErrorCondition` defines three-valued evaluation for declarative rules:

| A | B | A AND B | A OR B |
| --- | --- | --- | --- |
| true | true | true | true |
| true | false | false | true |
| true | unavailable | unavailable | true |
| false | true | false | true |
| false | false | false | false |
| false | unavailable | false | unavailable |
| unavailable | true | unavailable | true |
| unavailable | false | false | unavailable |
| unavailable | unavailable | unavailable | unavailable |

NOT unavailable is unavailable; only true matches a rule. Thus a condition relying
on unavailable body data cannot match merely through negation or inequality. An
independently true status branch in OR can still match. A decoded error's `test`
method offers this distinction for a Java predicate over the available DTO.

`optionalOn404` handles 404 before the error handler. The compiler rejects
contradictory declarative 404 rules; this runtime Java API cannot inspect
arbitrary handler code.

## Verification and next step

Runtime tests use a loopback HTTP server with no Docker/external service. They
cover methods, payloads, 404, status classification, typed rejection, selected
metadata, concurrency, response limits, timeouts through body reception,
interruption and programmer-error propagation. The Maven execution test compiles
and executes the tutorial's generated adapter, DTOs and mappings against HTTP.

Implemented next layer: a shared resolved REST model, typed request/response
bindings, external Java errors and LSP references; see the REST contract.
Ordered declarative error rules use this runtime's truth model; their compiled
tutorial includes status-only fallback and unavailable-body tests.

External implementation targets also follow the 0.5.0
[shared Java implementation resolution rules](implementation-contracts.md).
Their mapping/error-handler calling convention is unchanged.
