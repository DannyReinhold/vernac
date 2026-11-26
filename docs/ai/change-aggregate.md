# Add a state transition to an aggregate

A task can already be completed. Add reopening to the same aggregate and set its existing mutable
field through `self`. Here is the complete model file, with the added line marked `+`.

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

Add this domain scenario. It exercises both transitions and checks the original ID.

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

Run `./mvnw test` in a project with the Vernac test-compile goal and JUnit Jupiter. This domain
scenario runs in memory. [Scenario configuration](../tutorials/domain-scenarios.md)
