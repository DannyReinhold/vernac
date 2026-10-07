# Value Object Contract

Status: Accepted design contract; implementation conformance remains to be verified.
The runtime exception hierarchy and validation order below are part of this contract.

## Scope

This contract describes ordinary Vernac value objects and their generated Java API.
Enumeration values generate Java enums and have a separate contract. `id` definitions
also have a separate contract, although they share value semantics.

Related specifications:

- [Namespaces and imports](../specification/namespaces-and-imports.md)
- [Domain field types](../specification/domain-field-types.md)

The terms MUST and MUST NOT describe requirements for the compiler and generated code.
They do not imply that the current implementation already satisfies every requirement.

## Type identity and generated class

Each value object MUST generate one public final Java class in the Java domain package
derived from its Vernac namespace. The convention always appends `.domain`.
Per-definition Java package overrides are not supported.

Field types MUST be resolved as approved built-in types or valid Vernac types before
Java generation. Unknown names MUST produce Vernac diagnostics. A fully qualified
name MUST NOT serve as an escape hatch for arbitrary Java field types.

Names from the approved Vernac built-in type catalog are reserved for all
user-defined Vernac types, in every namespace. Matching is case-sensitive.
For example, `String`, `Integer`, `UUID`, `Currency`, and `LocalDate` cannot
be redefined. Field names such as `currency` remain permitted. This reservation
does not extend to every class in the JDK.

A violation MUST produce a diagnostic at the type declaration, for example:

```text
Type name 'Currency' is reserved for a built-in type.
Choose a domain-specific name, such as 'PaymentCurrency'.
```

The class MUST have a private constructor and MUST be annotated with `@NullMarked`.
The generated API MUST NOT depend on a generated `package-info.java` for nullness.

## Fields and immutability

Value object state is immutable:

- Fields MUST be private and final.
- `mut` fields MUST be rejected with a Vernac diagnostic.
- Setters MUST NOT be generated.
- Construction MUST NOT retain or expose a mutable representation that can change
  the value object's observable state or invalidate its equality and hash code.

Approved scalar types and immutable nested value objects satisfy the intended model.
Collection declarations, defensive copying, and collection access are governed by a
separate collection contract that remains to be specified. No arbitrary Java arrays
or collection field types are implicitly permitted by this contract.

## Construction and factories

The primary factory is named `of`. It accepts every declared field in declaration
order, using the resolved Java field types.

Required reference parameters MUST reject `null` at runtime. Optional reference
parameters MUST be annotated with `@Nullable` and accept `null` as absence.
Optional primitive declarations MUST be rejected; an appropriate optional wrapper
can be used instead. Factories MUST NOT take `Optional` as a substitute for optional
field parameters.

When a value object has both required and optional fields, an additional `of`
factory MUST accept only the required fields, preserving their declaration order.
It MUST delegate to the full factory, supplying `null` for optional fields.
Overloads for arbitrary subsets of optional fields MUST NOT be generated.

| Field structure              | Generated factories                                |
|------------------------------|----------------------------------------------------|
| All fields required          | Full `of(...)`                                     |
| Required and optional fields | Full `of(...)` and required-only `of(...)`         |
| All fields optional          | Full `of(...)`; no additional zero-argument `of()` |

A value object consisting entirely of optional fields is permitted. Whether all
fields may be absent is a domain decision expressed through its invariants.
The absence of a value object itself is modeled through an optional reference to
that value object. It is distinct from an existing value object whose fields are
all absent.

Fieldless value objects are forbidden. A declaration without fields MUST produce
a Vernac diagnostic at the declaration. This is a permanent language rule.

## Validation

Every construction path MUST enforce required-field checks and declared invariants
before returning an instance. A failed construction MUST NOT expose a partially
constructed or invalid instance.

The required-only factory MUST NOT bypass invariants. It may therefore reject a
particular invocation when the absent optional fields make an invariant fail.

Vernac MUST NOT silently normalize values to make validation pass. Any normalization
feature requires an explicit, separately documented contract.

Validation MUST proceed in this order:

1. Check required values in field declaration order.
2. Evaluate `require` conditions in declaration order.
3. Stop at the first failure and throw `DomainValidationException`.

Validation failures are not accumulated. A missing required value MUST be rejected
before any `require` condition is evaluated. Unexpected exceptions raised by a
validation expression are not automatically converted into validation failures.

## Accessors and nullness

Every field MUST have a public record-style getter: the field name with no `get`
prefix and no parameters.

| Field                                | Getter return type |
|--------------------------------------|--------------------|
| Required field of type `T`           | `T`                |
| Optional reference field of type `T` | `Optional<T>`      |

Required reference getters MUST return a non-null value. Optional getters MUST
return a non-null `Optional`, using `Optional.empty()` for absence.

Optional storage fields and constructor parameters MUST be annotated with
`@Nullable`. `equals` MUST accept an `@Nullable Object` argument.

Value objects MUST NOT receive automatically generated `asString()` or `asUuid()`
conversion methods. Named field getters are the standard access API. Authors may
explicitly define domain-specific conversion methods, including these names,
provided they do not collide with actual generated members.

This rule does not apply to `id` declarations: IDs provide `asString()` as a
convenient UUID string representation. `toString()` remains part of the VO contract
as a readable object representation, not a serialization format.

Generated convenience methods and user-facing code should use the public accessor
contract. Constructor, equality, and hash-code implementations may access private
storage directly.

## Equality and hashing

The class MUST override `equals` and `hashCode`.

Equality MUST depend on the value object's own type and all declared fields.
Two different value object types MUST NOT compare equal merely because they wrap
the same values. Two instances of the same type with equal corresponding fields
MUST compare equal. Optional absence MUST be handled consistently.

Hashing MUST include all declared fields and be consistent with equality.
The exact hash algorithm and numeric hash values are not a compatibility guarantee.

Built-in equality semantics MUST be preserved:

- `BigDecimal` equality remains scale-sensitive; values are not normalized.
- Floating-point equality follows the corresponding Java wrapper semantics:
  NaN values compare equal, while positive and negative zero remain distinct.
- Nested value objects use their own value equality.

## String representation

The class MUST override `toString` and provide a readable representation identifying
the value object type and its field values. Optional absence MUST be handled safely.

The exact punctuation and formatting are not a compatibility guarantee.
`toString` is not a serialization format or a persistence representation.

## Compiler, tooling, and verification

Invalid declarations MUST be rejected by Vernac with a source location and an
explanation of the violated rule, rather than being deferred to generated Java
compilation. This includes unsupported field types, unresolved Vernac references,
`mut` fields, optional primitives, and conflicting generated API names.

The language server MUST use the same semantic rules and resolved type identities
as the compiler. Navigation and completion MUST support local, same-namespace,
imported, and fully qualified Vernac value object references.

Contract verification MUST cover both generated source structure and observable
behavior of compiled generated classes. Essential cases include:

- Private construction, final class and fields, and absence of setters.
- Full and required-only factories, including delegation and all-optional declarations.
- Required-null rejection and invariant enforcement through every factory.
- Required checks before invariants, declaration order, and first-failure behavior.
- Rejection of fieldless declarations and reserved built-in type names.
- Optional getters, nullable annotations, and absence of automatic conversion helpers.
- Explicit author-defined conversion methods without false generated-name conflicts.
- Equality and hashing across all fields, absent fields, nested values, and distinct types.
- Scale-sensitive decimals and floating-point edge cases.
- A safe, readable `toString`.
- Type resolution across files and namespaces, including negative diagnostics.

The user documentation MUST explain factory overloads, optional getters, equality,
and validation. Tutorials should demonstrate these APIs through generated Java,
including a failed construction and a nested value object from another file.

## Runtime exception hierarchy

The runtime MUST provide the following minimal hierarchy in `vernac-runtime`, without JDBC dependencies:

| Exception                   | Parent                  | Purpose                                                               |
|-----------------------------|-------------------------|-----------------------------------------------------------------------|
| `VernacException`           | `RuntimeException`      | Common root for errors explicitly represented by the Vernac runtime   |
| `VernacDomainException`     | `VernacException`       | Domain-level rejection of an operation or value                       |
| `DomainValidationException` | `VernacDomainException` | Rejection of a value that violates its construction contract          |
| `VernacTechnicalException`  | `VernacException`       | Technical failure explicitly translated at an infrastructure boundary |

Preserve the existing name `DomainValidationException`; no separate exception class
is required for each generated value object.

For value object construction, both a missing required reference and a failed
`require` condition MUST throw `DomainValidationException`. Messages MUST
identify the value object and, when applicable, the field, while preserving the
model author's invariant message. Messages MUST NOT automatically include raw
input values.

Exceptions MUST support a message and an optional cause. If a known lower-level
failure is deliberately translated, its original cause must be preserved.

Do not catch arbitrary exceptions from validation expressions and relabel them as
domain errors. For example, an unexpected null dereference inside a custom expression
is a programming defect, not proof that the supplied value violated a declared rule.
Likewise, do not automatically wrap every Java or Spring exception in a technical
Vernac exception. Translation belongs at a boundary where it adds meaning.

Compiler diagnostics and compilation failures are separate from this application
runtime hierarchy. HTTP status codes, retries, logging, and recovery policies are
application decisions and must not be encoded in these base exception classes.

Applications with an existing business-exception superclass can translate Vernac
exceptions at their application boundary. Generated value objects do not need to
inherit from or depend on an application-specific exception hierarchy.

The treatment of existing runtime exceptions outside value object construction,
including identifier parsing and persistence failures, requires a separate review.
