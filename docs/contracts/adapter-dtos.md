# Adapter DTO and JSON contract

Status: implemented. This contract covers transport schemas, not REST invocation
or declarative domain mappings. See [ports and ACL](ports-adapters-and-acl.md)
for the larger design and its implementation status.

## Scope and generated API

A `schema` is local to an outbound adapter. Declarations may appear before or
behind its `behavior` block and may refer to later schemas in that same adapter.
They are not domain types, are not imported by Vernac namespace imports, and cannot
be parameters or results of a domain port. Different adapters may each declare
`Response`. Arbitrary Java classes and domain types are not schema field types.

```vernac
adapter CarrierClient implements Shipping {
    behavior {
        // Implement the port operations here.
    }
    schema Response naming snake_case (
        String trackingNumber,
        list<Line> lines,
        String? comment
    );
    schema Line(String productCode, int quantity);
}
```

For namespace `example.shipping`, Java output is a public final holder
`example.shipping.adapter.outbound.dto.CarrierClientDtos` with nested public records
`Response` and `Line`. Records expose their declared component accessors and Java's
record `equals`, `hashCode` and `toString`. They are `@NullMarked`.

Records have a public canonical constructor and a complete static `of(...)` factory.
Ordinary required components have their declared Java type. Ordinary optional
components are `Optional<T>`; their factory arguments are `@Nullable T`. Optional
primitives use boxed factory parameters, such as `@Nullable Integer`. Supplying
null to an `Optional` component's canonical constructor is rejected.

Names use Vernac's ordinary inferred/explicit field-name rules. Duplicate names,
Java-invalid identifiers and collisions with `of` or Java's record/Object API
are compile errors. No names are silently numbered or repaired. Technical helper
names beginning with `__` are reserved. Built-in scalar names are unavailable as
schema names. A schema also cannot have the same name as its generated holder.

## Supported field types

| Category | Types |
| --- | --- |
| Text | `String`, `char`, `Character` |
| Boolean | `boolean`, `Boolean` |
| Numbers | `byte`, `short`, `int`, `long`, `float`, `double`, their boxed forms, `BigInteger`, `BigDecimal` |
| Identifiers and codes | `UUID`, `Currency` |
| Dates and times | `Instant`, `LocalDate`, `LocalTime`, `LocalDateTime`, `OffsetDateTime`, `OffsetTime`, `ZonedDateTime` |
| Other temporal values | `Duration`, `Period`, `Year`, `YearMonth`, `MonthDay`, `Month`, `DayOfWeek`, `ZoneId`, `ZoneOffset` |
| Structures | Another schema in the adapter; `list<T>` with any supported element type |

`EventId` and other Vernac domain types are deliberately excluded. Technical JSON
arrays use `list<T>`, not automatically generated domain collections. Nested lists
are supported. Each list is defensively copied, including nested lists, and has
no mutable backing collection exposed to the caller. Null elements and optional
element types such as `list<String?>` are rejected. `list<String>?` is an optional
list and is permitted. Empty records are permitted as transport schemas.

Cyclic schema containment is currently rejected, including cycles through optional
fields and lists. Recursive external documents require an explicitly handwritten
integration until this restriction is revisited.

## JSON runtime

Applications using generated DTOs add `org.vernac:vernac-runtime-json` at the same
version as their compiler. It brings `vernac-runtime` and Jackson Databind; it does
not require JDBC. Domain-only applications do not need it.

Generated records select their own Jackson serializers/deserializers through
annotations. A standard `new ObjectMapper()` can read and write them without
registering a module. No application-wide naming or coercion setting is changed.
Applications intentionally overriding these codecs are responsible for preserving
the contract.

- Unknown fields are ignored recursively. Additive response changes are tolerated.
- Required missing or JSON-null fields fail, including primitive fields. There is
  no implicit zero or false.
- Optional ordinary fields treat missing and JSON null as absence.
- Present empty text and empty lists stay present.
- Scalar token kinds are checked: no strings-to-numbers, numbers-to-strings,
  strings-to-booleans or floating tokens-to-integers. Integral ranges are checked.
- Text-encoded types use their Java textual representations and parsers. Month
  and weekday use enum names. UUID text must have the full canonical shape;
  uppercase hexadecimal digits are accepted. A `char` is one UTF-16 code unit.
- Numeric JSON preserves BigDecimal precision and scale and floating signed zero.
  Java float/double precision still applies to arbitrary incoming numbers;
  non-finite values and overflow to infinity are rejected.
- Temporal values preserve their Java precision, including nanoseconds and zone
  information. No automatic unit, timezone or currency conversion occurs.
- Duplicate JSON keys are rejected rather than silently selecting one occurrence.

Record constructors enforce structural presence, PATCH permissions and immutable
list storage. They do not execute domain validations. Domain factories will be
used at the domain-mapping boundary. Unserializable numeric values fail when
encoded.

Structural violations produce `DtoContractException` (a `VernacTechnicalException`)
with a field path, such as `$.lines[2].quantity`. Jackson decoding retains it as
the cause of a `JsonMappingException`; root null can throw it directly. Generated
contract messages do not include field values. General Jackson parser exceptions
can contain input excerpts: applications should not log whole parser exceptions
or payloads indiscriminately. Remote/local responsibility is not inferred by the
DTO codec; the later adapter error layer owns that classification.

## External names and absence

By default, JSON names equal Java component names. `naming snake_case` opts in to
an explicit strategy; `named "wire-name"` overrides it for one field. Both reading
and writing use the resulting name. Nested schemas have their own strategy.

The snake_case algorithm processes Unicode code points without a dictionary or
locale-dependent lowercasing. It inserts `_` before an uppercase letter if the
preceding character is lowercase or a digit, or if an uppercase run is followed
by a lowercase letter at that position. It then applies `Character.toLowerCase`
to each code point. Existing underscores and digits are retained.

| Java name | External name |
| --- | --- |
| `trackingNumber` | `tracking_number` |
| `URLValue` | `url_value` |
| `HTML2XMLMapper` | `html2_xml_mapper` |
| `MaßeGröße` | `maße_größe` |

There is no guessed alias or reverse normalization. Explicit names use JSON string
escaping. Duplicate effective external names are compiler errors, including
collisions introduced by a strategy or explicit override.

Absent ordinary optional fields are omitted on output. `absent as null` explicitly
writes JSON null instead. It is invalid on required fields and PATCH fields.

```vernac
schema Request(String name, String? note, String? comment absent as null);
```

## PATCH fields

```vernac
schema CustomerPatch (
    patch String name,
    patch String? comment,
    patch list<String>? tags
);
```

Each component is `org.vernac.runtime.ports.PatchField<T>`, a non-null immutable
wrapper. The factory also takes the wrapper, never a nullable argument.

| Java state | Incoming JSON | Outgoing JSON |
| --- | --- | --- |
| `PatchField.unchanged()` | Field omitted | Field omitted |
| `PatchField.clear()` | Explicit null | Explicit null |
| `PatchField.set(value)` | Present value | Encoded value |

`?` permits clearing. `name` cannot be cleared; `comment` and `tags` can. Both
JSON decoding and direct Java construction enforce this rule. `set(null)` throws;
use `clear()` deliberately. `set("")` and `set(List.of())` are values, not absence.

`isUnchanged()`, `isClear()` and `isSet()` expose intent. `value()` returns an
`Optional<T>`; it alone does not distinguish unchanged from clear. `map(...)`
transforms only set values and preserves the other two states; a null mapping
result is rejected. Equality/hashCode include both intent and value.

This is whole-field replacement. There are no inferred recursive merges, list
edit operations or automatic aggregate mutations. The remote protocol must support
the declared semantics; using HTTP PATCH alone is insufficient. Applying an
update to a domain object remains an explicit application/domain operation.

## Tooling and follow-up

The LSP completes local schema types and scalar types in schema fields and navigates
to adapter-local declarations. Schemas do not leak into domain completion. Java
type and component navigation can resolve generated DTOs back to their schemas.

Typed declarative/Java mappings, nested/list conversions and state-preserving PATCH
conversion are now implemented; see the [mapping contract](adapter-mappings.md).
REST requests and error translation use these DTOs; see
[declarative REST](declarative-rest.md) and the [HTTP runtime](http-adapter-runtime.md).

## Page fields

`offset list<LocalDto>`, `counted offset list<LocalDto>` and `cursor list<LocalDto>`
are immutable transport envelopes; approved scalar entries are also allowed. The
[page contract](pagination.md#application-results-and-transport-pages) fixes metadata
names, validation, missing continuation/unaligned page-number representation and
variant-preserving mappings. This field shorthand is intended for Vernac's common
page protocol. Other vendors' pagination layouts remain ordinary explicitly modeled
schemas with Java conversions where necessary. Outer naming strategies do not rename
the fixed technical envelope metadata. No global Jackson module is changed.
