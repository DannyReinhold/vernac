# Validation through read views

## Scope and syntax

Value objects, entities and aggregates evaluate their `validates` rules in a separate,
package-private generated class. Each rule uses an explicit `self` receiver:

```vernac
namespace example;

value Name(String, String? note) validates {
    require(!self.string().isBlank(), "Name must not be blank");
    require(self.note().isEmpty() || !self.note().orElseThrow().isBlank(),
            "An optional note must not be blank when present");
};
```

This is a breaking change: bare field references are not supported. Vernac diagnoses
references to known fields or behavior methods without `self`. Java compilation checks
method availability and expression types. There is no token replacement or implicit
rewriting of arbitrary Java expressions. Whitespace and comments in expressions survive
code generation.

Usecase and service validation are outside this change: their input-validation contract
has not been redesigned. Collection behavior is also unchanged.

## Generated structure

For the example, the compiler generates `Name` and `__VernacValidation_Name` in
`example.domain`, and the public `NameRead` interface in `example.domain.access`.
The validation companion contains
`static void validate(NameRead self)`. `Name` delegates from its private `validate()`
method with a new private inner `__ReadView` instance. The value object itself does not
implement `NameRead`. Unvalidated value objects do not need this additional interface.

`NameRead` exposes field getters and public value-object behavior signatures. Optional
getters return `Optional<T>`, exactly as on the owner. Required getters return `T`.
Entities and aggregates reuse their existing `Read` interface, which exposes getters
and `read` behavior only. Validators cannot call setters or `modify` methods through
this interface. Private behavior helpers are not exposed.

The validation class is a separate top-level class, so it has no privileged access to
private owner state. Generated read contracts and validation classes use `@NullMarked`.
Generated validation type names are reserved against user-defined type collisions.

The owner's `behavior { java imports { ... } }` imports are also available in its
validation expressions. These imports follow the existing collision and visibility
rules. A public read behavior can encapsulate a reusable predicate; private helpers
remain implementation details of the behavior companion.

## Timing and failures

Required-field checks happen before validation. Rules execute in declaration order and
stop at the first failure. A false condition throws `DomainValidationException`, with
the type name and the rule's message. Unexpected exceptions from expressions propagate;
they are not converted into successful validation or swallowed.

Value-object construction validates the completed field state. Entity and aggregate
creation and reconstitution also validate. For modifications, validation still occurs
on successful return from the outermost nested modify scope. Intermediate field writes
are not individually checked by these rules.

There is **no rollback**. Writes occur on the original objects. A failed rule or behavior
can leave them changed and invalid; callers should abort and discard the affected graph.
This patch changes access to validation state, not mutation isolation or persistence.

## Limits of read access

A read interface restricts the directly available API; it is not a purity checker or a
security boundary. User Java can have external side effects. A getter returning a child
entity still exposes that child's public API. Aggregate ownership and deep read-only
views remain separate design work. Calling a read behavior which recursively invokes
itself also remains an application error.

## Editor and verification

IntelliJ Java injection presents validation expressions with the same read-typed `self`
and imports as generated Java. Host ranges use UTF-16 offsets, including Unicode source.
Generated read types can navigate back to the corresponding Vernac definition.

Tests compile generated Java to check read-view isolation, Optional getters, public
behavior delegation, imports and invalid member access. Existing lifecycle tests cover
reconstitution, nested modifications, validation failures and retained mutations.

## Readability and editor context

The failure branch removes one clearly outermost `!`: `require(!self.string().isBlank(), ...)`
generates `if (self.string().isBlank())`. Other conditions retain their structure inside
`if (!(condition))`. There are no De Morgan rewrites or comparison inversions.

The injected editor fragment uses the original expression in `if (condition) {}`.
It deliberately does not inject the generated failure negation, so inspections do not
report a double negation which the user did not write.
