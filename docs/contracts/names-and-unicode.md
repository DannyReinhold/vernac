# Names, Unicode and source encoding

Status: agreed language contract. The Unicode patch passed the project build and
manual IntelliJ checks for accented namespaces, files and value-object types.
Default-name and ordinary VO API-collision implementation and regression tests
have been supplied; the full Maven test run for this change remains pending.
Fixed ID declaration syntax is retained and covered by negative tests.
See [implementation notes](../development/unicode-support.md).

This contract applies to namespace segments, declared type names, field names,
parameter names, method names and enum constants. Existing type-specific naming
restrictions still apply, including reserved built-in type names.

## Encoding

Vernac source files and generated Java source files use UTF-8. Encoding must not
depend on the operating system's default charset. Non-ASCII text is supported in
comments, string literals and embedded Java as well as in identifiers.

Identifier restrictions below do not prohibit these characters inside comments or
string literals. Embedded Java remains subject to Java's own syntax and semantics.

## Identifier alphabet

Names follow Java 21 identifier rules:

- The first Unicode code point must be a valid Java identifier start.
- Subsequent code points must be valid Java identifier parts.
- Java keywords and literals cannot be used as identifiers where Java forbids them.
- Restricted Java identifiers must be rejected in the contexts where generated Java
  would be invalid. For example, `record` is not a valid declared type name.
- Vernac additionally rejects identifier-ignorable characters, control characters
  and Unicode format characters inside identifiers. This includes invisible
  directional controls and zero-width format characters.

The Unicode alphabet must be consistent across supported compiler and IDE JVMs.
Running the tooling on a newer JDK must not silently accept names that the Java 21
compilation target rejects.

There is no ASCII-only restriction. Names may use accented letters, non-Latin
scripts, combining marks and valid supplementary Unicode characters. Underscores
and dollar signs follow the same Java-compatible identifier rules; a single `_`
is not a valid identifier.

These are validity rules, not a recommendation to use every permitted spelling.

## Namespaces and imports

A namespace is a dot-separated sequence of valid namespace segments. Each segment
maps to one directory below the Vernac source root. The declared spelling must
match the source layout.

Vernac keywords are permitted as namespace segments when Java permits that spelling
as a package component. For example, `org.example.custom` is valid. Java keywords
such as `class` are not valid package components.

Imports and fully qualified Vernac type references use the same namespace and name
rules. Their meaning does not depend on the source filename or generated Java
package. Existing namespace visibility and import-conflict rules remain unchanged.

## Name identity and preservation

Names are case-sensitive. The compiler preserves their spelling and never
transliterates them: `Größe` does not become `Groesse`.

There is no implicit Unicode normalization, case folding or replacement of
confusable characters. Name identity uses the exact sequence of Unicode code points.

For example, a name containing precomposed `ö` (U+00F6) differs from one containing
`o` followed by COMBINING DIAERESIS (U+006F U+0308), even if an editor renders them
identically. A reference must use the same sequence as its declaration.

Both spellings are allowed if they meet the identifier rules. Vernac must not
silently merge them or rewrite one to the other. A future optional warning for
confusable or non-normalized names may help users; such a warning is not part of
this implementation's required behavior.

Filesystem limitations do not change language identity. If two generated output
paths cannot be represented distinctly on the current filesystem, generation must
report the conflict rather than overwrite one declaration's output with another.

## Generated Java and derived names

Generated Java preserves explicit names and uses UTF-8. Existing architectural
package conventions still apply; a namespace is not itself a generated Java package.

Automatic name derivation must process Unicode code points, not individual UTF-16
code units, and must be independent of the default locale. It must never split a
supplementary character into two surrogate halves.

### Explicit and default field names

For ordinary fields and parameters, an explicit name takes precedence. If omitted,
Vernac derives a name from the resolved type's simple declaration name. Namespace
qualification must not become part of the field name. Imports and equivalent
qualified references must not change the result.

There is no special fallback to `value` for single-field value objects. The number
of fields must not affect the name of an existing field. To expose `value()`, name
the field `value` explicitly. Do not generate an alias returning the first field.

```vernac
value Title(String);             // getter: string()
value Label(String value);       // getter: value()
value Quantity(int);             // getter: intValue()
value Reference(TaskId);         // getter: taskId()
value Price(BigDecimal amount);  // getter: amount()
```

For approved primitive types, append `Value`: `int` becomes `intValue`, `boolean`
becomes `booleanValue`, and so on. Reference types use the capitalization rule
below: `String` becomes `string`, `BigDecimal` becomes `bigDecimal`. Optionality
must not rename a field. Explicit names must satisfy the same validity and
collision checks as defaults.

### Initial capitalization and acronyms

Process the initial consecutive uppercase code points of the simple type name:

- If there is no initial uppercase code point, preserve the name.
- Lowercase a single initial uppercase code point.
- For a longer initial uppercase run immediately followed by a lowercase code
  point, preserve the last uppercase code point and lowercase the preceding run.
- Otherwise lowercase the entire initial uppercase run.
- Preserve the remainder. A digit ends the uppercase run.

Use locale-independent Unicode code-point case conversion; do not transliterate,
normalize, split surrogate pairs or infer acronym boundaries from a dictionary.
The derived result must still pass identifier validity and collision checks.

| Type name | Default name |
| --- | --- |
| `StatusReason` | `statusReason` |
| `TaskId` | `taskId` |
| `UrlValue` | `urlValue` |
| `URLValue` | `urlValue` |
| `URL` | `url` |
| `HTMLXMLMapper` | `htmlxmlMapper` |
| `HTML2XMLMapper` | `html2XMLMapper` |
| `CustomerID` | `customerID` |
| `Größe` | `größe` |
| `𐐀name` | `𐐨name` |

Spellings such as `UrlValue` and `CustomerId` are recommended, not required.
Vernac does not invent alternatives, append numbers or choose another name to
avoid a conflict. The author supplies an explicit, meaningful name instead.

### IDs and entity identity are distinct contexts

`id TaskId;` is a complete ID declaration. An ID has exactly one required UUID
value with the fixed getter `value()`. Its payload cannot be renamed, made
optional or changed to another type. ID declarations do not accept extra fields,
custom validation or custom methods. Their factories, equality and string API
follow the common ID contract; this naming decision does not redesign them.
The existing ID convenience method `asString()` remains supported.

When an ID type is an ordinary VO field, normal naming applies: `TaskId` gives
`taskId`. For an entity or aggregate identity declared with `[TaskId]`, the
identity is always called `id` and exposed through `id()`. It is not an ordinary
field and does not participate in default-name derivation. Full entity/aggregate
conformance will be checked during their feature reviews.

### Duplicate fields and Java API collisions

Check all effective field names together, including explicit and derived names.
Different types may produce the same name:

```vernac
value IntValue(int value);
value InvalidPair(int, IntValue); // both fields would be named intValue
value NamedPair(int count, IntValue limit);
```

Repeated types require distinct effective names too. Source order, filename,
namespace qualification or imports must not silently disambiguate them.

Compare the emitted methods with generated and inherited Java methods. Check
resolved Java parameter types, including erasure when generic types are supported.
Return type, parameter names, nullability annotations and `static` do not make
otherwise identical signatures valid overloads. Check inherited override legality
as well: final methods cannot be overridden, and illegal static/instance or
visibility combinations must not escape detection where Vernac owns the signature.

| Field name | Getter | Result for an ordinary VO |
| --- | --- | --- |
| `toString` | `toString()` | Conflicts with the generated method |
| `hashCode` | `hashCode()` | Conflicts with the generated method |
| `getClass` | `getClass()` | Conflicts with the inherited final method |
| `wait` | `wait()` | Conflicts with the inherited final method |
| `notify` | `notify()` | Conflicts with the inherited final method |
| `notifyAll` | `notifyAll()` | Conflicts with the inherited final method |
| `equals` | `equals()` | Does not collide with `equals(Object)` |
| `value` | `value()` | Allowed if otherwise conflict-free |

This is not a global name blacklist. Apply the API of the generated type being
checked. For example, an ordinary VO can have a field named `id`; an entity's
identity already occupies that name. Legitimate overloads remain allowed.

Author-declared methods must not replace generated getters, factories, `toString`,
`equals` or `hashCode` with the same signature. A conflict must not cause the
compiler to omit a promised generated method. Two author-declared methods must
also have compatible, distinct Java signatures. Compare against the factories
actually generated, including the required-only `of` overload where applicable.

Vernac already parses method declarations. These checks use their AST signatures
and resolved types; they do not require analyzing embedded Java method bodies.
Body type-checking and local declarations remain javac's responsibility for now.

### API stability example

```vernac
value Price(BigDecimal);           // bigDecimal()
value Price(BigDecimal, Currency); // bigDecimal(), currency()
```

These are alternative versions of the same definition, not simultaneous
declarations. Adding a field does not rename `bigDecimal()`. Likewise, explicitly
naming it `amount` preserves `amount()` when another field is added. Factory
signatures may naturally change when fields change; only field-name stability is
promised here.

### Actionable collision diagnostics

A diagnostic must identify the conflicting effective name or Java signature and
point to the relevant declaration. For two source declarations, include the other
location where supported; for a generated method, explain what generates it.

```text
Field 'toString' would generate the getter 'toString()', which conflicts
with the generated method. Specify a different field name.
```

For an omitted name, also explain the type-to-name derivation. Do not suggest an
unchecked replacement such as `text`: it may already exist or cause another API
conflict. Generic guidance is the baseline. A future concrete suggestion must be
validated in the complete declaration context and must not silently rename code.

For method conflicts, distinguish renaming a method from renaming a field.
Do not recommend return-type changes as a way to disambiguate signatures.

## Diagnostics and editor behavior

The compiler checks invalid names directly and reports a Vernac diagnostic at the
relevant source location. A name violation must not first surface as a javac error.
Where an invisible character is involved, the diagnostic should identify its code
point, for example `U+200B`, rather than relying on displaying that character.

The same acceptance rules apply in:

- parsing and semantic name validation;
- namespace and type resolution;
- Java source generation;
- LSP completion, diagnostics and navigation;
- IntelliJ New Namespace and namespace-aware file creation.

Editor positions must follow the negotiated LSP position encoding. With the current
UTF-16 default, supplementary characters occupy two code units. Parser code-point
positions must be converted accordingly. Highlight ranges must not split surrogate
pairs or become displaced by supplementary characters earlier on a line.

## Example

Source: `src/main/vernac/de/beispiel/aufträge/titel.vernac`

```vernac
namespace de.beispiel.aufträge;

id AuftragId;

value Bezeichnung(String text);
value Auftragstitel(Bezeichnung bezeichnung);
```

`Auftragstitel` can also refer to `Bezeichnung` from another file in the same
namespace without an import. References from another namespace follow the normal
explicit-import, wildcard-import or fully-qualified-name rules.

## Acceptance checks

The implementation must demonstrate:

1. Accented and non-Latin names parse, resolve across files and generate compilable
   Java, including ID and VO declarations.
2. At least one valid supplementary identifier character survives parsing,
   generation, completion and navigation without corruption.
3. Unicode namespaces work in directory validation and New Namespace.
4. Invalid identifier starts, Java-reserved names and invisible control/format
   characters produce targeted diagnostics.
5. Precomposed and decomposed spellings retain their distinct identities.
6. Derived names remain valid and stable under a non-English default locale.
7. Semantic highlighting, diagnostic ranges and navigation targets remain correct
   when supplementary characters precede or occur inside a name.
8. Existing ASCII models and contextual Vernac keywords in namespaces still work.
9. Generated output path collisions are reported without overwriting another type.

### Additional naming and API acceptance checks

These checks are required by the accepted design, not a statement that they
already exist or pass:

1. Single- and multi-field VOs use the same derivation; adding or removing a field
   does not rename surviving fields. Explicit `value` produces exactly that getter.
2. Primitive and reference defaults, explicit overrides, optional fields, acronym
   runs, digits, non-Latin names and supplementary code points follow this contract.
3. Repeated types and different types such as `int` / `IntValue` report collisions;
   explicit distinct names resolve them without generated numbering.
4. Qualified and imported references to the same type yield the same default name
   and the same Java method signature.
5. Getter/generated/inherited method conflicts and custom factory conflicts fail;
   legitimate overloads such as `equals()` versus `equals(Object)` are accepted.
6. Duplicate custom signatures fail even with different parameter names or return
   types. Generic erasure collisions are tested when those signatures are supported.
7. Diagnostics remain useful when `text` and other plausible replacement names are
   already taken; no unchecked suggestions are emitted.
8. IDs keep their fixed UUID payload and API and reject customization. Ordinary ID
   fields and entity/aggregate identities are covered as distinct contexts.
9. Generated positive examples compile as Java. Negative examples fail in Vernac
   before generation, at useful source locations.
10. LSP diagnostics expose the compiler result consistently, including ranges and
    related locations. Completion/navigation tests cover names where supported.

## Related documents

- [Value object contract](value-objects.md)
- [Feature review checklist](../development/feature-review-checklist.md)

The compiler and tools should share name derivation and resolved signature data.
The implementation architecture belongs in development notes; this contract
specifies observable behavior.
