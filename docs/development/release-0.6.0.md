---
title: Vernac 0.6.0 development plan
---

# 0.6.0 · Declarative views and executable tests

**Outcome.** A developer creates a Spring Boot project with the IntelliJ wizard, changes a modeled action,
and writes a Vernac scenario that exercises the action and its view. Maven runs that scenario against the
application's existing Java-backed behaviors. CI can run selected scenarios with PostgreSQL. The scenario
sources also serve as the compatibility corpus for the [0.7.0 development plan](release-0.7.0.md).

**Baseline.** At revision `db264bd` on `feature/0.6.0`, the compiler resolves typed views, generates
Thymeleaf resources and binds forms to named MVC endpoints. The wizard's task board, row component and
problem page use this path. View acceptance is in progress. The template already has Java domain,
rendered-MVC and opt-in persistence tests. Vernac test declarations, test discovery and their runner are
work for this release. [View tutorial](../tutorials/declarative-views.md) ·
[Task board source at the planning baseline](https://github.com/DannyReinhold/vernac/tree/db264bd0a41cfacfd96814a164fd11e18ccc1c64/templates/vernac-spring-boot)

The executable test sources are in both wizard templates and in
`examples/document-workspace-scenarios`. The wizard runs the domain and rendered
MVC scenarios with the default Maven test lifecycle. The Document Workspace
uses the same permission evaluator for its projected action and command guard.
Its `postgres` profile verifies persisted state after successful, denied and
rolled-back commands. Release verification checks the named cases in both
profile reports. [Scenario tutorial](../tutorials/domain-scenarios.md)

## Two applications of the test language

1. **The wizard task board** is the user-facing acceptance project. Keep its familiar create, complete and
   reopen flow. Use the existing Java tests as independent expectations while introducing Vernac
   scenarios. Exercise a project created by the packaged wizard as well as the template in the repository.
2. **A small Document Workspace fixture** makes one authorization rule observable from both a use case and
   a view. Model a document, a delete permission, a permitted actor, a denied actor, a typed view
   projection and a deletion event. Compute the projected action visibility from the same permission
   decision as the command guard. Bind the form through the supported enum condition and named endpoint.
   The complete SaaS example belongs to the
   [archived extension line](https://github.com/DannyReinhold/vernac/tree/16665729f87fc6dbe7a92d58acd6185889b4aa87/examples/saas-document-workspace).
   The new fixture uses only core Vernac and also supplies a repeatable workload for 0.7.0.

## Milestones and handoffs

Each row is a reviewable implementation increment. The identifiers provide stable references for
implementation PRs and their acceptance evidence.

| ID | Depends on | Deliverable | Acceptance before advancing |
| --- | --- | --- | --- |
| V1 | Current view changes | Complete the manual wizard review. Turn each discrepancy into a reproduction and regression. Record expected page, action, validation and error behavior. | Real wizard project renders the board and problem page. An open row offers Complete, a done row offers Reopen, and a successful submit returns to the board. Record the installed plugin and project revisions. |
| V2 | V1 scenarios | Specify test sources, scenario identities, fixtures, actions, observations and profiles. Extend the grammar, AST, symbol resolution and diagnostics. | The positive corpus resolves across files. Wrong value types, ambiguous imports, duplicate scenarios, missing endpoints and test-only names in application code produce located errors. A published application/module contains only its production inputs. |
| V3 | V2 | Implement discovery and a fast runner against generated Java and the current runtime. Connect it to the Maven test lifecycle. | A fresh CLI Maven run discovers the expected case IDs. A deliberately wrong expectation makes the build fail at the right source line. Repeated and parallel runs isolate fixtures, event observations and actors. |
| V4 | V2, V3 | Exercise named views and actions through the real MVC renderer. Add the PostgreSQL execution profile and the document fixture. | The acceptance matrix below passes on its specified backends. Selecting a required backend that cannot start produces a failing run with its cause. CI retains the executed case IDs and reports. |
| V5 | V2 for editor work, V3–V4 for final verification | Extend LSP, IntelliJ, formatter, wizard and documentation. Add release checks and freeze the 0.6 scenario corpus. | From the packaged plugin a developer creates a project, navigates from an assertion to a declaration, fixes a diagnostic and runs its tests. Release evidence includes generated-project, database, browser and compatibility checks. |

## Test contract to settle in V2

- Discover `*.vernac` under the proposed default `src/test/vernac`. Reuse namespace layout, imports and
  module export visibility. Test sources can see production declarations and their own fixtures. Resolve
  main sources independently so application code cannot depend on tests. Key a case by module, namespace,
  suite and case name. Preserve its source range independently of that identity.
- Use typed fixture builders, literal data and named references to declared operations. Bind values
  returned by the application, including generated IDs. Cases can create an actor, seed a test adapter,
  invoke a use case, submit an endpoint or render a view. Bind a fixed clock or ID provider where the
  application declares one. Make each case independent of ordering or wall-clock sleeps.
- Start assertions with typed equality, presence/absence, expected rejection, before/after state, event
  type/payload/count, calls to a declared port, visible actions and rendered field/text values. Describe
  whether an event observation means a recorded emission, a committed durable record or a completed
  delivery. An observation on a fake mail port records a call only.
- Keep expression rules small enough to specify in 0.6.0. Resolve literal types, enum members, field
  paths, optional values and equality once. Pin value equality and entity identity with positive and
  negative fixtures. Give each operation and assertion a resolved reference and a source span. Import this
  typed representation into the 0.7 interpreter design. Shared semantics do not require choosing
  behavior-call syntax in V2.
- Put backend bindings in test configuration. A scenario can declare needed capabilities such as a
  rendered MVC response or committed storage. An execution profile selects providers or reports the
  missing capability. A requested profile with zero matching cases, a failed setup, an assertion failure
  and a deliberately unselected case must be distinguishable in the report.

This notation is a **design specimen for V2**. The words and punctuation are provisional. The assertion
about the direct use case deliberately stands on its own even if the view would hide the form.

```text
suite DocumentAccess
  scenario MemberCannotDelete
    given workspace = selectedWorkspace
    given document = stored Document("Minutes") in workspace
    given actor = workspaceReader in workspace
    when render DocumentsPage as actor
    expect action DocumentsWeb.delete for document.id is absent
    when invoke DeleteDocument(document.id) as actor
    expect permission denied
    expect stored Document(document.id) equals its initial state
    expect emitted DocumentDeleted count 0
    expect calls to DocumentStore.delete count 0
```

In this case denial happens at entry. Java-backed `modify` currently retains field writes if a later
invariant fails. A database rollback also cannot rewind an already mutated Java object. V3 must observe
the actual object and committed storage separately. The stronger candidate-state guarantee belongs to the
interpreted behaviors in 0.7.0.
[Current failure contract](../contracts/entities-and-aggregates.md#failure-semantics)

## Execution profiles and integration points

| Profile | Implementation choice for the first runner | Observable evidence |
| --- | --- | --- |
| Domain and ports | Call the real generated domain/use-case entry point. Supply isolated fixtures and in-memory repositories or recording adapters. Exercise the generated guard for a protected operation. | Returned values, state, denial, recorded events and calls. Capture an independent value snapshot before a mutation so two references to one live object cannot yield a false pass. |
| Rendered MVC | Use the existing Spring MVC, Thymeleaf and security setup with a test repository. Resolve a view, component or action in Vernac and check the rendered response/DOM. | Form method, resolved URL and row identity, action presence or absence, escaping, validation status and redisplay. Match the intended row when several forms use the same endpoint. |
| PostgreSQL | Reuse the disposable database setup and generated JDBC/Flyway path. Start the service once per suite, isolate test data per case/worker and read committed results through a fresh transaction. | Real round trip, rollback and event/outbox visibility where configured. Run tenant isolation and migration proofs against PostgreSQL. An embedded SQL profile can be added if a measured need justifies its separate dialect contract. |
| Browser smoke | Start the packaged or freshly generated application. Visit the board, fill the form, submit and follow navigation in a real browser. | The rendered form works with CSRF, validation feedback stays usable and the action appears on the correct row after redirect. Keep browser startup outside the default domain test run. |

Pure domain tests can construct objects directly. For a protected use case the fast runner must enter
through the generated `execute` with an active, synchronized Spring transaction, a verified actor and the
selected tenant. Use a test transaction manager and recording stores for this profile. The existing
`AuthorizationGuardTest` shows how to establish the transaction and tenant in memory. Expect
`ACCESS_DENIED` for a denied actor with valid scope and a configuration error for a missing transaction.
Test these paths independently so an incorrectly wired fixture cannot satisfy the denial case.
[Use-case authorization contract](../contracts/usecase-authorization.md)

Implement V2–V3 next to the existing ANTLR grammar, project loader and resolver in `vernac-compiler`.
Produce a typed scenario plan with fixture, action and assertion nodes. For the first Maven integration,
generate a small test-scoped JUnit bridge and register test sources/resources in the Maven test phases.
Put execution behind a runner API so future clients can supply the same plan directly. Keep compiler,
launcher and test libraries off the production runtime classpath unless the application already needs a
particular dependency. Verify this with a built JAR and its dependency scopes.

Map failures back to `.vernac` file, line, assertion, expected and observed values. Publish JUnit XML with
stable case IDs and profile names. Include discovery counts, executed, failed, skipped and setup failures.
Give the editor the same main/test visibility rules and compiler diagnostics. Test completion and Go to
Declaration for use cases, fields and view actions, including incomplete source and unsaved edits.
Formatting twice must preserve meaning and yield the same output.

## Release acceptance matrix

| Case | Required observation | Execution |
| --- | --- | --- |
| T1 · Task lifecycle | Create, complete and reopen preserve the task ID and expose the expected status and row action. The empty board and a second page render correctly. | Domain, rendered MVC and one browser journey |
| T2 · Invalid input | A blank title returns 422 with feedback and no task is added. A request missing CSRF is rejected. A title containing markup renders as text. An unknown task reaches the problem page. | Rendered MVC, browser for validation and recovery |
| T3 · Protected deletion | With a permitted actor the correct action is visible, direct invocation succeeds and a single deletion and event are observed. With a denied actor the action is hidden. A direct command and a correctly formed direct POST are rejected, with no business write or deletion event. | Domain and rendered MVC, database assertions in T4 |
| T4 · Committed effects | After successful delete a fresh read sees the removal and expected durable event when configured. After denial or an injected transaction failure a fresh read sees the original document and no new committed event. | PostgreSQL, including a repeat run with clean fixtures |
| T5 · Test-system failure | Rename an action, change an assertion to the wrong type, alter one expected result, and select an unavailable required profile in separate runs. Each fails at the appropriate stage with a case or source reference. | Compiler, CLI Maven build and CI report audit |
| T6 · Compatibility | Build an existing all-Java-behavior application with its original tests. Build a wizard application containing both Java tests and Vernac tests. Inspect the production JAR for leaked test classes, fixture data and test descriptors. | Maven clean build, packaged wizard |

## Release evidence and handoff to 0.7.0

- Execute the full reactor under the repository JDK 25 baseline, the generated Java 21 application, the
  existing Java regression suites and the new scenario cases. In the generated project, exercise both
  `./mvnw test` and `./mvnw -Pdatabase-tests verify` with a disposable PostgreSQL database. Extend the
  release report audit to require the named cases in their assigned profiles and reject missing or skipped
  required proofs.
- Ship working examples in the basic and Boot wizard templates, with a short path from editing one
  expectation to seeing its diagnostic. Update specification, contracts, language and AI references,
  tutorials, plugin What's New and the next release notes. Synchronize the published version labels, JDK
  instructions, navigation and release-note links under
  [issue 122](https://github.com/DannyReinhold/vernac/issues/122).
- Archive the case sources, fixture bindings, independent expected results and the baseline timings in
  version control or reproducible CI artifacts keyed by the fixture revision. Time at least Maven startup,
  generation, Java compilation, framework startup, scenario execution and reporting. Give 0.7.0 that
  corpus and a repeatable patch to a view, a rule and a schema. These are reference measurements. The
  subsecond performance gates start in the follow-up release.
- Use the normal [release operations](../maintainers/releasing.md) for candidate verification, publication
  and fresh-consumer checks. Track view review findings until reproduced and resolved. If a backend cannot
  reproduce an expectation, record the backend-specific contract and keep the failing case visible until
  the discrepancy is explained.
