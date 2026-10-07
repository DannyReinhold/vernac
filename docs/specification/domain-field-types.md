# Domain Field Types and Nullness

Status: Accepted design; implementation pending.

Related specification: [Namespaces and Imports](namespaces-and-imports.md).

This document specifies intended language behavior. It does not claim that the
current compiler, generators, JDBC support or IDE implement every requirement.

## 1. Purpose and scope

Vernac models domain state with explicit, meaningful types and removes the
boilerplate required to implement them in Java. A controlled set of built-in
Java value types provides the foundation for value objects.

These are deliberate Vernac modeling constraints, not a claim that DDD itself
prescribes a Java type whitelist or forbids primitive fields in every entity.

The rules below apply to user-declared state fields. They do not automatically
apply to method parameters, return types, adapter schemas, event payloads or
embedded Java implementation code. Those contexts require separate decisions.
Generated technical fields, such as version numbers and timestamps, may use
appropriate Java types directly.

MUST and MUST NOT indicate requirements; SHOULD indicates a recommendation.

## 2. Allowed domain field categories

| Containing definition | Allowed field categories                                                                                 |
|-----------------------|----------------------------------------------------------------------------------------------------------|
| Value object          | Built-in scalar/value types, Vernac IDs, Vernac enums, other value objects, and value-object collections |
| Entity                | Vernac IDs, Vernac enums, value objects, contained entities, and corresponding Vernac collections        |
| Aggregate             | Vernac IDs, Vernac enums, value objects, contained entities, and corresponding Vernac collections        |

Entities and aggregates MUST NOT directly declare primitive or built-in Java
value fields as user-defined state. Introduce a meaningful value object instead.

```vernac
namespace org.example.tasks;

id TaskId;
id CustomerId;
value Title(String);
value DueDate(LocalDate);
value Status = PENDING | COMPLETED;

aggregate Task[TaskId](
    Title title,
    DueDate? dueDate,
    CustomerId owner,
    mut Status status
);
```

IDs and enums do not require an additional value-object wrapper.

A value object MUST NOT contain an entity or aggregate as a field. Its contained
values and any collection of value objects must preserve its immutability.
A final reference alone is not a sufficient immutability guarantee.

Contained entities belong to their aggregate's consistency boundary. References
to other aggregate roots use IDs rather than direct aggregate objects.
A collection of contained entities is consequently different from a collection
of IDs referring to external aggregates. General collection declaration and
ownership rules are specified separately; this document adds no new collection
syntax.

## 3. Built-in value types

The following types are available without user imports. Each built-in has a
known Java mapping; the generator supplies imports where necessary.

| Category                    | Vernac names                                                                  | Java mapping                         |
|-----------------------------|-------------------------------------------------------------------------------|--------------------------------------|
| Boolean                     | `boolean`                                                                     | Java primitive                       |
| Integers                    | `byte`, `short`, `int`, `long`                                                | Java primitives                      |
| Floating point              | `float`, `double`                                                             | Java primitives                      |
| Character                   | `char`                                                                        | Java primitive; one UTF-16 code unit |
| Boxed primitives            | `Boolean`, `Byte`, `Short`, `Integer`, `Long`, `Float`, `Double`, `Character` | Corresponding `java.lang` classes    |
| Text                        | `String`                                                                      | `java.lang.String`                   |
| Arbitrary-precision integer | `BigInteger`                                                                  | `java.math.BigInteger`               |
| Decimal                     | `BigDecimal`                                                                  | `java.math.BigDecimal`               |
| UUID                        | `UUID`                                                                        | `java.util.UUID`                     |
| Instant                     | `Instant`                                                                     | `java.time.Instant`                  |
| Local date/time             | `LocalDate`, `LocalTime`, `LocalDateTime`                                     | Corresponding `java.time` classes    |
| Offset date/time            | `OffsetDateTime`, `OffsetTime`                                                | Corresponding `java.time` classes    |
| Zoned date/time             | `ZonedDateTime`                                                               | `java.time.ZonedDateTime`            |
| Time spans                  | `Duration`, `Period`                                                          | Corresponding `java.time` classes    |
| Calendar components         | `Year`, `YearMonth`, `MonthDay`, `Month`, `DayOfWeek`                         | Corresponding `java.time` types      |
| Time zones                  | `ZoneId`, `ZoneOffset`                                                        | Corresponding `java.time` classes    |
| Currency                    | `Currency`                                                                    | `java.util.Currency`                 |

Examples:

```vernac
value Title(String);
value Quantity(int);
value Temperature(double);
value Money(BigDecimal amount, Currency currency);
value DeliveryDate(LocalDate);
```

This is an explicit catalog, not permission to use all classes from these Java
packages. Being immutable does not automatically make an arbitrary Java class
an accepted built-in.

The catalog provides technical value semantics. Domain-specific restrictions,
such as nonblank text or nonnegative quantities, remain the responsibility of
the modeled value object's validation rules.

## 4. Type resolution and rejection

The compiler MUST resolve field types to known identities and categories before
generation. A simple name, imported name or fully qualified name is subject to
the same field-category restrictions.

A fully qualified name MUST NOT bypass the catalog:

```vernac
value Wrapped(org.example.MyVernacValueObjectType);
```

This is allowed if the referenced type is a known Vernac value object.

```vernac
value Wrapped(org.example.MeineJavaKlasse);
```

This is rejected if the name does not resolve to a supported type. General Java
classpath imports are not introduced for domain field declarations.

Distinguish diagnostics:

- Unknown type: the name cannot be resolved to a supported type.
- Disallowed field category: the type is known, but cannot be used in this
  containing definition, such as an entity field inside a value object.
- Direct technical state: an entity or aggregate declares a built-in scalar
  field and should use a domain value object instead.

Without Java classpath analysis, do not claim that an unknown name necessarily
identifies a Java class. Explain the supported categories instead.

These failures MUST be reported by Vernac at the field/type location, rather
than relying on javac to report missing imports or invalid generated code.

## 5. Optionality and nullness

Reference-type uses are non-null by default. `?` explicitly permits null:

```vernac
value Measurement(Integer? value);
```

`Integer` without `?` remains non-null. Primitive types cannot be nullable:

```vernac
value Measurement(int? value);
```

This MUST fail with a Vernac diagnostic suggesting `Integer?`. Apply the
corresponding rule to all eight primitive/wrapper pairs.

Do not silently translate `int?` to `Integer?`.

The generator MUST annotate every generated top-level class, interface, enum
and any other top-level type declaration with JSpecify `@NullMarked`.
Package-level `package-info.java` generation is not required for this policy.
This avoids shared-file ownership conflicts across generation units and
hand-written code.

Type uses that permit null MUST receive `@Nullable`, consistently across fields,
factory/constructor parameters, accessors and other affected signatures.
This includes Java API contracts such as:

```java
@Override
public boolean equals(@Nullable Object other) {
    // Generated equality implementation.
}
```

JSpecify annotations describe nullness; they do not enforce runtime checks.
Generated construction, reconstitution and mutation paths MUST enforce required
non-null state at runtime as appropriate. Exact validation timing and exception
contracts are specified with the corresponding feature.

No Java-annotation syntax is introduced into Vernac source by this policy.
Nullness annotations are generated from language semantics.

## 6. Equality and validation

Ordinary value objects use equality and hashing over all their state fields.
The built-in fields retain their Java value semantics; the generator MUST NOT
silently normalize values to change equality.

### Decimal values

`BigDecimal.equals` includes scale. Consequently, values representing `1.0`
and `1.00` are unequal under the default policy, even though `compareTo` regards
them as numerically equal.

Any domain-specific normalization must be explicit. A normalization facility is
not specified here. Do not generate compareTo-based equality while retaining
an incompatible hash implementation.

### Floating-point values

Generated equality for primitive `float` and `double` MUST follow the
corresponding wrapper equality semantics: NaN values compare equal for value
object equality, and positive and negative zero are distinct. Hashing MUST be
consistent with that equality.

NaN and infinity are not universally forbidden. An individual domain type's
validation decides whether they are meaningful.

## 7. Generated representation

An ordinary value-object definition generates a Java class, not a record, with:

- A private constructor and an `of` factory.
- Value-based `equals` and `hashCode` over all fields.
- A generated `toString`.

The enum form generates a Java enum:

```vernac
value Status = PENDING | COMPLETED;
```

It does not use the ordinary value-object class/factory representation.

Entity and aggregate definitions generate identity-bearing Java classes.
Their identity, factories, mutation behavior and relationship handling are
specified separately; this document does not redefine those contracts.

## 8. Initially excluded field types

The following are not accepted as direct value-object field types:

- Arbitrary Java classes, including fully qualified references to them.
- General abstractions such as `Object`, `Number` and `CharSequence`.
- Mutable types such as `Date`, `Calendar` and `StringBuilder`.
- Arrays and direct Java collections such as `List`, `Set` and `Map`.
- `Optional`: express optional state using `?` instead.
- `void` and `Void`: neither is a state-field type.

`void` as a method return type is a separate context. Restrictions here do not
imply that Java collections or library helpers are forbidden inside embedded
Java implementation code.

## 9. Persistence is a separate capability

An allowed domain type does not automatically imply a supported JDBC mapping.
When repository generation encounters an unsupported mapping, Vernac MUST emit
a diagnostic identifying the affected field path and type. It MUST NOT silently
fall back to an arbitrary SQL type or defer the problem to JDBC at runtime.

Persistence rules, SQL type mappings, serialization, enum persistence values
and migrations are defined during the persistence review.

## 10. IDE, documentation and acceptance checks

Compiler and plugin must share the same type catalog and category rules.
Completion SHOULD prioritize types valid for the current field context.
Diagnostics MUST agree with command-line compilation and point to the source
field/type, including when the type is imported or fully qualified.

Before completion, provide:

- A reference table of built-ins, Java mappings and allowed field contexts.
- A tutorial introducing domain wrappers and using them in aggregates.
- FAQ entries for optional primitives, BigDecimal scale, floating-point equality,
  rejected Java types and the distinction between modeling and persistence.

Tests must cover:

- Every built-in's resolved Java type and compilable generated output.
- All primitive optionality failures and nullable wrapper alternatives.
- NullMarked on generated top-level types and Nullable on affected API uses.
- Runtime rejection of null in required construction and mutation paths.
- Cross-file/imported/qualified Vernac field references and category checks.
- Rejection of arbitrary Java classes and excluded direct field types.
- Rejection of direct technical state fields in entities and aggregates.
- Value equality/hash consistency, including decimal scale, NaN and signed zero.
- Enum generation and calls from hand-written Java code.
- Targeted failure of unsupported JDBC mappings where repositories are generated.

Existing tests remain unless their expectations are explicitly superseded by
these accepted language changes. Parser-only success is not sufficient;
generated Java and relevant runtime behavior must be tested.

## 11. Open decisions and migration

The following are intentionally unresolved:

- Name resolution when a Vernac type has the same name as a built-in, such as
  `Currency` or `String`; no precedence or reservation rule is established here.
- Whether exact fully qualified Java spellings of built-ins, such as
  `java.math.BigDecimal`, are accepted aliases. Arbitrary Java names remain
  forbidden either way.
- Rules for operation signatures, event payloads and adapter schemas.
- Additional Java libraries and imports inside embedded Java code.
- Future support for user-defined Java value types.
- Complete collection semantics and declaration forms.

Migration requires auditing existing direct primitive/Java fields in entities
and aggregates, current imports, examples, templates, tests, compiler diagnostics
and plugin behavior. Replacing fields with value objects changes the generated
Java API and may affect persistence; document such changes explicitly.

## References

- Eric Evans, DDD Reference: https://www.domainlanguage.com/wp-content/uploads/2016/05/DDD_Reference_2015-03.pdf
- Java 21 java.time: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/package-summary.html
- Java 21 BigDecimal: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/math/BigDecimal.html
- Java 21 Double: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Double.html
- JSpecify user guide: https://jspecify.dev/docs/user-guide/
