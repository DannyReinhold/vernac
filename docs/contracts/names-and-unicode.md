# Names, Unicode and source encoding

Status: agreed language contract; implementation and regression tests supplied.
Full Maven/IntelliJ verification of the Unicode patch is still pending.
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

This contract does not redesign the general default-name convention. That remains
a separate review item. Every derived name must nevertheless be valid and checked
for collisions. Users can provide explicit names where the language supports them.

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

Until these checks pass, documentation must not claim complete Unicode support.
