# Enum Value Object Contract

Status: Accepted design contract; implementation conformance remains to be verified.
MUST and MUST NOT express requirements, not claims about the current compiler.

## Purpose and scope

An enum value object represents a named, non-empty, closed set of domain values.
It is suitable for statuses, classifications, and kinds whose allowed values are
part of the domain model. User-maintained or dynamically extensible catalogs need
a different model.

The declaration defines possible values, not permitted state transitions. The
responsible domain object enforces transitions and context-dependent invariants.
An enum may offer domain-specific queries through author-defined methods.

Related contracts:

- [Ordinary value objects](value-objects.md)
- [Names and Unicode](names-and-unicode.md)
- [Namespaces and imports](../specification/namespaces-and-imports.md)
- [Domain field types](../specification/domain-field-types.md)

## Declaration and type identity

```vernac
namespace org.example.tasks;

value Status = PENDING | IN_PROGRESS | COMPLETED;
```

An enum MUST declare at least one constant. Declaration order MUST be preserved.
Duplicate constants MUST be rejected, using the shared identifier comparison rules.
No automatic renaming, numbering, case conversion, or implicit extra constants
(such as UNKNOWN) are permitted.

Each declaration MUST generate one public Java enum in `<namespace>.domain`.
The enum MUST be annotated with `@NullMarked`. It MUST NOT depend on a generated
`package-info.java` for nullness.

Enum type names follow the same namespace, uniqueness, Unicode, and reserved
built-in type-name rules as IDs and ordinary value objects. For example, an enum
type named String is forbidden because String is an approved built-in type.
Per-definition custom Java packages MUST NOT be supported. Legacy package-override
syntax and associated enum generation paths must be removed rather than retained
as compatibility behavior.

## Constant names and separate name categories

UPPER_SNAKE_CASE is the recommended constant naming convention, not a mandatory
language restriction. Valid Unicode member identifiers remain supported under
the shared naming contract. Java keywords and other invalid Java member names
MUST be diagnosed by Vernac. Vernac keywords MUST NOT introduce arbitrary extra
restrictions where the shared naming contract permits their use.

Constants are values of their declaring enum. They do not declare types and do
not refer to existing types merely because their names match.

The following declaration MUST be accepted:

```vernac
namespace example;

value MeinTyp(int);
id MeineId;
value MeinEnum = MeinTyp | MeineId | String;
```

`MeinEnum.MeinTyp`, `MeinEnum.MeineId`, and `MeinEnum.String` are three constants
of MeinEnum. Their names do not alter type resolution for MeinTyp, MeineId, or
String in Vernac field and method signatures. The reservation of built-in TYPE
names does not reserve those names for constants.

Different enums MAY use the same constant names. Constants MUST NOT be added as
top-level type symbols or implicitly imported into other definitions. Vernac type
imports remain type imports; they are not Java static imports.

Fields/constants and methods occupy different Java name categories. Consequently,
constants named `name`, `values`, or `toString` are not, by themselves, collisions
with `name()`, `values()`, or `toString()`. Checks MUST consider actual Java legality,
not a shared blacklist of all API words.

### Embedded Java name shadowing

A permitted constant can obscure a type name in expression context. Inside the
example enum, `String.valueOf(123)` can resolve String as the enum constant rather
than java.lang.String. `java.lang.String.valueOf(123)` disambiguates this example.
The same issue can affect static factory calls on domain types.

Documentation MUST distinguish legal declaration names from Java expression
shadowing. The generator MUST produce valid Java for permitted constant names,
including in generated expressions. Analysis of arbitrary embedded Java bodies
remains javac's responsibility; Vernac does not promise Java-body name resolution.

## Generated Java API and value semantics

Vernac MUST preserve the standard Java enum model:

- Constants are the only instances; no public constructor or instance factory.
- `==` and `equals` identify the same constant of the same enum type.
- Java's enum equality and hashing are inherited, not regenerated as VO field logic.
- `name()` returns the exact declared constant name.
- `values()` returns constants in declaration order using standard Java behavior.
- `valueOf(String)` retains standard Java behavior, including rejection of unknown
  names and null. It is not a domain input-validation or external-mapping API.
- Enums can be used in Java switch statements and expressions.
- `ordinal()` and natural ordering remain available as Java features. Their values
  depend on declaration order and MUST NOT define a Vernac persistence contract.

Vernac MUST NOT automatically generate `of`, `create`, `value`, `asString`, or
`dbValue` helpers for enums. A legal author-defined method may use such a name;
removing a generated helper does not globally reserve or ban its name.

Without an author-defined override, `toString()` follows Java enum behavior.
A legal author-defined `toString()` override is permitted. Neither `toString()`
nor `name()` is promised as a stable external code across domain refactorings.

## Author-defined behavior and API collisions

Authors MAY define methods using Vernac's method declarations and embedded Java
bodies. Method signatures follow the shared resolved-type and nullness rules;
no arbitrary Java types become available through this feature.

This contract does not introduce extra instance fields, constructors, constructor
arguments per constant, constant-specific class bodies, mutable state, or abstract
methods requiring implementations on individual constants. Enum construction
validation blocks are not part of this model: every declared constant is a valid
member of the set.

Vernac MUST diagnose duplicate or incompatible method declarations before Java
generation where their declarations provide enough information. Checks MUST cover:

- Duplicate author-defined Java signatures after Vernac type resolution.
- Implicit Java methods such as `values()` and `valueOf(java.lang.String)`.
- Final inherited methods from Enum and Object, including generic specialization
  and erasure where relevant (for example compareTo).
- Java-specific enum restrictions such as declaring a finalizer.
- Illegal return types, visibility reductions, or static/instance conflicts in
  overrides, to the extent expressible by Vernac's supported method syntax.

Methods MUST NOT be rejected solely because they share a name with an inherited
method: legal overloads and legal overrides remain permitted. `toString()` is an
example of an allowed override; `name()` is an example of a forbidden override.
A constant named `name` is still legal.

Diagnostics MUST identify the declaration, conflicting member/signature, and reason.
Suggested replacement names MUST NOT introduce known conflicts. Compiler and LSP
MUST share the implementation rather than maintaining separate naming rules.

## Use as a value object field

Enums MUST be usable as fields in ordinary single- and multi-field value objects.
Same-file, same-namespace multi-file, imported, and fully qualified references MUST
resolve through the shared Vernac symbol model.

```vernac
namespace org.example.tasks;

value Status = PENDING | IN_PROGRESS | COMPLETED;
value TaskState(Status);
value TaskView(String title, Status, Status? previousStatus);
```

The default field name for Status is `status`; there is no single-field `value`
exception. Explicit field names remain supported. Normal name-collision checks
apply when multiple fields derive the same name.

Required enum fields follow ordinary VO required-null rejection. Optional enum
fields accept a nullable input and expose `Optional<Status>` through the getter.
Enums participate in the containing VO's equality, hashing, and string rendering
under its normal field rules. There is no implicit String-to-enum conversion.

## External representations and persistence

The enum declaration MUST NOT contain database codes, transport codes, serialized
aliases, or a special dbValue property. Previous code-assignment syntax and generated
dbValue helpers must be removed during implementation of this contract.

An external representation belongs to the relevant persistence or adapter mapping.
Different external systems may assign different representations to the same enum.
The domain enum MUST NOT acquire one privileged external representation.

Stable codes, unknown external values, renaming, mapping completeness, and schema
migrations will be specified with persistence and ACL mapping. Removing dbValue
MUST NOT be compensated by silently substituting name(), toString(), or ordinal()
in existing mappings. Affected generators and examples must be identified; unresolved
mapping support must be explicitly diagnosed or tracked until that review.

`ordinal()` MUST NOT be used as the generated persistent representation. The presence
of ordinal() on the Java API does not imply a stable domain or storage identifier.

## Verification and documentation

Implementation work MUST include compiler, generated-Java, and LSP checks for:

- Non-empty declarations, declaration order, duplicate constants, and Unicode names.
- Required package placement, @NullMarked, and rejection of custom package syntax.
- Reserved enum type names versus legal constants named String or existing types.
- Repeated constant names in different enum types without symbol-table pollution.
- Constants named name/values/toString without false method-collision diagnostics.
- Real method conflicts, legal overloads, and a legal custom toString override.
- Compilable generated Java even with constants that obscure common type names.
- Required and optional enum fields in VOs, across files and namespace boundaries.
- Standard Java enum behavior and absence of automatically generated legacy helpers.
- Independent editor diagnostics remaining visible despite unrelated file errors.

Documentation and a tutorial MUST explain domain use, custom behavior, generated
Java usage, equality, optional fields, name shadowing, and the distinction between
constant names and external codes. Tooling review MUST cover highlighting, type
completion, navigation, and diagnostics. Enum-constant completion/navigation in
embedded Java requires explicit verification and must not be claimed solely because
type navigation works.

## Java reference

The Java name-category and enum behavior rules are grounded in the Java Language
Specification, especially sections 6.4, 6.5, and 8.9:

- https://docs.oracle.com/javase/specs/jls/se21/html/jls-6.html
- https://docs.oracle.com/javase/specs/jls/se21/html/jls-8.html#jls-8.9
