---
title: Applying the lessons to SQLJ
---
# A starting checklist for a SQLJ plugin

[Lessons learned](index.md) · [Documentation map](../../documentation-map.md)

This is a design checklist, **not an implemented SQLJ feature or a specification of
any customer's translator**. SQLJ combines Java with embedded SQL and translator
semantics. Before choosing IntelliJ extension points, collect representative files,
the actual SQLJ toolchain/dialect, generated Java and debug information from the
intended environment. Do not assume every SQLJ implementation emits the same code.

## Choose the leading language deliberately

Vernac owns the outer file and injects Java into reviewed bodies. SQLJ may instead
be mostly Java with SQL islands and extra declarations. Compare these alternatives
on real examples before adopting Vernac's exact PSI/injection structure:

| Approach | Investigate |
| --- | --- |
| Custom SQLJ host with Java projection | Preserve Java class/member identity, imports and scopes across SQL fragments; measure projection cost |
| Java-oriented PSI with SQLJ extensions/projection | Determine which Java parsing assumptions reject SQLJ syntax and which supported extension points can bridge it |
| Generated Java as the only IDE model | Useful baseline, but check source navigation, edit latency and whether users must leave their SQLJ source |

Language injection supplies a fragment context; it does not teach the Java parser
an arbitrary grammar extension. Coloring SQL or replacing an embedded statement
with a dummy expression is not evidence that the surrounding Java type model is
correct. Prototype the hardest small file, not just the shortest valid example.

## Define identities and mappings before completion

Track the original Java declarations, SQLJ-specific declarations, bind variables,
SQL ranges and generated implementation separately. Establish which declarations
are user-owned, generated or dependency-provided. Imported/generated types must not
be offered as writable rename targets merely because navigation can find them.

Resolve Java bind expressions in their real lexical scope. SQL columns and tables
need an independently selected dialect/schema context. A field with the same name
as a Java local is not the same symbol. Consider read/output bind direction,
nullability, iterators/cursors and connection contexts as explicit test dimensions
where the chosen translator supports them.

Database metadata access is optional infrastructure: keep it off editor callbacks
that must remain responsive, cache it with explicit invalidation, and define useful
behavior offline. Never require production credentials to parse a file. Persisted
metadata and schema assumptions need provenance, not guessed global resolution.

## Keep translation and debugging separate from editor recovery

First validate ordinary Java assistance around an incomplete SQL statement. Then
validate SQL assistance without claiming the entire Java/SQLJ file compiles.
An isolated SQL syntax error should not unnecessarily erase unrelated class APIs.
Build errors must still fail the real translator/build.

Inspect the translator's line tables and any supplied source maps. If it already
emits a reliable standard mapping, evaluate reuse before adding a sidecar format.
If it does not, determine which user statements can be mapped unambiguously and
how to detect stale output. Preserve actual JDBC calls, exception paths and worker
threads; avoid hiding an expensive or failing query as an invisible technical step.

## Build a small vertical acceptance example

Start with one Java class containing a parameter, a local, one embedded query and
an observable result. Add incomplete SQL, an unresolved bind name, Unicode/CRLF,
a handwritten helper and a generated iterator type if applicable. Check Java
completion before/after the SQL fragment, SQL name completion, both navigation
directions and breakpoint/evaluation behavior in the original file.

Only then expand to multiple files, dependencies, project import, translator output
cleanup and packaged-plugin testing. Use the
[verification ladder](debugging-and-verification.md#use-a-verification-ladder) so a
successful projection test is not mistaken for full IDE integration. Cross-language
refactoring, SQL schema refactoring and Hot Swap remain separate designs.
