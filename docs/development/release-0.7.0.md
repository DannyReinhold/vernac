---
title: Vernac 0.7.0 development plan
---

# 0.7.0 · Fast application builds and tests

**Release target.** A developer or agent edits an application, gets typed diagnostics, rebuilds its
runnable development version and executes the affected Vernac scenarios in a warm workspace. A fresh
checkout can also produce and run a complete Spring Boot artifact through the Vernac build tool. **0.7.0**
is the working version for the release after [0.6.0](release-0.6.0.md). The commands and budgets below are
proposed interfaces and acceptance targets.

The starting point is a Java-generating compiler, a strict whole-project resolver, an LSP with unsaved
overlays, a generated-source ownership manifest and Maven integration. The 0.6.0 test corpus supplies
expected outcomes independent of the new compiler. Use the existing Java 21 application target, JVM
provider contracts and Spring transaction boundaries when adding the interpreted execution path.
[Compiler architecture](compiler-architecture.md) · [Editor analysis](lsp-workspace-analysis.md) ·
[Generated output ownership](generated-output.md)

## Implementation sequence

The critical path runs through B0, B1, B2, B3 and B4. B5 can begin CLI packaging against the clean
compiler during B1 and finish its warm path after B4. Both paths meet at B6.

| ID | Depends on | Deliverable | Proof at the milestone |
| --- | --- | --- | --- |
| B0 | 0.6.0 corpus and baseline | Pin the task board, document fixture, toolchains, real edit scripts and timing harness. Capture cold and warm Maven timings and independent expected results. | A second checkout reproduces the same fixture outcomes and reports input sizes, phase times and selected profiles. |
| B1 | B0, 0.6.0 expression contract | Complete the specification for keyword messages and shared expressions. Implement the opt-in form, type/effect checks, source locations and versioned executable representation. | Positive and invalid specimens cover a transition, port call, event, optional value and nested invariant. The original 0.6 test sources still parse and mean the same thing. [Behavior language decision](behavior-language-decision.md) |
| B2 | B1 | Implement the interpreter and JVM bridge. Run one scenario end to end, then port the task lifecycle and the document fixture's business rules. Use the same interpreter in tests and in the packaged application. | Fixed inputs yield the expected state, rejection, events and port calls through both development and Spring entry points. Read the same retained aggregate and child references after a rejected interpreted modification. |
| B3 | B2 for end-to-end proofs | Add an incremental query/dependency graph and conservative test selection. Validate edits against a clean compiler and full test oracle. | View-only, body-only, signature, role, module, schema and deletion edits produce correct results. Counts demonstrate reuse for local changes and widening for opaque inputs. |
| B4 | B3 | Host the compiler, interpreter and test runner in a per-workspace daemon. Implement atomic revisions, private client overlays, cancellation and restart. Connect CLI, LSP and a local MCP adapter. | Two independent clients edit and check concurrently. Each result names the requested revision. A stale edit, cancelled run, repeated batch and daemon restart all have reproducible outcomes. |
| B5 | B0 to start, B2–B4 to finish | Deliver the standalone build tool, its project configuration, launcher and distribution. Build warm runnable application updates and clean Boot artifacts. Share the build core with Maven integration. | A fresh generated project builds and starts using the Vernac CLI. A mixed Java/Vernac app and a module consumer build successfully. A running development instance observes a changed behavior and a changed view. |
| B6 | B2–B5 | Check parity, correctness and latency on pinned revisions. Exercise the installed CLI and IDE, document migration and package the release. | All required scenarios and clean-build checks pass. The reference benchmark meets the warm budgets with raw results attached. A degraded or partial run identifies the outstanding checks. |

## Execution model and interoperability

The compiler resolves names, types and effects into a versioned
**typed intermediate representation (IR)**. Keep source locations and stable declaration identities with
each operation. The 0.6 test runner already distinguishes fixtures, actions and observations. Lower its
expressions and the new behavior expressions through the same semantic rules. Distinguish the pure
operations allowed in assertions and view bindings from writes, port calls and event emission allowed in
business code.

The interpreter evaluates the new bodies directly in the warm JVM. Generated JVM APIs continue to expose
domain operations to Java callers. In the packaged application their bridge invokes the same IR through
the existing authorization, transaction and provider wiring. Store the IR with its format version in the
artifact. Check runtime/format compatibility before executing an operation. Errors and stack locations
must name the original Vernac declaration and source span.

Native behavior statements now lower to a versioned in-memory program executed by the runtime.
The generated method calls this interpreter for local bindings, requirements, assignments, sends,
statement branches and returns, within the existing JVM behavior boundary. The runtime rejects
an incompatible program version before execution. Expressions have explicit runtime nodes for literals,
operators, optional matches, accesses and calls. The generated JVM bridge still constructs those
nodes and supplies the host accessors and call targets. Boolean branches, integer-to-decimal
conversion, rounding, division with rounding, date and duration operations, and typed collection
queries, including projection into a declared collection, also have dedicated runtime nodes.
The scenario runner and Maven build write versioned statement positions under
`META-INF/vernac/native-sources/`. A runtime exception raised inside a native statement retains its
original type and carries the declaration, source-relative path, line and column as a suppressed
`SourceTrace`. Moving source lines in a native-only behavior refreshes that resource without
changing the generated Java. Copied Java continues to use the existing debug map. The same resource
directory now carries a versioned statement layout. At class initialization, the runtime loads that
layout and links each instruction slot to the generated JVM binding, checking the version, opcode
and slot inventory. Layout version 2 can also supply scalar expressions made from literals, method
arguments, locals, and prefix or binary operators. It still reads version 1 with its Java operands.
For expressions that need a host access or call, the compiler still provides the JVM operand.
Separating those bindings and replacing a program inside a live application remain open.

For interpreted aggregate modifications, stage writes, validate at the outermost boundary, then publish
validated state to the existing object identities. Nested modifications and invariant reads see the
candidate. A rejected outer modification discards all staged changes, including child changes, timestamps
and buffered event intents. A nested failure that is handled inside the outer scope needs one documented
rule and a conformance case before the feature ships. Isolate effectful external calls outside candidate
mutation. Durable delivery follows the existing transaction/outbox contract. Already executed external I/O
needs its own failure policy. A later database commit failure and a rejected in-memory modification have
different observation points. [Candidate-state design](entity-behavior-and-change-contexts.md) ·
[Event contract](../contracts/events.md)

Keep unmarked inline Java bodies and `implemented by` delegates readable throughout migration. Compile
changed host code through the JVM toolchain and widen invalidation where its effects are opaque. Technical
providers can implement the supported JVM interfaces. A Kotlin/JVM provider can be checked in an interop
fixture using its normal build configuration. An embedded Kotlin compiler and direct bytecode emission are
possible follow-ups to a measured bottleneck. The required execution backend for this release is the
interpreter.

## How incremental compilation decides what to run

Start by splitting existing `VernacProjectLoader`, `ProjectTypeResolver`, `WebViews` and generation work
into repeatable queries over explicit inputs. Keep a clean evaluation mode as the reference. Key queries
by project/module, declaration identity and requested result, and record the queries they depend on.
Compute separate fingerprints for a public signature, executable body, relevant permission/policy,
projected view data, template and persistence schema. A recalculation with an identical result can stop
further propagation. Retain source ranges independently so a line move refreshes diagnostics and debugging
even when behavior stays identical.

Include name lookup scopes and failed lookups, source-root membership, imports, module descriptors and
selected provider bindings. Fingerprint compiler/IR versions, JVM release, application configuration,
classpath contents and relevant resources. A new symbol can resolve a formerly missing name or make a
wildcard import ambiguous. Use the old and new dependency edges for renames and deletions. Stage graph and
output changes by revision. Tolerant editor diagnostics may use an invalid snapshot. Publishing a runnable
revision or artifact requires successful strict compilation.

| Example change | Results that require reconsideration | Test-selection proof |
| --- | --- | --- |
| Edit a label in `TaskRow` | Component, including views, their template and resource output | The rendered row assertion reruns. Task persistence artifacts keep their fingerprints. |
| Change a business rule used by a command and a view projection | Rule body and transitive users including the projected action | Both the direct command and view cases rerun, even if the previous run never entered the changed branch. |
| Change a public field type or endpoint input | Signature, callers, form bindings, module consumers and generated JVM bridges | A wrong binding fails at its source span. A repaired multi-file edit yields the same new results as a clean build. |
| Change a role, provider, schema or module contract | Effective role grants, provider users, migration/schema inputs or affected module consumers | Run the corresponding authorization, integration or module group. Report pending PostgreSQL proofs when only the fast profile was requested. |
| Delete a declaration, change imports, rename a fixture or edit opaque Java | Old and new lookup scopes, test inventory, owned artifacts or the affected host module | Removed scenarios disappear, newly reachable cases run and obsolete classes/resources disappear from the next artifact. Unknown reach widens to the complete applicable suite. |

Select tests from a conservative **static** transitive closure over fixture setup, production calls,
assertions and their registered providers. Runtime observations can explain a selection or refine a proven
bound. Historical coverage alone misses newly reachable branches. Cache deterministic outcomes only with
complete inputs. An explicit full run executes the suite, including requested volatile or infrastructure
checks. For replayed patch sequences compare complete diagnostics, normalized artifacts and case outcomes
to a clean compile/full run. Inject a failing rule for a formerly untaken branch and demonstrate
detection.

For the first vertical slice, make one edit to the document rule and return its direct-command and view
results from the warm core. Capture the trace before adding more declaration kinds. Treat an unsupported
kind, a new dependency edge that cannot be bounded, or an unreadable cache as a reason to recompute the
enclosing unit and its consumers. Track such fallbacks by cause so the next optimization targets an
observed cost.

### Implemented parse reuse

The loader has an explicit parse session per source root and client. It reuses a file's parsed
unit and syntax diagnostics when its normalized path and effective text match the previous request.
Editor recovery and strict parsing keep separate entries. Each request rediscovers source membership,
rebuilds the symbol index, and resolves the project again. Removed files are dropped, and a renamed
file is parsed at its new path so diagnostics get the correct source coordinates.

The Language Server uses one session per active source root and drops it when the root is no longer
being analyzed. The clean loader remains available for comparison. Regression tests replay a changed
overlay, a syntax error, deletion and repair and compare the resulting diagnostics and generated
sources against the clean path. Parse counts show reuse at this stage. The warm 500 ms p95 release
gate covers the full patch-to-test result and remains to be measured.

### Scenario selection

The compiler exposes scenario IDs, profiles, Java fixture usage and resolved direct references.
The selector can use source dependency graphs for the previous and current revisions. It follows
both graphs so that removal of an old edge cannot hide a dependency. Missing dependency information
and Java fixtures widen selection. An opaque change or the absence of a baseline selects the full
requested profile. Results include the reason for each selected case.

Graph construction now certifies plain ID and value declarations and follows resolved value field
types to their source files. Files with behavior, validation, collections or other opaque declarations
remain uncertified and trigger a conservative fallback when reached. The compiler test combines
two checked workspace revisions with the derived graph and verifies that an unrelated scenario is
excluded. A planning session now compares exact production and test source snapshots and returns
case IDs and reasons for the target revision. The caller supplies a verified baseline from the
same session and profile. A foreign or mismatched baseline selects the full profile. Broader
dependency extraction and the connection to the warm runner remain open. A plan can now
materialize its selected JUnit methods from the same checked scenario texts without parsing
the tests again. The regression suite compiles and executes both the affected and full lists
after a validation edit and checks that both expose the same failing scenario.

The execution session now passes the checked production model and selected JUnit bridge to one
executor call. It advances its comparison baseline only after the backend reports a pass for every
selected case. An incomplete report, a skipped test or a failed run cannot establish a new baseline.
Opaque input changes invalidate the old baseline even if the attempted run fails. The in-process
test backend exercises this contract. An optional in-process JUnit executor now compiles generated
Java with the Java 21 target, runs the selected Jupiter scenarios in the same process and isolates
each generated revision in a dedicated classloader. Its caller supplies the dependency classpath
and the Jupiter engine. The executor caches application and scenario bytecode separately. A test-only
edit can reuse the last application compilation. An application change also invalidates the test cache.
Every run still loads the classes in a fresh loader. The caller signals opaque input changes such as
replacement classpath entries to invalidate both caches. Host source builds, finer-grained reuse
within application builds, and integration-profile acceptance remain open.

## Daemon, editor and agent protocol

Use a local daemon per isolated workspace/worktree and compatible toolchain. Reuse the existing LSP
overlay behavior and UTF-16 positions. A request names the workspace, client session, base revision and
operation. Apply a batch of file creates, edits, renames or deletions atomically. A stale base revision
returns the current revision and a conflict. Replaying the same batch with its request ID returns its
original outcome. Keep private editor and agent overlays distinct from the shared saved tree. A build
requested against saved files sees only the saved revision.

Provide operations to open a snapshot, apply edits, check, test, inspect selection reasons and retrieve or
cancel results. Publish diagnostics and outputs only for their matching revision. Bound concurrent work,
retain a limited number of old snapshots, release them when idle and recover by rebuilding from saved
inputs after cache loss or an incompatible upgrade. Test workspace isolation, interrupted publication,
retry and recovery in a fresh process. Keep per-case fixture state out of the daemon's shared caches.

The CLI and LSP are clients of the same service. Expose snapshot edits, check, affected tests and
structured results through a thin **MCP adapter** over local stdio. Bind agent edits to its own
workspace/session. Return request and snapshot IDs, diagnostics with ranges, selection reasons, profile,
cases executed or reused, skips, phase timings and outstanding checks. Long-running database or package
checks should provide a run ID so a CPU runner can finish them between model calls. The integration proof
compares equivalent CLI, IDE and MCP requests and disconnects an agent mid-run before retrieving its
result.

The compiler now has an in-memory source revision store for atomic create, replace, move and
delete batches. A stale base reports the current revision. A retained snapshot can be parsed
independently of later changes on disk or in the client. Daemon transport, retention limits
and request replay are separate milestones.

The in-process scenario workspace now uses one revision for production and test sources.
A client can update both sets in one atomic batch. A run captures the requested revision before
it enters the executor, so concurrent edits do not change its inputs. New requests against
a stale revision receive the current revision. This entry point uses the conventional Maven
Vernac source roots and keeps captured inputs in memory. A client overlay opens on a specific
shared revision and starts its own revision sequence, parser session and test baseline. Later
edits to the shared or a sibling workspace do not change it. An embeddable local transport now
accepts one JSON request per line and returns a correlated response. It serves shared revision
queries and opening, editing, checking, running and closing overlays. The transport handles requests
sequentially and reports stale revisions and validation diagnostics. A fresh JVM can start
`org.vernac.compiler.pipeline.ScenarioDaemonLauncher <workspace> [domain|mvc|postgres]`
with the compiler, Jupiter engine and project dependencies on its Java classpath. It captures the
saved Vernac sources, module descriptors and build-generated module index at startup, and uses UTF-8
stdin/stdout for the protocol. Test output goes to stderr. A restart captures the saved tree again.
The distribution exposes the same session as `vernac daemon [workspace [domain|mvc|postgres]]`.
A client keeps the process open, sends one UTF-8 JSON object per line and reads each correlated
reply from stdout. `{"id":1,"op":"hello"}` returns `protocolVersion` and the current saved
`revision` in the reply's `result`. Closing stdin ends the session.
The launcher includes the project's Maven `target/test-classes` and `target/classes` outputs.
Handwritten fixtures and application classes there share a classloader with the new generated classes,
so Spring and the scenario bridge use the same type identities. Compile Java fixtures and prepare
their resources before running the daemon.
Scenario runs load generated HTML from the requested Vernac revision. Project build outputs
cannot shadow those templates or supply an old local template after its declaration is removed.
Ordinary resources and templates from dependencies keep their normal lookup behavior.
The `refresh` request takes the expected shared `revision` and scans both saved Vernac source roots.
It publishes additions, changes and deletions in one shared revision. If the sources are unchanged,
it returns the same revision. Open overlays retain their snapshots and new overlays can open on
the refreshed revision. Changed configuration or module index data requires a daemon restart.
`checkSaved` takes the expected shared `revision`, refreshes the saved sources and returns
revisioned type and scenario diagnostics. It uses the same analysis as overlay `check` and does
not execute tests.
`runSaved` takes the expected shared `revision` and combines that refresh with an affected test run
in the same request. The shared workspace retains its compilation cache and its last passing baseline
across requests. An optional `opaqueChange: true` requests conservative execution after changes outside
Vernac source files. When source validation fails, `sharedRevision` reports the revision already
captured from disk, so the client can retry after repairing the sources.
Automatic file watching, overlay persistence and cancellation remain open.

### Structured model operations for agents

Use the compiler's resolved model for `describe_type`. Address declarations by qualified Vernac
identity and return fields, declared methods, access mode, parameter and return types, and source
locations. A read should use an existing resolved snapshot. It should identify that snapshot and
surface unresolved types when the snapshot is partial. It needs no file reload or compile by itself.

Start with `describe_type`, `preview_add_value_field`, and `preview_create_aggregate`.
The aggregate operation proposes a declaration and, when requested, its ID declaration. It takes
the namespace and target source explicitly. The field operation proposes one insertion and preserves
surrounding text. The compiler now provides the type description, field insertion preview and
new aggregate source primitives. The field preview rejects stale source content and both preview
outputs can be checked through an ordinary parse session. MCP transport, revision assignment
and checked application of the batch are still pending.

Structured changes should produce the same versioned source edit batch as direct editor changes.
Return the preview with the base revision, reject conflicts, and apply related edits atomically.
Run the same diagnostics, invalidation and affected-test selection regardless of which operation
created the patch. Keep ordinary text patches available for operations that span multiple constructs.
A field insertion changes the value type's signature and may affect constructors, validations,
serialized forms, schemas and consumers. Selection must widen to those dependents where applicable.
Batch several intended changes before checking to avoid repeated semantic work.

Cover arbitrary source constructs through a small shared interface: inspect or navigate by
semantic identity where one exists, locate syntax by revision and range otherwise, and submit
versioned source edits. Add typed refactorings where the compiler can maintain references or
enforce a useful contract. Common operations can use convenience tools. Avoid one transport
tool per grammar production: some constructs have no stable semantic ID, and an internal AST
should be free to evolve independently of the tool contract.

Measure model-read round trips separately from preview and from patch-to-test latency. A quick
symbol lookup or a cheap edit preview does not establish the 500 ms p95 target for the full workflow.

### Versioned language help

Expose the bundled [AI Language Reference](../ai-language-reference.md) and selected [FAQs](../faq/index.md)
through read-only MCP resources. Give each section a stable key and identify its Vernac version in the
response. Offer a small search operation for clients that discover help through tools, returning matching
section titles and links. Use the published documentation as the source for both the bundle and search
index. Keep help separate from workspace inspection so asking about syntax does not trigger compilation.

## Standalone build tool

Build the CLI ZIP from the repository with JDK 21.

```sh
mvn -pl vernac-compiler -am -Pcli-distribution package
```

Extract `vernac-compiler/target/vernac-<version>-cli.zip` and add its `bin` directory to `PATH`.
The ZIP includes `vernac` for POSIX shells, `vernac.cmd` for Windows and the base runtime
dependencies. The launchers use `JAVA_HOME` or `java` from `PATH`. Running the extracted CLI
requires a JDK and can work without Maven installed. The current directory is the default workspace.

```sh
vernac check
vernac test --scenario app.Checks.counter
vernac test /path/to/project mvc
vernac daemon /path/to/project mvc
```

The CLI reads saved sources and writes one JSON result to stdout. Status 0 indicates success, 1 a
source error or a non-passing test outcome, and 2 an invalid invocation or execution error.
`--scenario` selects one fully qualified scenario ID in the chosen profile. For scenarios using
external libraries, supply their Java classpath via `VERNAC_PROJECT_CLASSPATH` using the platform's
classpath separator. The runner picks up precompiled project fixtures from `target/test-classes`
and `target/classes`. The daemon's `runScenario` offers equivalent selection on an overlay
revision without advancing its affected-test baseline. The one-shot commands open a fresh workspace.
Clients can retain warm state by keeping `vernac daemon` open. Connecting a one-shot command to a
separately running daemon and resolving project dependencies remain open.

| Proposed command | Contract to implement and test |
| --- | --- |
| `vernac check` | Strict type and effect analysis for the requested revision. Supports structured diagnostics and a nonzero exit on errors. |
| `vernac test --affected` | Compile the required inputs, run or explicitly reuse eligible scenario results, and explain selection. Default to the complete selected profile when there is no valid comparison revision. |
| `vernac test --all` | Run every case in the selected profiles and report required versus unselected integration checks. Support machine-readable output plus JUnit XML for CI. |
| `vernac build --dev` | Produce or activate a runnable update in the warm workspace. A successful result identifies the revision served. On a failed patch the previous valid revision can still serve requests and the failed target revision is explicit. |
| `vernac build` | Resolve inputs, compile Vernac/host sources, assemble production resources, IR and JVM classes and produce a startable Boot artifact in a clean workspace. Inspect the artifact, launch it, render a page and execute a real use case. |

Vernac owns task ordering, invalidation and artifact assembly decisions for supported application inputs.
Reuse dependency resolution and Boot packaging libraries/tools as appropriate. A small edit to a
interpreted behavior or view should execute its required tasks inside the warm process. Capture the actual
processes started and regenerated files. The acceptance trace must show that a warm edit to an interpreted
body avoids a recursive Maven lifecycle, a fresh JVM compiler launch and a full Spring restart.

Activate a validated bundle of behavior IR, templates and bindings for a development revision as one unit.
Pin each in-flight request to its starting revision. Test an edit that changes both a component and its
behavior while one request is still running. Each response must match one complete revision. A JVM
signature or provider change can require rebuilding classes or restarting the application. Expose that
wider operation and its timing to the caller.

In B5 define one resolved project model for roots, versions, dependencies, profiles, JVM release and main
class. The wizard can import its existing POM. Prefer a small declarative YAML descriptor for standalone
projects that need one. Select one authoritative input per project and diagnose ambiguity. Specify
resolved dependency versions and supported Maven customizations. Report unsupported configuration before
packaging. Use an existing dependency resolver with pinned inputs and a reusable artifact cache. Verify
the distributable launcher on Windows and Linux with only the documented JDK/tool prerequisites. Record
tool and application JDK requirements separately.

Keep ownership and locking across generated sources, resources, IR, classes and final artifacts. A clean
rebuild and an incremental rebuild of the same inputs must contain equivalent normalized contents,
including absence of obsolete classes and templates. After a partial write or daemon crash, reconcile
owned outputs before exposing a new revision. Pass a hand-written Java delegate, an exported `.vmodule`,
an application `.vapplication` and a schema/migration resource through both Maven and CLI consumer builds.
Check that public signatures, observable HTTP behavior and migration inputs agree.

## Performance acceptance

Measure the interval seen by a local agent client, from submitting changed source until the complete
usable response. Include IPC, parsing, invalidation, execution and reporting. For a development build,
stop the clock after a probe observes the new revision in the running application. Also report individual
phases, including launcher time for a fresh CLI invocation. GPU scheduling and cloud inference are
independent of this CPU measurement.

| Operation on a warm reference workspace | p95 release target | Workload |
| --- | --- | --- |
| First complete type diagnostic | ≤100 ms | One typed view binding or interpreted body edit, including a deliberately invalid edit |
| Runnable incremental development build | ≤500 ms | Update one interpreted rule or template and observe its changed output |
| Patch through affected test results | ≤500 ms | One local edit and a small affected suite with a real command and a view assertion on the document fixture |
| Cold clean build, broad signature/schema change, full suite, browser and PostgreSQL | Measure and publish each separately | Fresh checkout with tool/dependency cache state declared, plus a wider module-consumer workload |

In B0 pin a reference runner (CPU model, allocated cores, memory, OS, JDK and power settings), the fixture
sizes, exact edit corpus and expected selection. Use the same machine class for the acceptance run. Start
three independent warm sessions per workload. Warm each with 20 edits, then time at least 100 distinct
effective patches per session. Include both passing and intentionally failing inputs and validate their
expected outcomes. Compute the nearest-rank p95 per workload and session. Retain raw samples, p50/p95,
selected/executed counts, cache-hit counts, memory and recreated processes. Publish cold starts and
first-use dependency downloads separately. Compare matched work with the 0.6 baseline. Repetitions served
entirely from cache do not establish changed-input latency.

The repository benchmark exercises the daemon's `runSaved` request on Document Workspace.
It copies the example and its prebuilt fixtures into a temporary workspace for each JVM session.
It measures from writing a changed source file through reading the JSON reply, and verifies the
selected cases and their outcomes. Every fifth edit intentionally fails the editor scenario while
the restricted reader scenario still passes. These workloads change the MVC assertion, the native
`DeleteDocument` body, or the generated post-delete HTML checked by the editor scenario. The
`view-diagnostics` workload edits a view heading and makes
every fifth edit an invalid typed form binding. It verifies the diagnostic message and location
or the repaired source's successful check. Run Maven preparation and measurement on the same JDK.

```sh
mvn -pl examples/document-workspace-scenarios -am \
  -Dtest=ScenarioDaemonTest,VernacScenariosTest -Dsurefire.failIfNoSpecifiedTests=false test
python3 tools/benchmark-saved-scenarios.py --max-p95-ms 500 > saved-mvc-assertion.json
python3 tools/benchmark-saved-scenarios.py --workload delete-behavior --max-p95-ms 500 > saved-delete-behavior.json
python3 tools/benchmark-saved-scenarios.py --workload view-diagnostics --max-p95-ms 100 > saved-view-diagnostics.json
python3 tools/benchmark-saved-scenarios.py --workload view-render --max-p95-ms 500 > saved-view-render.json
```

The JSON includes cold start and first run, raw samples, selected cases, outcomes and per-session
percentiles. The diagnostic workload records validation results and compiler messages instead of
case selection. A threshold miss returns status 1 and still writes the report. The benchmark
requires prebuilt Java fixtures and Maven resources. Template activation and development builds
have their own acceptance workloads.

## Release evidence and migration

1. Freeze the syntax decision and publish the behavior, test, effect and failure contracts. Check stack
   locations, completion, formatting, navigation and diagnostics using a packaged IntelliJ plugin. Show
   how to incrementally convert a Java behavior and how to keep a technical provider in Java. Document the
   CLI and MCP protocol, exit statuses, revision handling and profile selection in the AI tools reference.
2. Run the 0.6.0 corpus unchanged against its original Java fixtures. Compare converted behaviors with
   independent golden outcomes and the original implementation where the contracts agree. Give the
   stronger candidate-state contract its own cases. Verify nested changes, aliasing, authorization denial
   before writes, clock/ID determinism, events, port failures, database rollback and actual durable
   delivery in their proper profiles.
3. Replay valid, invalid and repaired edit sequences in both clean and incremental modes. Include
   interrupted output writes, a stale client, incompatible IR/cache versions and a removed module export.
   Require equal results and explain every safe fallback. A clean evaluator must remain available to
   diagnose a mismatch.
4. On a fresh machine, install the candidate distribution, build both wizard projects and the document
   fixture, run the Boot artifact and exercise a PostgreSQL-backed operation. Verify a separate JVM module
   consumer and a 0.6-to-0.7 application upgrade using reviewed migration inputs. Register the CLI/IR
   runtime artifacts and compatibility checks in the existing release inventory and fresh-consumer
   verification.
5. Attach the B0/B6 benchmark reports for the exact revisions. If a target misses, profile the dominant
   phase, correct it and repeat the same workload. Investigate direct JVM bytecode only if interpreter
   execution accounts for a meaningful share of the failing budget. Publish the measured scope alongside
   the release notes.
