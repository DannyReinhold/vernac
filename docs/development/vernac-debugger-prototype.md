# Vernac debugger: source mapping and acceptance

Status: 0.5.0 development tooling. The initial installed-IDE walkthrough has confirmed
breakpoints, stepping, visible variables, Evaluate Expression with resolution/completion
and conditional breakpoints after the source-position and editor-context fixes. This is manual acceptance for
the exercised path, not exhaustive automated IDE coverage. This implements
a bounded part of the [IDE roadmap](intellij-deep-integration.md), not a new DSL rule.

## Mapping contract

The compiler records domain behavior and inline usecase correspondences after final Java emission,
including vocabulary decoration. It parses the emitted companion with the JDK Java
parser and identifies original bodies through the Vernac grammar. Correspondence
requires matching ordered nonblank body lines; mismatches produce no guessed mapping.
Mappings include Java class/method/line, project-relative source/line, source and
Java hashes, and a runtime fingerprint. Coordinates are one-based; body offsets are
converted from ANTLR code points to Java UTF-16 before deriving source lines.

The private companion fingerprint covers the original source and emitted companion
before the fingerprint field is inserted. It detects stale loaded classes and changed
generator output without changing public domain contracts. CRLF/LF normalization
matches IntelliJ document semantics. A missing fingerprint cannot authorize a mapping.

The sidecar has formatVersion 1 and is written with generated sources under the same
output lock. Empty generation replaces old mappings. It is disposable build metadata,
not migration history or a promise of rollback on I/O failure. No `.class`/`.jar` cleanup
or bytecode postprocessing is introduced. The compiler host requires a JDK for parsing.

## IntelliJ integration

A Java-backed Vernac line-breakpoint type and handler bind original source positions
to mapped companion classes. A PositionManager handles forward/reverse locations and
class preparation. It retains ordinary JVM variables and uses normal Java debugging
for unmapped/handwritten code. Source/generated-file content and runtime fingerprints
must match. Multiple ambiguous reverse candidates fall back to Java rather than
selecting an arbitrary source. Dependency module source attachment is outside this slice.

The project-scoped map cache invalidates on file timestamp/size changes. Gutter lookup
loads only the owning Maven module's map. Source checks include unsaved documents;
map discovery currently assumes default Maven source/output paths. Generated Java
and bytecode remain available. A project-local contributor switch prefers Java source
presentation and does not alter runtime behavior.

## Acceptance and next work

Automated checks cover Unicode/CRLF, overloaded/private/collection methods, omitted
external delegates, sidecar round trips and stale-map cleanup. A real JDI test launches
the example and inspects self, arguments and locals at the mapped bytecode line.
Plugin checks reject absent/mismatched runtime fingerprints. SDK compilation alone
does not establish a functioning installed IntelliJ session.

Follow the [example walkthrough](../../examples/debugger/README.md). Record gutter
binding, source selection, private-helper and Java transitions, entity access views,
stale-source behavior and contributor-mode results. After that acceptance, improve
expression-editor resolution/completion and source presentation. Core mappings now
include domain behaviors, usecases, services, require rules and listener bodies. Evaluate Expression, watches and breakpoint expression editors now receive
mapped Java method scope. Custom renderers, remote debugging, hot swap and
cross-language refactoring remain separate work.

## Java expression context

`VernacEvaluationPosition` delegates file, line, offset and navigation to the Vernac
position, but returns the mapped Java PSI element for evaluation. IntelliJ's Java
runtime evaluator therefore sees the actual companion method and its parameters
and locals. A code-fragment factory supplies that same verified scope to expression
editors using the requested source line, without consulting the globally selected
stack frame. Two factory registrations cover explicit Java and implicit Vernac
file-type requests. Already supplied Java PSI contexts are preserved. The host
lexer retains one injection-host node but emits line-sized leaves so context lookup
does not collapse every source position to offset zero. Ambiguous or stale mappings
are rejected rather than borrowing another method's scope.

The breakpoint type returns no replacement source position. IntelliJ's line
breakpoint therefore retains its original Vernac file and line. Calling the
breakpoint's source-position accessor inside the type's position callback recurses;
returning generated Java coordinates here would also break Vernac request lookup.
A regression test supplies a breakpoint that fails on any accessor call and checks
that this callback returns null without consulting it.

The breakpoint evaluation element continues to point at generated Java for runtime
conditions and evaluated log messages. Expression editors before a debug session
require a current, unique source mapping. Highlighting/completion still require
installed-IDE acceptance of the new editor routing. Logging and suspension remain standard IntelliJ options.

The unit test checks separation of navigation and evaluation; it does not run the
IntelliJ expression engine. SDK compilation verifies the extension APIs. Installed
IDE acceptance must check `result + suffix`, `self.string()`, Watches, a condition,
a non-suspending logpoint and View Source, including after a plugin restart.


## Next increments

1. Inline usecase execute and private helpers now share companion fingerprinting
   and verified body mapping. Real-JVM checks inspect their parameters, locals and
   Access self; a result-record mapping test preserves static Result imports. An
   identical-body regression distinguishes execute from an earlier helper using the
   grammar. Complete installed-IDE acceptance with UsecaseMappingProbe before
   treating this as accepted UI coverage. The probe does not test transaction advice.
2. Domain service operations and private helpers now share the verified mapping
   and fingerprint path. Source tests cover overloads, uses dependencies and external
   delegates; real-JVM tests exercise public operations and private helpers. Complete
   installed-IDE acceptance with ServiceMappingProbe. Require rules for domain types, usecases and services now have separate validation
   mappings. Synchronous/deferred listener execute and private helpers are also mapped, preserving
   actual worker stacks and transactions. Complete installed-IDE listener acceptance
   before closing this mapping phase.
3. Core expression-editor resolution and completion are now confirmed in the installed
   IDE. Verify newly mapped adapter/conversion frames separately, then inspect wrapper
   stepping and Read/Write/Access views, preserving the contributor Java view.
4. Add installed-platform integration tests for gutter creation, expression evaluation,
   condition/logpoint execution, source navigation and stale-code behavior. Extend
   the current mapping/JDI/unit tests rather than treating them as UI acceptance.
5. Evaluate dependency-module source attachment, alternate source/output roots,
   remote debugging and hot swap independently before claiming support.

The [public tutorial](../tutorials/debugging.md) exposes the working slice without
promising full-language coverage. Cross-language rename/refactoring remains a
separate workstream in the IDE roadmap.


## Usecase implementation notes

Fingerprint creation is shared by BehaviorGenerator and UseCaseGenerator. The latter
preserves its companion's Result static imports when inserting the private field.
Usecases and ListenerNode.operation now share this mapping path. Listener companions
live in adapter.inbound.event; both synchronous and deferred modes carry source/runtime
fingerprints. Delivery mode does not change source coordinates or Java stack structure.
The debug map treats execute and helpers as copied bodies without manufacturing a
synthetic public method or changing the language AST. Execute body discovery accepts
only direct usecase behavior members, not a private helper with matching text.
External execute implementations receive no guessed mapping. Validation delegates
use the separate verified rule-entry mapping described below.


## Domain service implementation notes

BeanBehaviorGenerator fingerprints only actual domain service companions in this
increment. Outbound adapters share that generator but are not opted into mappings.
DomainServiceNode methods enter the existing domain-package mapping path; generated
validation delegates and externally implemented operation bodies remain excluded.
Overloads retain separate source/Java line mappings. JVM tests filter by source file
as well as method and statement, since usecase and service helpers can have the same
name and identical source lines in different files.


## Validation rule entry mappings

ValidationSourceMaps covers user-authored require rules on ordinary VOs, entities,
aggregates, usecases and domain service operations. Their generated validation
companions receive the same source/runtime fingerprint as behavior companions.
Adapters and listeners are deliberately not opted into this increment.

The mapper parses actual and expected conditions with the JDK Java parser. Method
names, rule counts, canonical condition trees and if/throw structure must match.
A changed structure publishes no mapping for that companion. It does not infer
conditions from formatted Java strings. The expected condition uses the same
failure-condition helper as the corresponding generator; validation semantics do
not change in this increment.

Exactly one entry maps a require opening line to the generated condition's first
expression line. Parentheses, unary operators, casts and leftmost binary/conditional
operands are unwrapped to locate that expression for multiline conditions. A
compiler-optimized constant condition may still have no bytecode location. Throws,
null checks and wrappers are not additional user-authored rule entry points. Stepping
into those unmapped instructions may still display generated Java.

Source tests cover multiline rules, Unicode/CRLF, multiple rules, overloaded service
validator indexes, stale mapping cleanup, metadata hashes and rejection of changed
condition structure. Ten real-JVM runs cover passing/failing VO, entity, aggregate,
usecase and service rules, checking a single stop, runtime fingerprint and Read/input
self. The mapping probe is not a Spring transaction test. Installed-IDE acceptance
must check breakpoint binding on require, self access, condition evaluation and
normal exception behavior after resuming.


## Listener mapping and execution boundaries

The existing usecase companion generator now fingerprints listener implementations
too. addOperation maps execute plus ordered private helpers for either package path.
Execute body search uses direct usecaseBehaviorMember grammar nodes for both usecases
and listeners, so an earlier private helper with identical text cannot steal the
execute location. External listener implementations continue to be unmapped Java.
Listener source tests cover both delivery modes, dependencies, private helpers,
repeated bodies, CRLF/Unicode, hashes, sidecar round trips and stale-map cleanup.

Four JDI cases exercise synchronous/deferred execute/helper mapping. They compile
the actual example, invoke generated handlers directly, verify event/self/locals,
runtime fingerprints and real foreground/background stack boundaries. These are
mapping probes, not an outbox or transaction test. No fake emitter stack is installed.
Existing EventExecutionTest and database-backed EventWorkerPostgresTest own delivery
and transaction coverage; a local database is required to rerun the latter. The
persistence demo is the installed-IDE acceptance path for actual publication/worker
processing. Source mapping does not change claim leases or retries.

Remaining current debugger closure work: adapter/mapping installed-IDE acceptance,
ordinary local-value editing acceptance and a final tutorial audit. Leave advanced
renderers, dependency sources, remote debugging and hot swap
as explicit follow-ups rather than blocking the queued AI-disclosure review forever.
That review remains first after closure of the current debugger increment.

## Expression-editor regression scope

Host lexer tests cover LF, CRLF, CR, Unicode/UTF-16 offsets, partial ranges and empty
input. Factory tests cover both requested file types and preservation of an existing
Java context without intercepting ordinary Java editors. These tests and exact-SDK
compilation do not run IntelliJ completion or its expression-analysis UI. Acceptance
must separately verify resolution, Ctrl+Space, helpers and breakpoint editors.

Local validation for this increment: the changed sources compiled against IntelliJ
262.10968.63 APIs; seven focused plugin tests passed, together with ten release-tool
unit tests and all local documentation links. The standalone plugin test harness
used upstream kotlinx-coroutines 1.10.2 for IconLoader initialization because the
IDE-patched dependency was unavailable locally. This is not a full reactor build
or an installed-IDE completion test.

## Adapter and ACL coverage

BeanBehaviorGenerator now fingerprints inline adapter companions as well as services;
BehaviorSourceMap visits adapter methods with the existing copied-body matching.
Rest-generated operation bodies and implemented-by bridges are not copied user Java
and do not gain artificial inline statement locations.

MappingGenerator fingerprints Java mapping companions and mapping holders in both
adapter directions. MappingSourceMaps maps copied Java bodies through the existing
body matcher. For declarative mappings it checks the generated method shape, target
local names and target-path literals against the resolved assignments. Explicit
assignment locations are looked up by target name, not source declaration order.
Implicit defaults do not gain user-source locations. Inbound execute bindings use
the resolved usecase-parameter order and generated conversion locals in the controller's
try block. Controllers carry a fingerprint too. Event requires use ValidationSourceMaps
and the existing state-validation generator. Adapter preconditions remain rejected
by PortContracts; enabling them is a separate language decision.

AdapterSourceMapTest covers both directions, Unicode/CRLF positions, optional defaults,
assignment-order differences, sidecar persistence/cleanup and rejection of altered
assignment structure. AdapterDebugExecutionTest launches real JVMs for nine cases:
adapter method/helper, Java mappings in both directions, declarative mappings in both
directions, inbound argument conversion and passing/failing event validation. Each
checks executable bytecode locations, fingerprints, method identity and visible locals.
The debugger example contains a direct-call AdapterMappingProbe; it does not start
HTTP, create transactions or publish events. Installed-IDE acceptance remains separate.

Next: inspect technical stepping stops and Read/Write/Access presentation without
weakening their access contracts. Dependency-source attachment, remote debugging and
hot swap stay explicit future work. AI-content disclosure review follows this workstream.

Validation of the adapter increment: all 422 compiler tests passed; nine new adapter
JDI cases and 30 existing Maven/mapping/JDI regression cases passed with no skips.
All six debugger-example tests passed after direct generated-Java compilation, and
ten release-tool tests plus 567 local documentation links passed. This was focused
local compilation/test execution, not a complete Maven reactor or native IDE UI run.

## Honest facade presentation and conservative stepping

The user has confirmed adapter/mapping debugging as well as the core expression
editor. This increment adds VernacViewInspector, VernacViewRenderer and
VernacViewSteppingFilter. It changes plugin presentation only, not generated domain
interfaces, mutation guards, runtime semantics or the source-map format.

The inspector recognizes facade contracts using their owner/access-interface
relationship. Rendering/filtering additionally requires a current source/generated
map and a matching loaded companion fingerprint in the facade's class loader.
Absent, stale or unverified metadata leaves normal Java presentation/stepping intact.

Property inspection interprets only a fail-closed subset of loaded JVM getter
bytecode: aload_0, checked getfield chains, recursively checked virtual field getters,
Optional.ofNullable as a storage-projection marker, and typed return. It performs
JDI field reads, never invokeMethod. It rejects computations, writes, branches,
guards, arbitrary calls, recursion, incompatible backing names and unsupported VM
information. Computed read behaviors are not silently evaluated. The returned
property is the real final backing field/object pair; captured parameter fields
must match the accessor name. Updates are read again when the tree is refreshed.

The renderer retains real Java type/object identity and adds the contract/role.
Its children are custom non-lvalue descriptors, with no modifier even for Access.
Optional children are labeled nullable stored-value projections. Relative child
expressions use this.accessor(), with orElse(null) only for an optional projection;
IntelliJ can compose the actual parent expression when creating a Watch. Merely
opening a tree does not evaluate that expression. Other IDE/user-supplied renderers
for expanded child objects retain their own behavior.

The extra stepping filter requests STEP_OUT only while actually inside one of
these verified facade field accessors. It is not a package filter and does not skip
a public wrapper merely because it is generated. In particular BehaviorModification,
MappingChecks, event dispatch and other callback-bearing code are not stepped over
as a unit. Existing explicit breakpoint requests, exception requests and stack frames
are not changed. Show Generated Java in Debugger disables both enhancements; use
a fresh session when comparing modes.

VernacViewInspectorTest launches a real Java 21-targeted JVM fixture covering named
Read/Access facades, inherited getters, owner delegation, anonymous validation
captures, optional absent/present storage, live updates and a side-effect counter.
Computed getter calls and mutation methods are rejected, and the counter remains
zero. This tests the inspector/filter predicate, not IntelliJ's native tree or step
engine UI. Installed acceptance must cover expansion, labels, Add to Watches,
read-only projected nodes, stepping, explicit/exception breakpoints and contributor
mode before declaring those interactions confirmed.

Local verification: eight focused plugin tests passed, including the real-JVM
inspector test; ten release-tool tests and 568 local documentation links passed.
All seven tutorial model blocks still match executable source files. The changed
plugin classes compile against IntelliJ 262.10968.63 APIs without deprecation
warnings. As in the expression-editor increment, the local headless SDK harness
uses upstream coroutines 1.10.2 for missing IDE runtime dependencies. No full Maven
reactor or installed-platform UI test was run for this presentation increment.
