# Adapter mapping contract

Status: normative mapping contract, including the 0.3.0 inbound HTTP extension. This contract complements
[adapter DTOs](adapter-dtos.md) and [REST bindings](declarative-rest.md).

## Purpose and placement

Mappings translate between transport data and domain values. They do not load
aggregates, call repositories or orchestrate a usecase. Each mapping belongs to
one adapter. Declarations and references are independent of source order; a named
mapping may be declared later in that adapter.

The Java API is a public final holder
`<namespace>.adapter.outbound.mapping.<Adapter>Mappings`. Each mapping generates a
public static method with one required argument named `source` and one required
result. Mapping roots cannot be optional; the caller handles a missing whole
response. Field optionality is supported independently.

Applications using these generated mappings add `vernac-runtime-json` at the same
version as the compiler, also when a particular mapping has no DTO endpoint.
Manual port implementations without generated mappings do not require it.

HTTP inbound adapters use the same conversion rules and factory generation, with
DTO/mapping holders under `<namespace>.adapter.inbound.http`. They additionally
allow an injected usecase's explicitly declared `<Usecase>.Result` as a mapping
source; Result components retain their normal optionality. Result is not a mapping
target or a new domain type. See [the inbound contract](inbound-http.md).

```vernac
mapping toWeight:
    Weight <- WeightPayload {
    bigDecimal <- source.weightKg;
}
```

Every arrow reads target on the left, source on the right. Mappings are either
fully declarative, fully inline Java, or delegated to an external Java class.
There is no mixed Java/declarative field body.

## Types and visibility

Roots and fields use approved scalars, adapter-local schemas, Vernac IDs, value
objects, enum values, technical `list<T>` or declared collections of domain values.
Entities, aggregates, events and their collections are not mapping payloads.
Arbitrary Java classes cannot become mapping endpoint types.

Local DTOs use their schema names. Vernac domain references follow the file's
namespace/import rules and can be fully qualified. A simple name identifying both
a local DTO and a visible domain declaration is an error: rename the local schema
and/or qualify domain references. An import never silently replaces a domain type.

Mapping names must be distinct in the adapter. Overloading is not supported.
Names conflicting with generated/Object APIs or starting with `__` are reserved.
No custom Java packages are available.

## Declarative construction

Declarative targets are DTO records, IDs or non-enum value objects. Scalar, enum
and collection root results require a Java mapping. A source path starts with
`source`, optionally followed by declared fields, such as `source.address.city`.
The bare `source` refers to the complete input. Domain ID values use `source.value`.
Arbitrary getters, method calls, indexing and Java expressions are not paths.

Assignments name immediate target fields. For nested target construction, use a
named mapping; there is no implicit object creation for a dotted target path.

- Every required target field must be assigned exactly once.
- An unassigned ordinary optional field becomes absent.
- An unassigned PATCH field becomes unchanged.
- Unknown fields, duplicate assignments and incompatible types are compiler errors.
- Unused source fields are permitted.
- No automatic name-based field assignments occur.
- Domain objects are constructed through their normal validated `of(...)` factories.
  DTOs use their normal `of(...)` factories as well.

An optional source path cannot populate a required target. A path through an
optional parent remains optional even if its final member is required. Absence
propagates without a null dereference; an optional target factory receives null.
Providing defaults or rejecting absence requires explicit Java code.

## Exact automatic conversions

Identical types pass through. Primitive/boxed forms of the same scalar are
compatible; numeric widening or narrowing is never inferred.

A single-value domain object or ID may be wrapped/unwrapped through its one
required field when the leaf types match exactly. This may traverse nested
single-value wrappers. No automatic conversion passes through an optional inner
field. Multi-field objects require an explicit mapping.

There are no implicit unit conversions, normalization, timezone changes,
String/number conversions, enum-name conversions or guesses about external codes.
If two distinct single-value types wrap the same scalar, an explicit field
assignment can convert between them through that scalar and the target factory.
The compiler cannot establish that their business meanings agree; use the mapping
to document that deliberate boundary decision.

## Named nested and collection mappings

```vernac
mapping toShipment: Shipment <- ShipmentPayload {
    address <- source.destination using toAddress;
    items <- source.lines using toItem;
}
```

`using` identifies a mapping in the same adapter. Its exact input/output types
must match either the whole field pair or the collection element pair. Whole-field
matching takes precedence. There is no automatic selection among candidate mappings.

Collections convert element by element when their element conversion is exact or
explicitly named. Lists preserve order and repeated entries. Null elements are
never inserted. Set-to-list conversion uses that set's iteration order and promises
no stable ordering across executions. List-to-set conversion is not implicit,
because it can discard duplicates: use a Java mapping to make that decision.
Mapping to a domain set from another set uses normal target equality and may merge
elements that the chosen conversion makes equal. This is the declared set contract,
not a guarantee of cardinality preservation.

Nested collection conversions preserve the same rules recursively. Collection
traversal in a field path is not supported; use an element mapping instead.
Mapping invocation cycles are rejected. Java recursion and side effects remain the
responsibility of handwritten code.

## PATCH intent

PATCH-to-PATCH conversions preserve unchanged, clear and set. The contained value
is converted only for set, including named element mappings for a set collection
value. Unchanged and clear never execute the value converter.

A clearable source cannot populate a non-clearable PATCH target. Ordinary optional
fields cannot implicitly become PATCH fields or vice versa. A path cannot traverse
inside a PATCH wrapper or into a PATCH field behind an optional parent. These
cases require a Java mapping with an explicit decision about intent.

Mapping a PATCH DTO does not modify any domain object. A usecase/domain operation
must decide how to apply the resulting instruction.

## Inline Java and external implementation

```vernac
mapping normalizeTitle: Title <- String {
    java imports {
        java.util.Locale;
    }
    java {
        return Title.of(source.toUpperCase(Locale.ROOT));
    }
}
```

The body receives `source` with its resolved Java type. Visible domain types follow
the usual Java behavior imports. DTO names used inside a Java body can be imported
explicitly from the generated holder. Java imports are local to that mapping;
conflicting imports are rejected. Body semantics are subsequently checked by javac
and the IDE's Java analysis.

```vernac
mapping normalizeTitle: Title <- String
    implemented by example.integration.TitleConversions;
```

The external class provides `public static Title normalizeTitle(String source)`.
It is not a Spring bean. Both forms use a generated implementation companion and
the same checked public mapping method. The mapping has no receiver or injected
dependencies, so there is no `self`. It is a value conversion, distinct from the
port operation's receiver and invocation context.

Java is the escape hatch for explicit defaults, enum translation, normalization,
collection-root conversion and custom PATCH intent. No MapStruct integration is
required; a Java implementation may delegate to an explicitly configured mapper.

## Errors

`MappingContractException` extends `VernacTechnicalException` and exposes the
qualified mapping name and a target path such as `$.items[2].title`. Nested mapping
failures compose their target paths, and original causes remain available.

Generated boundaries translate domain-validation and DTO-contract failures and
reject null mapping results. A final whole-object invariant may only be attributable
to `$`. Other handwritten exceptions, including programming failures, propagate
unchanged; the generator does not classify every Java exception as bad remote data.
A null root argument is rejected before conversion.

Mapping diagnostics contain names and paths, not captured source object graphs.
Causes originate in user/domain code and may contain user-supplied messages. This
layer does not decide local versus remote responsibility, log requests or retry
operations. The adapter error boundary owns transport-specific classification.

## Tooling and verification

The compiler shares its resolved type/field model with mapping field completion.
The LSP supports endpoint types, target/source fields and local `using` names.
Inline Java bodies receive Java projection with a typed source parameter. Generated
mapping methods can navigate back to their declarations.

Tests compile and execute generated code: domain/DTO roundtrips, IDs, validation,
optional paths, lists and duplicates, PATCH states, Java imports, external delegates,
null results and preserved programming exceptions. Compiler tests cover malformed
assignments and incompatible types; editor tests cover local navigation/completion.

## Mapping pages without losing metadata

Pages are a separate conversion shape, not another sequence. A page field assignment
maps the entire page; `using <mapping>` selects its entry conversion. Automatic exact
VO/scalar conversion is also available for entries. Source and target variants must
match; counts/navigation/order/multiplicity are retained eagerly. Source paths cannot
traverse page internals, and no page is implicitly flattened into a list or rebuilt
as a domain result from a transport DTO. Explicit Java remains available for deliberate
alternative semantics. See [the pagination contract](pagination.md).
