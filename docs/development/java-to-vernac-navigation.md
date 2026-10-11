# Java-to-Vernac navigation

## User behavior

In an imported Maven project, **Go to Declaration** (Ctrl+B / Ctrl+click with the
usual IntelliJ keymap) on a generated model type reference opens its `.vernac`
definition. This includes ID types, ordinary and enum value objects, and explicit
list/set types on IDs and value objects.

For example, navigation on `ProductName` in this Java statement opens the
`value ProductName(...)` declaration:

```java
var product = ProductName.of("Notebook");
```

An explicitly named collection navigates to its name; an implicitly named
collection navigates to the corresponding `list` or `set` keyword.

Getter and public behavior references also navigate to Vernac:

| Java reference | Vernac destination |
| --- | --- |
| VO getter for an explicitly named field | The field name |
| VO getter for an implicitly named field | The field's type declaration |
| Optional VO getter | The same field declaration, despite returning `Optional` |
| ID `value()` | The ID declaration (its UUID field is implicit) |
| Public inline behavior method | Its method name in `behavior` |
| Public `implemented by` method | Its method name in the Vernac contract |
| Public enum/collection behavior method | Its method name in that type's `behavior` |

IntelliJ resolves the Java overload first. Vernac then matches the method name
and exact ordered parameter types using the compiler's type resolver. Primitive
and boxed types remain distinct. Nullable input annotations do not change the
Java parameter type. Overloaded methods therefore retain distinct destinations,
including method references such as `ProductName::displayName` when Java resolves
them unambiguously.

Constructors, factories such as `of`, conversions such as ID `asString`, generated
`equals`/`hashCode`/`toString`, standard enum methods and collection helpers keep
normal Java navigation. Private inline helpers retain the existing Java-injection
navigation within their behavior block. Calling a hand-written external static
implementation directly still navigates to that Java implementation.

The generated Java sources remain available in the project tree and through
Navigate to Class. The plugin does not change Java reference resolution, types,
compilation or generated source contents: it only supplies a declaration target
for this navigation action.

## Identity and provenance

The integration first lets IntelliJ resolve the Java reference. It redirects only
references resolving to a top-level Java class/enum whose physical `.java` file
is registered in the existing `.vernac-generated-sources` ownership manifest.
The manifest is parsed with the same validation as compiler generation/cleanup.
A familiar package name, filename or generated Javadoc is not sufficient evidence.

Within the referenced type's Maven module, the current Vernac project index is
consulted. The compiler's `JavaTypeNames` mapping supplies the exact Java identity;
there is no global search by simple class name or removal of a guessed `.domain`
suffix. Duplicate or absent declarations have no target. Body implementation
companions are not domain declarations and are not redirected.

The existing ownership inventory plus the live declaration index are sufficient
for type, getter and behavior navigation. No additional source map, absolute-path
annotation or comment parsing is introduced. The lookup cache is attached to the
resolved Java declaration, keeping different members and overloads separate.

## Live source ranges

The destination range is taken from the current parsed declaration, not a line
number saved during generation. Unsaved editor documents override disk contents.
Supplementary Unicode characters are converted from ANTLR code-point offsets to
IntelliJ UTF-16 offsets. Unrelated files with syntax errors do not prevent an
independently valid declaration from being found.

Lookups are cached per resolved Java type and invalidated by PSI/VFS changes.
During indexing, or when a model/manifest is unavailable, the handler leaves
normal Java navigation in place. The handler never launches Maven or modifies
source files.

## Initial scope and prerequisites

- The model module is imported as a Maven project and uses `src/main/vernac`.
- Java generation has run and its output is indexed by IntelliJ.
- The output still has its ownership manifest. Custom output directories work
  because the manifest is found relative to the resolved Java source file.
- Definitions use the reviewed namespace-based ID/value-object/enum/collection
  model. Other language features and custom Vernac input roots are follow-ups.
- Binary dependency JARs and attached source JARs are not redirected to similarly
  named local models. Cross-module navigation uses the resolved class's module,
  never the caller's module.

If a definition is renamed or removed without regeneration, the stale Java type
has no matching live definition and navigation falls back to Java. The same applies
to renamed getters and changed behavior parameter signatures. Duplicate member
signatures or a getter/behavior collision never select an arbitrary declaration.

## Verification

Automated tests cover exact qualified identities, namespace collisions, IDs,
values, enums, explicit/implicit collections, live Unicode offsets, unsaved
renames, duplicate/removed declarations, unrelated parse errors and ownership
manifest validation. Member tests additionally cover explicit/implicit and optional
getters, ID access, overloads, imported parameter types, enum/collection behavior,
external delegation, live signature changes and ambiguous members. Distribution
tests verify registration and packaged classes.

For the IDE integration check, navigate from a real Java reference to a generated
model type. Also check that a normal Java type still navigates normally and that
the generated `.java` file can still be opened directly from the project tree.

## Technical interfaces

Access interfaces now live in `<namespace>.domain.access`. Navigating to the type
`TourRead`, `TourWrite`, or `TourAccess` opens its generated Java declaration. These
technical types are not aliases for the domain declaration. Source-backed members can
still navigate to Vernac, including getters resolved through an injected `self` receiver.
