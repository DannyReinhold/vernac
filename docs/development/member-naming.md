# Default member names and API collision checks

The authoritative rules are in the [names contract](../contracts/names-and-unicode.md).
This implementation covers ordinary VO fields/method declarations and fixed ID syntax.

## Implementation

- `VernacNames.defaultMemberName` is the shared code-point-aware derivation function.
  The AST builder applies it to unnamed ordinary fields/parameters, independent of count.
- `FieldNode.hasExplicitName` records provenance for diagnostics. Explicitly constructed
  AST nodes retain explicit-name semantics through the existing constructors.
- `MemberNames` shares duplicate-name explanations between single-file and project analysis.
- `JavaTypeNames` shares target package/identity mapping between generation and API checks.
- `ValueObjectApiValidator` checks getters, actual factories, validation helpers, custom
  signatures and inherited Object methods. Optional parameter annotations do not change
  signature identity. Generic parameter types remain rejected by the existing type resolver;
  erasure support must be extended together with generic type support, not guessed here.
- Custom method declarations named `value` or `id` are parsed so they can be checked against
  the API. `internal` methods emit package-private Java visibility rather than an invalid modifier.
- The LSP consumes project diagnostics; no independent editor naming policy is introduced.
  Full Java-body completion and analysis remain outside this change.

SQL naming, collections, enums and full entity/aggregate conformance remain separate reviews.
Existing enum validation is retained without claiming a complete enum API review.

## Verification status

Shared name derivation and the actual API validator were compiled and exercised using
JDK 17 (preview enabled for existing pattern switches) in the patch environment.
Checks passed for acronym/Unicode/locale behavior, generated/inherited conflicts, legal
overloads, visibility and equivalent resolved signatures.

New JUnit tests cover compiled generated Java APIs, one-to-many field evolution,
explicit names, duplicate defaults, fixed ID syntax, factory collisions, imported and
qualified signatures, valid overloads, and LSP diagnostics/correction. Existing tests
are retained; the former single-field fallback expectation is updated to `uuid`.

A complete Maven/JDK 25 run is still required. The preparation environment did not have
Maven or JDK 25. Run in the IntelliJ Maven command window:

```text
mvn -pl vernac-intellij,vernac-maven-plugin -am clean verify
```

After installing the rebuilt plugin, try the [naming tutorial](../tutorials/default-names.md).
The obsolete embedded-body `.value()` assumption in outbound mapping is a recorded
follow-up for the mapping review; it is not silently generalized in this change.

## Independent editor diagnostics

Editor analysis retains parseable files when another source has syntax, namespace,
or declaration errors. Import failures only block type lookups in the affected
file. Name and getter collisions do not require complete type resolution.
Factory and custom-method parameter signatures are checked only when their
parameter types have resolved; Object override return checks require a resolved
return type. No placeholder Java types are invented to continue validation.

The strict loader and compiler still reject erroneous projects before generation.
This is partial analysis, not parser recovery: a syntactically broken file is
excluded until it parses. References to declarations missing from that file may
therefore report unresolved types. Completion/navigation recovery is a separate
concern; this change concerns published diagnostics.

`VernacProjectDiagnosticsTest` covers errors in a second file, unsaved edits,
independent collisions, clearing corrected diagnostics, and strict loading.
