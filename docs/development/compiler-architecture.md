# Compiler architecture and shared semantics

This is implementation guidance, not an alternative language contract.

## Project analysis

The loader discovers .vernac files below a source root, parses namespace declarations
and creates the complete symbol index before resolving references. Imports remain
file-local. Resolution uses Vernac identities; generators consume resolved types
instead of guessing Java classes from strings. File order and import cycles do not
require recursive source inclusion.

VernacCompiler provides readProject, analyzeProject and compileProject entry points.
The compileSource and compile(Path) convenience APIs index one source and use the
same ProjectTypeResolver and generation pipeline. They do not discover sibling files;
use compileProject for applications. There is no separate single-file semantic analyzer
or generator fallback for unresolved types.
Strict compilation rejects invalid projects before writing output. LSP diagnostics
retain parseable files so an error in one source need not hide independent errors
elsewhere. Unresolved references are not replaced with invented Java types.

## Names and Unicode

The dependency-free shared/vernac-names sources define the compiler/plugin name
policy. Unicode ranges match the Java 21 target rather than changing with the host
JDK. Names are not normalized. Default derivation is code-point-aware, and explicit
name provenance is retained for diagnostics. Generated API checks compare resolved
Java signatures, not merely method spelling.

LSP positions are UTF-16; lexer offsets and code-point indexes must be translated
through the shared source-text utilities. Multiline semantic tokens are split into
single-line ranges. Namespace paths preserve exact spelling; output collisions on
the actual filesystem are diagnosed before replacement.

## Common generation paths

IDs, values, enums, entities, aggregates and explicit collections use the shared
project index. DomainCollectionGenerator owns the generated collection API;
DomainCollections provides shared algorithms. ValueObjectGenerator handles value
and enum representation. API and field checks prevent mutable entity state from
entering immutable values through collections.

Public model types live in domain; access interfaces live in domain.access.
Behavior and validation companions isolate implementation from private storage.
Java bodies still require javac and configured nullness analysis. Parser acceptance
alone is not evidence that embedded Java compiles. Obsolete package overrides, bare
entity/aggregate method blocks and custom-repository grammar productions are removed.
SchemaBuilder runs once per compilation and supplies the same storage plans to every
repository/event binding. Domain generators never emit SQL constants.

Persistence, queries and adapters consume resolved models and have separate capability
checks. Do not make domain legality depend on one transport's representation limits.
The source writer reconciles only owned output; see [output ownership](generated-output.md).

## Verification

Tests must cover parsing/resolution diagnostics, generated Java compilation, meaningful
runtime behavior and editor symbol updates. Documentation examples are tested in
DocumentationExamplesTest; transport/persistence behavior needs its dedicated tests.
Avoid claiming full IDE verification from a source-only check. The [feature checklist](feature-review-checklist.md)
and [release plan](release-0.2.0.md) define review and acceptance.
