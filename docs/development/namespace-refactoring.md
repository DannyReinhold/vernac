# Namespace refactoring: implementation progress

This document records the staged implementation of the namespace design. It is not
a claim that the complete namespace, import, or value object contract is implemented.

## Completed: parsing and project indexing

Every source now begins with an explicit declaration:

```vernac
namespace org.example.tasks;

id TaskId;
value Title(String value);
```

The old file-level `package` declaration and the implicit default namespace are
rejected. IDs and value objects now always generate into `<namespace>.domain`.
Their per-definition package overrides have been removed from grammar and AST.
Package handling for other declaration categories remains pending.

Qualified names accept Vernac keyword segments such as `custom` and `value`.
Namespaces must still be valid Java package names. Java contextual words that are
legal package segments, such as `record`, are not indiscriminately rejected.

The new project-reading entry point is:

```java
VernacProject project = new VernacCompiler()
        .readProject(Path.of("src/main/vernac"));
```

It discovers regular `.vernac` files recursively, in deterministic path order,
parses all of them, and builds a shared declaration index. It does not follow
symbolic links. Each namespace must match the source's directory relative to the
source root, exactly and case-sensitively:

```text
src/main/vernac/org/example/tasks/model.vernac
namespace org.example.tasks;
```

Multiple files and multiple declarations per file are supported. Filenames are
not part of type identities. The index registers top-level declarations and their
currently declared collection types. Duplicate identities are rejected with all
conflicting source locations; equal simple names in different namespaces remain
valid. Built-in catalog names are rejected as declaration names by the index.

`VernacSourceParser` parses named in-memory text without a filesystem requirement.
`SourceLocation` now carries the source name and one-based line and column numbers.
Syntax and semantic diagnostics include that origin. Project reading collects
errors across files before reporting failure; it does not generate Java output.

The LSP recognizes `namespace` for highlighting and completion and supplies the
current document URI to the AST builder.

## Completed: imports and value-object field type resolution

Imports are AST nodes carrying their own source locations. The next analysis
entry point builds on the project loader:

```java
ResolvedProject result = new VernacCompiler()
        .analyzeProject(Path.of("src/main/vernac"));
```

This validates imports in every file, including unused imports, and resolves
ordinary value-object field references. The rules are:

- Same-namespace declarations are visible across files without imports.
- Explicit imports and nonrecursive wildcard imports refer to Vernac identities.
- Own-namespace imports, unknown imports, and conflicting explicit imports fail
  at the import. A local/explicit name conflict also fails even when unused.
- Local declarations and explicit imports take precedence over wildcard candidates.
- Overlapping wildcard imports only cause an error when an ambiguous name is used.
- Repeated external imports produce warnings and do not introduce ambiguity.
- Imports remain file-local and are not re-exported.
- Fully qualified Vernac names resolve directly. Generated Java package names and
  arbitrary Java classes do not act as aliases or fallback targets.
- Import cycles terminate because resolution uses the completed symbol index and
  does not recursively follow imports or type definitions.

Every successfully resolved VO field type is available through
`result.typeOf(field.type())`. `ResolvedType.Builtin` contains the approved Java
scalar class; `ResolvedType.Declared` contains the exact Vernac symbol, including
its namespace, category, and declaration location. Optionality remains on the
immutable source `TypeNode`; no automatic boxing or nullness inference is performed.
The result also retains per-file scopes for subsequent tooling integration.

This stage rejects optional primitives with wrapper suggestions, fieldless VOs,
mutable VO fields, raw generic field types, and entity/aggregate/service/etc. types
used as VO fields. IDs and enums are valid VO field types. Collection field analysis
is explicitly reported as not implemented yet, pending the collection contract.

`result.diagnostics()` retains warnings from successful analysis. Errors are
reported through `SemanticValidationException`, with source-aware diagnostics.
`result.deferredTypes()` identifies indexed declaration categories not covered by
this VO analysis stage. An accepted project is not yet proof that every declaration,
method body, validation expression, or generated member satisfies its full contract.

## Completed: ID and value-object generation

The new generation entry point builds on project analysis:

```java
VernacProjectCompilationResult result = new VernacCompiler()
        .compileProject(Path.of("src/main/vernac"));
result.writeTo(Path.of("target/generated-sources/vernac"));
```

It generates IDs, ordinary value objects, and enums across files and namespaces.
It preserves analysis warnings. Projects containing declaration categories whose
project generation has not been migrated are rejected explicitly, rather than
silently producing partial output. Collections remain deferred.

`ResolvedJavaTypes` translates resolved symbols into JavaPoet types. The ID/VO
path does not guess classes from unrecognized strings. Custom VO and enum method
signatures also use the project scopes; `void` is allowed only as a return type.
Their embedded Java bodies are not fully analyzed by Vernac.

The generated VO contract now includes:

- Final classes, private constructors, final fields, record-style getters, and
  equality/hash codes over all fields.
- `@NullMarked` on each generated class/enum; type-use `@Nullable` on optional
  storage and input parameters and on `equals`' parameter.
- `Optional<T>` getters for optional fields. Redundant `asString`/`asUuid` helpers
  are not generated for VOs; IDs retain `asString`.
- A complete `of` factory and one required-only overload for mixed required and
  optional fields. All-optional VOs have no additional zero-argument factory.
- Required reference checks in declaration order before invariant checks.
  Invariants execute in declaration order and stop at the first failure.
- Source-aware rejection of invalid member names and collisions with generated
  API members.

The runtime exception hierarchy is `VernacException` with domain and technical
branches: `VernacDomainException` and `VernacTechnicalException`.
`DomainValidationException` extends the domain branch. Required-value and declared
invariant failures use that exception; unexpected exceptions from handwritten
validation code propagate unchanged.

IDs retain UUID factories and type-specific equality. Missing required UUIDs and
malformed UUID strings produce `DomainValidationException`; malformed strings
retain the parsing exception as their cause. Constructor validation runs outside
the UUID parsing catch block. Optional UUID VOs can accept a missing value, subject
to their own invariants. Overloaded String/UUID factories require an explicitly
typed null when a caller passes a null literal.

Enum generation now uses the same namespace and nullness conventions, but its
existing parsing behavior is not a newly reviewed enum contract.

## Completed: Maven integration; next: language-server integration

`compileSource` and `compile(Path)` now use resolved types for ID/VO generation,
but their source scope remains a single file. The Maven plugin now invokes `compileProject` once for the entire source root.
Semantic errors are Maven build failures with source-aware diagnostics; filesystem
errors are execution failures. Successful analysis warnings are logged. Java files
are written only after the whole project passes analysis and generation. The output
directory is then registered as a Maven compile source root.

The LSP has namespace syntax support but has not yet been connected to the shared
project scopes for workspace diagnostics and navigation. Other generators still
use their existing resolution paths and will be migrated deliberately. In
particular, mixed old/new generator package behavior must not be treated as a
compatibility guarantee.

## Migration work deliberately left pending

Existing example `.vernac` files, IntelliJ file templates, and the basic project
template still use the old file-level syntax and source layout. They must be migrated
together with the remaining generator integration. A whole-repository
build that compiles those old examples is therefore not the gate for this interim
step. Do not introduce compatibility fallbacks to accommodate them.

Compiler test cases are retained and their Vernac fixtures use `namespace`.
The former rejection test for a Vernac keyword in a qualified adapter package now
asserts acceptance, matching the contextual-keyword rule. Existing tests for Java
keyword rejection remain.

## Verification

Run from the repository root in the IntelliJ Maven command window:

```text
mvn -pl vernac-compiler,vernac-lsp -am test
```

The project tests cover multi-file discovery, namespace/directory mismatches,
duplicate declarations in and across files, reserved names, enum and collection
symbols, invalid namespaces, missing declarations, source-aware syntax errors,
empty source trees, and preservation of imports without recursive loading.

Additional resolution tests cover explicit/wildcard/qualified lookup, precedence,
unused invalid imports, file-local visibility, non-re-export, wildcard ambiguity,
repeated-import warnings, mutually referring namespaces, forbidden Java fallback,
VO field categories, optional primitive diagnostics, and explicit analysis deferrals.

Generation tests compile emitted Java and execute its factories, getters,
equality, hash codes, and validation. They also exercise multi-file references,
same-simple-name types from different namespaces, all approved scalar types,
handwritten Java callers, and a namespace ending in `.domain`. Nullable qualified
types are tested specifically to ensure valid Java type-use annotation placement.

Nullness annotations are checked through compilation and reflection, and runtime
null rejection is exercised directly. A static nullness checker such as NullAway
has not been integrated in this step.

## Maven end-to-end example

[The namespace/value-object example](../../examples/namespace-values/README.md)
uses the actual Maven lifecycle, three Vernac files in two namespaces, and a Java
JUnit test. It is a reactor module and needs no database.
Run from the repository root:

```text
mvn -pl examples/namespace-values -am clean verify
```

The reactor builds the local compiler and plugin before compiling the example.
This also runs the compiler and Maven-plugin unit tests. The existing broad
examples are not selected because their language features remain under review.

No automatic deletion of stale generated sources is introduced here. Use `clean`
after deleting or renaming definitions until the generated-file ownership and
cleanup contract is implemented. The no-partial-output guarantee concerns analysis
and generation errors, not atomic filesystem writes or cleanup of earlier output.

## IntelliJ follow-up: New Namespace

Add a **New Namespace** action analogous to Java's **New Package**, relative to
an identified Vernac source root. Validate namespace names with the shared compiler
rules, create the corresponding directory structure, and have subsequent new
Vernac files receive the matching `namespace` declaration automatically. This is
pending plugin work, alongside LSP workspace integration.
