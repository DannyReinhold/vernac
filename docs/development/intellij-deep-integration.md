# Deep IntelliJ integration

Status: **roadmap with an experimental first debugger slice in 0.5.0**.
See the [prototype record](vernac-debugger-prototype.md) and
[manual acceptance example](../../examples/debugger/README.md). Refactoring and
the broader debugger experience below remain planned.
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

1. Establish a small multi-file Vernac/Java application and build-owned source mappings.
   The initial domain-behavior prototype and real JVM test now provide that foundation.
2. The initial installed-IDE walkthrough has confirmed breakpoints, stepping,
   variables, expression evaluation and conditional breakpoints. Keep separate
   acceptance for contributor mode, logpoints, Watches and each newly mapped construct;
   do not infer UI correctness from SDK compilation.
3. Core mappings now include domain behavior, usecases, services, require rules and
   listener bodies, adapters, mappings, inbound argument conversions and event validations.
   Core expression resolution/completion is confirmed in the installed IDE. Complete
   adapter/mapping UI acceptance, then inspect technical stepping stops and improve
   Read/Write/Access presentation where useful. Verify ordinary local-value editing
   and audit the tutorial.
   Preserve real deferred execution boundaries. Advanced renderers, dependency-source
   attachment and remote/hot-swap support remain later work, separate from this closure.
4. Build semantic declaration/reference identities for one complete cross-language rename
   flow with conflict preview and undo; extend other refactorings incrementally.
5. Add true editor/debugger integration tests and measure responsiveness on larger projects.

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


## Diagram explorer follow-up

The bounded namespace export/action is implemented: shared resolved graph, Mermaid/PlantUML
sources, JSON and a Markdown catalog with vocabulary. A later model explorer can build on
that graph for live refresh, filtering, custom zoom/layout, clickable source navigation and
module/application-level views. These interactions are not promised by the initial export.


### Observed debugger editor gap

Evaluate Expression can successfully evaluate a mapped frame's local while showing
its name as unresolved and offering no Ctrl+Space completion. Treat Java expression
presentation/context selection as a separate acceptance target from runtime
execution. Cover locals, arguments, self, imports, conditions and log-expression
editors at a suspended frame, not only before launching a session. Preserve native
Vernac navigation and avoid routing breakpoint source positions back into themselves.

## Debugger value editing and completion audit

The installed-IDE validation walkthrough is confirmed: require breakpoints, Read/input
self inspection and runtime expression evaluation work. The user also requested
ad-hoc value editing to exercise a failure without changing source.

Inspection of the generated Units class and IntelliJ SDK 262.10968.63 shows that
intValue is a private final field and FieldDescriptorImpl marks final fields as
non-lvalues. Standard JavaValue only supplies a modifier for writable descriptors.
This is distinct from a getter result, which is not an assignable storage location.
Do not add domain setters or weaken immutable VO/Read contracts to implement a
normal debugger menu action.

Before choosing an additional value-editing feature:

- Verify ordinary local-variable editing and writable non-final Java fields in an
  installed session, separately from getter results and final domain fields.
- Prefer changing a primitive input before the validated factory/construction call
  for failure testing. The existing validation probe also supplies invalid inputs.
- Evaluate an explicit advanced/contributor-only raw-state editing mode only as a
  separate proposal. It must explain bypassed validation and immutable-state
  assumptions; ordinary display/rendering must not imply such writes are supported.

At debugger completion, perform a final tutorial audit against the installed-IDE
acceptance results: supported declarations, runtime evaluation versus red highlighting/
missing completion, writable versus read-only values, stepping limits, asynchronous
listener boundaries, contributor mode and every runnable example/command. Do not
claim untested behavior as established support.

## First task after the debugger workstream

Next, perform the [German/EU AI content disclosure review](ai-content-disclosure-review.md)
for vernac.org and its published texts, images and code. This is explicitly queued
for after debugger completion; no legal investigation is part of the current work.

The expression-editor increment uses the requested source line and matching factories
for Java and Vernac file-type requests. Host PSI keeps line offsets while preserving
the single injection host. SDK compilation and regression tests cover routing and
lexer offsets; visible resolution/completion still need installed-IDE acceptance.

### Adapter/mapping debugger increment

Source maps now include outbound adapter Java methods/helpers, Java and explicit
declarative mappings in both directions, inbound execute argument conversions and
event requires. Nine real-JVM tests check locations and locals. The core expression
editor fix is confirmed by the user; the new frames still need installed-IDE acceptance.
Next, evaluate technical intermediate stops and Read/Write/Access views. Do not
misrepresent read-only views or final VO fields as writable domain operations.

### Facade presentation increment

Adapter/mapping UI acceptance is now confirmed. The next plugin increment preserves
real Read/Write/Access types while projecting only recognized stored readable values;
it never evaluates computed getters to draw the tree or adds writable field nodes.
Its narrow extra stepping filter skips verified pure facade reads only. Constructor,
mutation-boundary, validation and callback-bearing runtime stepping remain normal.
Contributor mode restores raw views and disables the filter. Validate native tree,
Watch expression composition and stepping separately from the JDI inspector test.
After this acceptance, the queued AI-content disclosure review is next. Wider
renderers/filters and dependency-source work do not silently extend this workstream.

### Native type-reference / Ctrl-hover increment

The user has accepted the debugger workstream, including facade rendering and the
narrow stepping filter, and pushed it to feature/0.5.0. This increment adds native
PSI references to the existing lightweight injection host; its line lexer, TextMate/
LSP coloring and Java injection stay unchanged. VernacTypeReferences in the compiler
extracts exact type-name anchors from grammar contexts and resolves them through
FileTypeScope/ProjectSymbolIndex. VernacTypeNavigation shares declaration targeting
with Java navigation, now excluding term declarations when aligning model definitions.

The plugin caches per-host references against PSI and VFS modification trackers,
uses unsaved editor snapshots, build-written module metadata and existing module
source/contract views. References are soft navigation references, not new error
reporters. Ambiguous/unknown/non-exported types do not get guessed targets. The
provider does not reinterpret inline Java, enum constants or strings; adapter-local
DTO/result/path navigation stays with the LSP. Native rename/rebinding is rejected
rather than offering an incomplete cross-language refactoring.

Acceptance: resolved local/imported/qualified/module types show a link over the type
name when Ctrl is held, Ctrl+click and Ctrl+B agree, comments/unknown/ambiguous names
have no false target, and Java injection/documentation hover remain intact. Unit
ranges/target tests and SDK compilation do not establish the installed mouse-hover UI.

Local validation of this increment: 427 compiler tests, ten focused plugin tests and
ten release-tool tests passed. The changed plugin classes compile against IntelliJ
262.10968.63 APIs without deprecation warnings; 569 local documentation links passed.
No full Maven reactor or installed-IDE modifier-hover test was run here. Module
contract fallback PSI is read-only and stable per reference; stale materialized
views are not paired with ranges from a different contract snapshot.

### Term/reference regression follow-up

Installed-IDE feedback confirmed native type links, but a file containing a term
lost its embedded Java context. BehaviorJavaProjection compared all grammar
top-level declarations with the AST's executable definitions; the AST keeps terms
in a separate list. Filtering terms before both the size check and positional
alignment restores behavior and validation injections without changing Java
resolution or the plugin host. Regression coverage includes terms before and
between definitions, local Java helper declarations/calls, Java library calls and
an external implementation signature.

Native term-name links use the decoded AST term name and an exact same-namespace
symbol identity, including quoted identifiers and named collections. Spaces,
dotted vocabulary labels and unmatched concepts never become guessed imports.

### Java targets in DSL syntax

Behavior signatures are DSL syntax, not copied Java fragments. Native references
now supplement declared Vernac references with approved non-primitive built-ins
and explicit `implemented by` class identities. The compiler helper extracts only
grammar type positions and implementation labels (behavior/service/execute/mapping/
inbound error policy/REST error handler); it never scans raw Java text for names.
The plugin delegates class lookup to JavaPsiFacade in the host's resolve scope.
Reference extraction is cached with the existing PSI/VFS dependencies; class lookup
runs against the current index and returns no target in dumb mode. Unknown types,
primitive names and enum constants do not become fabricated Java classes. Existing
Java-body injection and cross-language rename limitations remain unchanged.

Validation: compiler extraction tests cover the three String occurrences around an
inline signature, the external signature/class reference, UTF-16 positions, optional
BigDecimal, primitives, unknown names and raw-Java exclusion. Plugin tests cover
native Java-reference ranges, canonical names and refusal of incomplete rename.
The contributor compiles against IntelliJ 262.10968.63; actual mouse-hover behavior
still requires installation and manual acceptance.

### Runnable project starter (0.5.0)

Native type/term/Java-signature navigation has been accepted in the installed IDE.
The next increment adds an optional Spring Boot Web Application project kind,
selected by default in the wizard. Minimal model projects and type libraries keep
their separate templates. The subsequent inbound MVC increment replaces the original
handwritten controller with `adapter TaskWeb inbound mvc`; generated controllers
and scoped error advice use adapter.inbound.http. Thymeleaf templates and security
configuration remain application-owned.

Template preparation computes the initial schema and history fingerprints for the
chosen namespace before writing any files. All destination collisions are checked
first. Creation never starts a database. Running the generated application with
no active profile selects `local` and starts Compose. An explicit deployment profile
replaces that default and requires external database configuration; the Dockerfile
selects `production`. Package import after
creation now derives the project root from src/main/vernac rather than fixed depth.

Regression coverage: custom/Unicode namespace generation, matching schema history,
no immediate migration diff, existing-file protection, configuration separation,
and real generated Java/domain/Thymeleaf/security execution. Plugin ZIP verification
checks web-template resources; release gates require both new test classes. The
web slice uses a test repository; real PostgreSQL execution is a separate opt-in
TaskPersistenceIT. Desktop/mobile rendering was inspected with fixture data;
installed-wizard and local Compose acceptance remain separate checks.

Local verification of this increment: eleven project-template tests passed,
including the existing minimal/module cases. The repository execution test compiled
the generated model and handwritten application, then ran all four domain/web
fixture tests without skips. The generated and handwritten main sources also
passed Error Prone 2.50.0 / NullAway 0.14.2 with the template's JSpecify flags on
JDK 25, targeting Java 21. Ten release-tool tests and 582 documentation links passed.
The preparation helpers compile locally; the full IntelliJ SDK/reactor build and
real Compose/PostgreSQL startup were not run in this environment.

### Declarative MVC boundary (0.5.0)

The shared inbound model now supports logical views, URL-encoded scalar forms,
local redirects, safe input redisplay and scoped error policies. The compiler
rejects unsafe view/redirect targets, missing model contracts and unsatisfied
redisplay-loader inputs. Mapping source maps target the actual generated helper
methods; form paths and redisplay targets participate in LSP completion/navigation.

Follow-up scope: multipart/repeated form bindings, field-level validation messages,
HTML fragments and dynamic redirects require separate contracts. Authentication
and authorization remain application concerns; this starter demonstrates CSRF,
not a complete user-management system.
