# Deep IntelliJ integration

Status: **planned; not implemented by the compiler-consolidation patch**.
Target: a first-class, coherent developer experience across Vernac and Java.
This is a tooling roadmap, not a change to language contracts.

## Cross-language refactoring

A model declaration is the source of truth for its generated Java API. Operations
must follow the relationship between source declarations, generated types/members,
inline Java and handwritten Java implementations rather than matching text.

- Rename types, fields, behavior methods, parameters, enum constants and namespaces.
  Find and update resolved references in both Vernac and Java, including imports,
  method references, external delegates and unsaved editor buffers.
- Moving declarations/files must preserve namespace/path rules and update imports.
  A Java-initiated refactoring of a generated symbol should target its Vernac origin
  or explain why it cannot be performed safely; editing generated output alone is
  not a valid implementation.
- Include safe delete, find usages and change signature. Preview affected files,
  generated API changes and naming collisions before applying edits. Support undo.
- Renaming handwritten Java classes/methods must update `implemented by`, Java
  imports/dependencies and embedded Java references where they resolve to that symbol.
- When an operation cannot be modeled reliably, report the limitation and affected
  references. Do not silently claim success or guess ambiguous targets.

Domain/API refactoring can also affect SQL names, schema history, event payloads,
listener identities and external contracts. Surface these consequences as diagnostics
or review tasks. Never rewrite released migration history or infer production data
conversions as a side effect of an IDE rename.

## Editor experience

Review completion, navigation in both directions, usage search, hover documentation,
Ctrl+hover link feedback, inspections and quick fixes as one integrated workflow.
Keep generated access interfaces navigable as Java while domain symbols can lead
to Vernac declarations. Diagnostics should point to the actual source fragment;
synthetic projection wrappers must not leak misleading Java inspections into Vernac.
Account for Unicode/UTF-16 offsets, multiple modules/source roots, dependency-provided
models when that feature exists, incomplete code and incremental editor changes.
Use shared compiler resolution rather than a competing IDE interpretation.

## Debugger investigation

First record actual behavior in a runnable example before choosing a mechanism:

- Breakpoints inside inline behavior, validation, usecases, services and listeners.
- Source locations, breakpoint binding and stepping through generated delegates,
  Read/Write/Access views and handwritten `implemented by` code.
- Display of `self`, domain values, optionals and collections; stack traces and
  exceptions with useful source locations.
- Deferred listeners and transaction boundaries: distinguish scheduling from the
  later execution context without suggesting that they share a Java call stack.

Evaluate source mapping/debug metadata, step filters and renderers only after this
baseline. Generated Java must remain available for detailed inspection. Define which
improvements need IntelliJ-specific support and which benefit any Java debugger.
Do not promise hot swap of structural model changes without evidence.

## Delivery and acceptance

1. Audit current behavior using a small multi-file Vernac/Java application.
2. Define source-to-generated-symbol identity and mapping lifetime, including stale
   generated output and unsaved changes; reuse existing navigation metadata where sound.
3. Deliver one end-to-end rename flow with conflict preview, diagnostics and undo.
4. Extend to other refactorings and complete the debugger prototype.
5. Add automated plugin tests plus a documented installed-IDE acceptance walkthrough
   against supported IntelliJ versions; measure responsiveness on larger projects.

Acceptance requires both successful refactoring and useful failure behavior. A green
compiler/LSP build alone does not establish IDE refactoring or debugger correctness.

See [the feature checklist](feature-review-checklist.md),
[embedded Java](intellij-java-behavior.md) and [Java navigation](java-to-vernac-navigation.md).


## Later: model-assisted editing

Plan a separate, optional in-plugin LLM experiment for fill-in-the-middle completion,
quick fixes and documentation suggestions. This is not part of the ubiquitous-language
increment. Danny's candidate is a small Qwen code model (approximately 1.5B parameters),
to be evaluated experimentally rather than treated as a selected or proven integration.

Fine-tuning concerns Vernac syntax and recurring language patterns, not application
vocabulary. Supply current project terms through the shared glossary/model context,
including imported type documentation, without retraining for every domain edit.
Validate code suggestions through the existing compiler and resolve diagnostics before
presenting fixes as correct. Documentation suggestions require human domain review.
Measure completion quality, latency and review effort before committing to model,
provider, packaging or automatic application of changes.
