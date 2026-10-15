# One operation, one usecase

Start with a namespace directory matching `namespace tasks;` below src/main/vernac.
This self-contained example requires Spring context and transaction support in the
Java application, **including a configured transaction manager** (as provided by
the [persistence demo](database-persistence.md)). Generation itself can be inspected
without starting Spring. Save it as `src/main/vernac/tasks/describe.vernac`:

```vernac
namespace tasks;
id TaskId;
value Title(String);

usecase DescribeTask(TaskId, Title? title)
    returns (TaskId, Title? title)
validates {
    require(self.title().isEmpty() || !self.title().orElseThrow().string().isBlank(),
            "A supplied title must not be blank");
}
behavior {
    execute {
        return describe(taskId, title);
    }
    private Result describe(TaskId id, Title? title) {
        return Result.of(id, title);
    }
}
```

Compile using your normal Maven build. Inject tasks.usecase.DescribeTask into the
application's caller. Invoke execute(id, null) for no title. The result's title()
returns Optional.empty(); toString describes both components. The public execute
argument is nullable, and `title` inside behavior/private helpers is nullable too. A blank supplied title
fails before execute's implementation body runs.

Try replacing the two result fields with `returns Title?`, changing the body to
`return Optional.ofNullable(title);` and removing the private helper. The public return type becomes
Optional<Title>; returning null is a contract failure.

To access persistence add `uses TaskRepository tasks` and invoke self.tasks().byId(...)
and self.tasks().save(...) in execute. The repository and aggregate must already be declared.
Dependencies arrive through constructor injection. A usecase called through an injected
Spring bean establishes/joins a REQUIRED transaction.

Restore the original tuple result and helper before the next step. To move Java
out of the model, replace execute's block with:

```vernac
execute implemented by custom.DescribeTaskImplementation;
```

For the original result contract that class supplies:

```java
public static DescribeTask.Result execute(tasks.usecase.access.DescribeTaskAccess self, TaskId taskId, @Nullable Title title) {
    return DescribeTask.Result.of(taskId, title);
}
```

Place the class in package custom and import tasks.domain.TaskId, tasks.domain.Title,
tasks.usecase.DescribeTask and org.jspecify.annotations.Nullable. It does not need @Component.
The first parameter is the generated Access self; declared uses dependencies are
available through its getters. Use your actual namespace for DescribeTaskAccess.
The generated validation and transaction wrapper remain in force.

Read the [complete usecase contract](../contracts/usecases.md) for optional results,
private helpers, naming, imports and transaction limitations.
