# One operation, one usecase

Start with a namespace directory matching `namespace tasks;` below src/main/vernac.
This self-contained example requires Spring context and transaction support in the
Java application (as provided by the Vernac Spring/JDBC setup):

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
        return Result.of(id, title.orElse(null));
    }
}
```

Compile using your normal Maven build. Inject tasks.usecase.DescribeTask into the
application's caller. Invoke execute(id, null) for no title. The result's title()
returns Optional.empty(); toString describes both components. A blank supplied title
fails before execute's implementation body runs.

Try replacing the two result fields with `returns Title?`, changing the body to
`return title;` and removing the private helper. The public return type becomes
Optional<Title>; returning null is a contract failure.

To access persistence add `uses TaskRepository tasks` and invoke tasks.byId(...)
and tasks.save(...) in execute. The repository and aggregate must already be declared.
Dependencies arrive through constructor injection. A usecase called through an injected
Spring bean establishes/joins a REQUIRED transaction.

To move Java out of the model, replace execute's block with:

```vernac
execute implemented by custom.DescribeTaskImplementation;
```

For the original result contract that class supplies:

```java
public static DescribeTask.Result execute(TaskId taskId, Optional<Title> title) {
    return DescribeTask.Result.of(taskId, title.orElse(null));
}
```

Place the class in package custom and import tasks.domain.TaskId, tasks.domain.Title,
tasks.usecase.DescribeTask and java.util.Optional. It does not need @Component.
Any uses dependencies follow the inputs in the static method signature.
The generated validation and transaction wrapper remain in force.

Read the [complete usecase contract](../contracts/usecases.md) for optional results,
private helpers, naming, imports and transaction limitations.
