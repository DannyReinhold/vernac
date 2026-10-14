# Ports, adapters, DTOs and mappings

Status: normative outbound architecture contract for 0.4.0. The linked DTO, mapping,
REST and runtime contracts define exact syntax and APIs. Inbound generators, async
ports and other extensions remain outside this contract.

## Architectural boundary

A port describes a domain capability independently of HTTP, databases, files,
devices or any other implementation technology. Outbound ports become interfaces
in the namespace's domain package. They are explicitly declared.

Parameters and results may use IDs, value objects, enum values and their declared
collections. Entities and aggregates are excluded from the initial port contract.
Operations may return void or an optional value. Existing inferred-name rules
apply; ambiguities require explicit names. Optional results never conceal failures.

An adapter implements the entire port and becomes a Spring bean. A single
implementation needs no extra selection syntax. Multiple implementations require
unambiguous application configuration; the generator must not silently choose one.
Concrete outbound implementations belong below <namespace>.adapter.outbound.
No custom-package override is provided. Access views reside in
`<namespace>.adapter.outbound.access`; DTO holders reside in
`<namespace>.adapter.outbound.dto`.

Ports and adapters neither open nor require a database transaction by default.
A database rollback cannot undo external communication. Calls are synchronous in
the first release; futures and implicit asynchronous execution are deferred.
Singleton adapters must support concurrent calls without shared mutable call state.

Inline behavior and implemented-by code use the same self access contract.
Vernac dependencies use uses; technical dependencies use java uses, with java
imports available for implementation code. Dependencies are constructor-injected
and accessible through self. Generated wrappers delegate to separate behavior
implementations. Handwritten Java port implementations remain an interoperability
option, but do not redefine the meaning of implemented by.

Usecases form the initial inbound application boundary. Dedicated HTTP/MCP inbound
adapter generation remains future work.

## DTOs

Technical schema declarations generate Java records, not domain value objects.
They may contain supported scalar types, nested DTOs and technical collections.
They must not introduce external DTO types into domain port signatures.
Records use NullMarked; constructors enforce structural validity.

Required fields reject missing values and null. Optional ordinary components use
Optional<T>, with nullable inputs in generated of factories. Optional containers
must themselves be non-null. Collections are defensively copied, expose no mutable
backing collection, and initially reject null elements.

Reading JSON:

- Unknown properties are ignored, including inside nested DTOs.
- The policy is scoped to generated DTO handling, not global application settings.
- Missing and null optional fields both represent absence.
- Required missing/null fields fail; primitives never silently become zero/false.
- Empty collections remain present empty collections.
- Wrong types fail without implicit coercion or substituted defaults.

Writing ordinary optional fields omits absent values by default. An explicit
absent as null modifier emits JSON null instead:

```vernac
schema CustomerPayload (
    String name,
    String? additionalInfo,
    String? comment absent as null
);
```

## External names

Default external names equal DTO component names exactly. A schema may explicitly
declare naming snake_case. A component's named "external_name" overrides that
strategy. Names apply symmetrically when reading and writing; Java names and
mapping paths remain unchanged.

```vernac
schema ParcelPayload naming snake_case (
    String trackingNumber,
    BigDecimal weightKg,
    String customerReference named "customer_ref"
);
```

Nested DTOs use their own strategy. There are no inferred aliases or guessed
conventions. Duplicate resulting external names are compiler errors. The exact
Unicode/acronym algorithm is defined in the [DTO contract](adapter-dtos.md).

## Three-state patch fields

An ordinary optional field is not a patch instruction. A patch field has:

| State       | Meaning                       | JSON             |
|-------------|-------------------------------|------------------|
| unchanged() | No instruction for this field | Property omitted |
| clear()     | Remove its value              | Explicit null    |
| set(value)  | Supply a value                | Encoded value    |

```vernac
schema CustomerPatch (
    patch String name,
    patch String? comment
);
```

patch permits an omitted instruction; ? permits clearing the target value.
name allows unchanged/set but rejects clear. comment allows all three states.
JSON decoding enforces the same distinction.

Java components use PatchField<T>, not Optional<PatchField<T>>. Containers are
non-null, set (null) is rejected, and generated constructors reject clear for a
non-clearable field. Factories take explicit wrappers, not nullable parameters.
PatchField is an immutable technical runtime value with meaningful equality,
hashCode and toString; its concrete Java declaration remains an implementation detail.

patch is initially restricted to DTOs. Combining patch with absent as null is a
compiler error. set (emptyCollection) differs from clear (). Setting a field replaces
its whole value in this initial contract. Recursive object patches and list edit
operations are not implicitly generated.

The remote API must actually support the declared wire semantics. HTTP PATCH
alone does not guarantee these meanings. Decoding a DTO never automatically
mutates an aggregate; usecases and domain behavior apply business changes.

## Mappings

Targets appear on the left and sources on the right:

```vernac
mapping toParcelPayload:
    ParcelPayload <- ParcelDescription {
    weightKg <- source.weight;
}
```

Declarations are order-independent. Each mapping is either declarative or Java,
not a mixture. Named nested mappings and element-wise collection mappings are
supported by the target design. No automatic unit conversion, normalization,
lossy conversion or arbitrary constructor selection is allowed.

Single-value objects can be wrapped/unwrapped when the types identify an
unambiguous compatible conversion. Domain construction uses validated factories.
Unknown external enum codes fail unless explicitly translated.
All required target fields must be assigned; optional unassigned targets are
absent. Unused source fields are permitted. Duplicate target assignments fail.
An optional source cannot silently satisfy a required target.

Patch-to-patch mappings preserve unchanged/clear/set. Contained values are
converted only for set. A potentially clearing source cannot target a non-clearable
field without explicit handling. Ordinary optional absence is never guessed to
mean clear or unchanged; initial nontrivial transitions require Java mapping.

Mappings convert data; repository lookup and application orchestration belong in
adapter/usecase code. MapStruct integration is a later interoperability exercise.

## Errors and responsibility

Use existing VernacDomainException and VernacTechnicalException bases.
Working subtype names:

- PortRejectedException: explicitly recognized business rejection.
- LocalIntegrationException: established local integration problem.
- RemoteIntegrationException: remote failure or response contract violation.
- IntegrationCommunicationException: communication failed, attribution uncertain.

Technical subtypes share a PortInvocationException base. Exact API names are
finalized with implementation. Observation and responsibility are distinct: a
status code alone cannot establish who must correct a problem.

Business codes are explicitly declared Vernac enum values. Technical categories
come from the runtime. Codes must remain structured and type-identifiable;
messages are never parsed for control flow. Example:

```vernac
value ShippingRejection =
    DESTINATION_NOT_SUPPORTED | SHIPPING_ACCOUNT_BLOCKED;
```

Exceptions carry port, adapter, operation, invocation ID, category/code and cause.
HTTP status, remote error codes, correlation IDs and Retry-After are optional
transport details. Selected request details can be added deliberately; whole
payloads, credentials and parameter object graphs are not captured automatically.
The invocation context is per call, not mutable singleton bean state.

Malformed successful responses, missing required fields and domain-validation
failures during response mapping are invalid-response integration errors with
location information and preserved causes, never Optional.empty ().

## Declarative HTTP error translation

```vernac
errors {
    response CarrierError;

    when status = 422
        and response.code = "DESTINATION_NOT_SUPPORTED"
    then reject ShippingRejection.DESTINATION_NOT_SUPPORTED
        "The destination is not supported";

    when status = 401
    then fail local AUTHENTICATION
        "Carrier credentials were rejected";

    otherwise default;
}
```

The external string and the domain enum code are separate concepts.
Rules run top-to-bottom, first match wins. Conditions support typed comparisons,
and/or/not and parentheses, without arbitrary Java expressions. No matching rule
uses the technical default; spelling otherwise default is optional.

Unparseable error bodies must preserve the original HTTP failure. Status-only
rules can still match; a rule depending on an unavailable response value does not
match, including through negation. Exact unavailable-value evaluation is specified in the
[implemented REST contract](declarative-rest.md). Error decoding details remain available diagnostically.

on 404 absent is explicit and valid only for optional operation results.
Contradictory shortcut and full rules are rejected.
Error translation is either declarative or errors implemented by a Java class
per operation. The Java hook receives invocation context, operation arguments and
transport failure, and returns the exception to throw. It can use default handling.
Recognized business exceptions are not subsequently rewrapped as technical errors.

Non-HTTP adapters use the same error foundations from their Java behavior.
No generic device-protocol expression language is required.

## Retry and observability

Business/technical classification does not determine safe retry. A timeout may
occur after a remote action succeeded. The initial REST adapter performs no
automatic retries. Hints such as Retry-After do not establish idempotency.
Existing deferred event delivery policy is not implicitly changed by this design;
explicit coordination is a follow-up to avoid inappropriate or multiplied retries.
Structured exception data supports monitoring without forcing duplicate logging
at every layer.

## Implementation status: ports and DTOs

Port interfaces and manual outbound adapters are implemented. The concrete syntax
is adapter Name implements Port [uses ...] { behavior { ... } }. The generated
bean package is <namespace>.adapter.outbound and its access-view package is
<namespace>.adapter.outbound.access. The per-call runtime context is named
AdapterInvocation. See [the tutorial](../tutorials/outbound-ports.md).

Adapter-local DTO records, strict JSON handling, naming strategies and PATCH are
implemented in the [DTO contract](adapter-dtos.md) and [tutorial](../tutorials/adapter-dtos.md).
Typed declarative/Java mappings are implemented; see the [mapping contract](adapter-mappings.md).
The shared port exception hierarchy and synchronous HTTP execution are implemented
in [the HTTP runtime contract](http-adapter-runtime.md). Existing Java adapter
behavior can use them now. REST request/response declarations and external Java error translation are implemented
in [the REST contract](declarative-rest.md). Ordered declarative error rules are specified there as well. Manual
behavior still propagates exceptions as-is unless it
explicitly invokes an integration boundary.

## Technical configuration

Adapters MAY declare typed required properties in a `configuration` block before
their implementation. Configuration accessors are part of the adapter access view
and behave identically for inline and external implementations. They do not belong
to the port API. See the [configuration contract](adapter-configuration.md).
