# Declaration recovery for editor snapshots

Status: implemented in 0.5.0 development. This is editor infrastructure, not a
relaxation of Vernac syntax or a replacement for Java compilation.

## Purpose

Keep complete, independently recognizable declarations available when the user
temporarily breaks another declaration in the same file. This is a prerequisite
for live Java API completion before generation. It does not implement that API
projection itself, automatic generation, compilation or Hot Swap.

## Shared editor pipeline

`EditorSourceParser` uses the existing ANTLR grammar and AST builder. It collects
syntax errors and records the enclosing top-level declaration. It excludes any
declaration that owns a syntax error, contains a recovered error node or rule
exception, or fails the existing name validation. It builds the file model from
accepted declarations only. It also retains accepted parse contexts for tooling.

`VernacProjectLoader.loadForAnalysis` indexes those partial file models, including
unsaved snapshots. Java projection and native type/navigation tooling use the same
accepted contexts. Local declarations are matched by source line/column rather
than by their position in the original declaration list. External module views
retain their existing contract-order mapping because their rendered source
positions can differ from producer metadata.

Source offsets remain based on the actual document, including Unicode and UTF-16
conversion at the editor boundary. No text is deleted or padded for recovery.
Terms survive independently when their declarations are complete.

## Guarantees and limits

- Syntax diagnostics remain visible. A retained declaration is not a statement
  that the whole file or its embedded Java compiles.
- Missing parameter names/types and other inserted tokens are never published
  as real declarations. Semantic type checking remains a separate stage.
- Namespace and import syntax must be reliable; their recovery errors cause the
  file context to be withheld rather than guessed.
- There is no last-good cache. Renaming or deleting a declaration changes the
  current snapshot immediately, even when a later declaration is damaged.
- Recovery does not guess boundaries inside an unclosed Java/behavior block.
  Complete preceding declarations survive; ambiguous content can remain withheld.
  Do not infer a new top-level declaration merely from a keyword inside Java.
- An incomplete owner can be withheld as a whole. Recovering individual API
  members of that owner is a possible later refinement, not guaranteed here.

## Strict builds

`VernacSourceParser.parse`, `VernacProjectLoader.load`, and compiler/build
entrypoints retain the strict parser. They do not compile the editor's recovered
subset. A syntax error prevents successful generation exactly as before; this
change neither writes generated output nor changes schema/migration history.

## Regression coverage

Tests cover unclosed behavior blocks, malformed parameters, forbidden names,
incomplete Java expressions in balanced blocks, semantic errors, sibling-file
errors, unsaved renames, invalid headers, Unicode source ranges, retained Java
injection/navigation, external module views and strict build rejection. A
deterministic set of small deletions around declarations and bodies also checks
that ordinary typing states do not crash recovery.
