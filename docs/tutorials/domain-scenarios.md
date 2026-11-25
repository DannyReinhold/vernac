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

The current execution profile exercises domain objects directly. See the
[0.6.0 development plan](../development/release-0.6.0.md) for application operation,
rendered MVC and PostgreSQL coverage.
