# Add a use case that changes stored state

The aggregate already owns the completion rule. In this 0.5.4 core example, the new use case
loads the task through the declared repository, calls the aggregate and saves it. The repository
alias `tasks` gives the behavior its `self.tasks()` accessor.

`src/main/vernac/guide/commands/tasks.vernac`

```diff
 namespace guide.commands;
 id TaskId;
 value TaskState = OPEN | DONE;
 aggregate Task[TaskId](mut TaskState) behavior {
     modify void complete() { self.taskState(TaskState.DONE); }
 }
 repository TaskRepository for Task {}
+usecase CompleteTask(TaskId) uses TaskRepository tasks behavior {
+    execute {
+        var task = self.tasks().byId(taskId);
+        task.complete();
+        self.tasks().save(task);
+    }
+}
```

Run `./mvnw compile` with the matching Vernac plugin and JDBC runtime. Application code injects
the generated `guide.commands.usecase.CompleteTask` Spring bean and calls `execute(taskId)`.
To verify committed state, invoke that bean for an open task and reload the same ID in a fresh
transaction. Its state should be `DONE`. Carry over the application's access requirements when
adding a protected operation. [Use-case contract](../contracts/usecases.md)
