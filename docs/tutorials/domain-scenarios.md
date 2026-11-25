---
title: Test a domain behavior in Vernac
---

# Test a domain behavior in Vernac

The 0.6.0 development branch runs domain scenarios alongside Java tests. The Basic and
Spring Boot wizard projects include a scenario under `src/test/vernac`. In a generated
project, run `./mvnw test` or `mvnw.cmd test` on Windows.

The Spring Boot scenario creates an open task, saves its generated ID and calls the same
domain behaviors used by the application. Each scenario gets its own fixtures.

```vernac
namespace org.example.tasks;

suite TaskLifecycle {
    scenario followsStatusTransitions {
        given task = Task(TaskTitle("Try Vernac"), TaskStatus.OPEN);
        given originalId = task.id;
        expect originalId is present;
        expect task.taskStatus equals TaskStatus.OPEN;
        when task.complete();
        expect task.id equals originalId;
        expect task.taskStatus equals TaskStatus.DONE;
        when task.reopen();
        expect task.taskStatus equals TaskStatus.OPEN;
    }
}
```

- `given` binds a fixture or captures a value for later assertions. Aggregate and entity constructors
  call the generated `create` factory. Value constructors call `of`.
- `when` invokes a public behavior on a previously bound fixture. Use `when result = task.readStatus();` to bind the result of a behavior that returns a value.
- `expect` compares two values of the same type or checks presence. A dotted fixture path reads
  its fields. Capture a field before a mutation when you need to compare its previous value.
- `expect TaskTitle(" ") is invalid;` requires the generated constructor to throw a domain validation exception.
- A scenario can use types in its namespace and import types from other namespaces. It supports
  enum members, strings, integers and booleans as inputs. Test declarations are visible only
  during test compilation.

Change the expectation after `complete()` to `TaskStatus.OPEN` and rerun Maven. JUnit reports both enum
values and the assertion's `.vernac` source location. Change it to a string and the Vernac compiler
instead reports the type mismatch before JUnit starts. Restore the expected value afterwards.

For an existing project, add a separate execution of the `vernac-maven-plugin` goal `test-compile`
and a test-scoped `org.junit.jupiter:junit-jupiter` dependency. The goal runs in
`generate-test-sources`. Surefire writes the reports to `target/surefire-reports`.
The generated bridge has one JUnit method per scenario and uses the namespace, suite and
scenario name as its display ID.

## Exercise application entry points and rendered pages

A suite can name a test-scoped Java configuration with `using`. The runner opens
and closes a Spring context for each case. A configuration can bind a `TenantId`
bean to the case scope and supply the application's actor resolver and permission
evaluator. `when id = invoke CreateTask(TaskTitle("Draft"));` calls the generated
usecase through Spring. Configure transaction management for guarded operations and
event emission. `when invoke DeleteDocument(id) is ACCESS_DENIED;` checks the exact
authorization outcome. `is invalid` checks a domain validation failure.

```vernac
namespace org.example.tasks;

suite TaskActions profile mvc using org.example.tasks.TaskScenarioFixtures {
    scenario displaysReopenAfterCompletion {
        when id = invoke CreateTask(TaskTitle("Draft"));
        when request TaskWeb.complete(id = id) csrf;
        when request TaskWeb.board();
        expect status 200;
        expect action TaskWeb.reopen(id = id) is present;
        expect action TaskWeb.complete(id = id) is absent;
    }
}
```

The `mvc` profile uses a test-scoped Spring `MockMvc` bean. A request resolves the
declared endpoint method, route and named inputs. Add `csrf` to submit Spring
Security's test token. `expect body contains` matches raw HTML source. Action
assertions parse the rendered HTML with test-scoped Jsoup and match the form method
and expanded path, including the row ID.

`expect emitted DocumentDeleted with documentId equals id count 1;` counts
matching payloads received by a synchronous test handler. Omit the field condition
to count all emissions of the declared event type. The handler runs inside the
transaction. Read persisted state through a later usecase to verify its committed
effects. `expect calls to DocumentStore.delete count 0;` observes calls to the
Spring port bean while delegating to the configured test implementation.

## Select a profile

The default run includes `domain` and `mvc`. Pass `-Dvernac.testProfile=domain`,
`mvc` or `postgres` to select one scenario profile. Java tests follow Maven's
usual selection. `-Dtest=VernacScenariosTest` selects the generated bridge.
A selected profile with zero matching cases fails generation. A setup failure
fails the selected case and reports its underlying cause.

The Document Workspace example includes a disposable PostgreSQL fixture for a
committed deletion and event, a refused command and rollback after an injected
listener failure. The [release plan](../development/release-0.6.0.md) lists the
acceptance matrix.


## Run scenarios by identity

Pass a comma-separated list of fully qualified scenario IDs to the Vernac Maven plugin. A scenario ID
consists of its namespace, suite name and scenario name. This command selects a single case:

```sh
mvn -pl examples/document-workspace-scenarios -am -Dvernac.testCases=example.documents.ReaderView.hiddenActionAndDirectPostAreDenied -Dtest=VernacScenariosTest -Dsurefire.failIfNoSpecifiedTests=false test
```

The compiler validates the scenario sources and emits JUnit methods for the selected cases. The selection
is restricted to the active Vernac test profiles. An unknown ID or an ID outside those profiles fails
test generation.
