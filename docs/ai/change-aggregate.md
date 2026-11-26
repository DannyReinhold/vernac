# Add a state transition to an aggregate

A task can already be completed. Add reopening to the same aggregate and set its existing mutable
field through `self`. Here is the complete model file, with the added line marked `+`.
The behavior uses Vernac 0.5.4 core syntax.

`src/main/vernac/guide/lifecycle/task.vernac`

```diff
 namespace guide.lifecycle;
 id TaskId;
 value TaskState = OPEN | DONE;
 aggregate Task[TaskId](mut TaskState) behavior {
     modify void complete() { self.taskState(TaskState.DONE); }
+    modify void reopen() { self.taskState(TaskState.OPEN); }
 }
```

On a build with Vernac scenarios (0.6 development line onward), add this test. It exercises the
public behavior twice and observes the original ID after both transitions.

`src/test/vernac/guide/lifecycle/reopening.vernac`

```vernac-test
namespace guide.lifecycle;
suite TaskReopening {
    scenario reopensTheSameTask {
        given task = Task(TaskState.OPEN);
        given originalId = task.id;
        when task.complete();
        expect task.taskState equals TaskState.DONE;
        when task.reopen();
        expect task.taskState equals TaskState.OPEN;
        expect task.id equals originalId;
    }
}
```

Run `./mvnw test` in a project with the Vernac test-compile goal and JUnit Jupiter. On 0.5.4 use
the same sequence in a Java unit test against the generated `Task` API. A domain-only test runs
in memory. [Scenario configuration](../tutorials/domain-scenarios.md)
