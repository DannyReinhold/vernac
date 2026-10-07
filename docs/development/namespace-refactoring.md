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
rejected. Java output packages and the remaining per-definition package overrides
have not been redesigned in this step.

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

## Next: reference resolution and integration

Project reading deliberately does not validate imports or resolve field references.
The presence of a parsed project does not mean that its imports, reference cycles,
or domain field types are semantically valid.

The next step will resolve same-namespace, imported, and fully qualified Vernac
references against the shared index and supply resolved symbols to generators.
The Maven plugin still invokes the existing per-file generation path; project-wide
generation and the LSP workspace index must be connected after reference resolution.
The existing string-based generator type resolver has not yet been replaced.

## Migration work deliberately left pending

Existing example `.vernac` files, IntelliJ file templates, and the basic project
template still use the old file-level syntax and source layout. They must be migrated
together with the remaining generator and Maven integration. A whole-repository
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
