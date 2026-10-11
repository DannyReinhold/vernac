# Your first domain model

Start with `templates/vernac-basic`, or create a project using the Vernac IntelliJ
project wizard. The wizard sets Maven coordinates, the namespace and source paths
for you. Use JDK 25 for the build; the application targets Java 21.

For a snapshot version, install the matching Vernac artifacts locally first.
Released compiler and runtime artifacts are downloaded from Maven Central.

## Build and explore

From the generated project directory, run `./mvnw clean test` on Linux/macOS or
`.\mvnw.cmd clean test` in Windows PowerShell. In an IntelliJ Maven run
configuration, enter `clean test`.

Open `src/main/vernac/org/example/tasks/tasks.vernac` (with the corresponding
namespace path if you chose another namespace):

```vernac
namespace org.example.tasks;

id TaskId;

value Title(String) validates {
    require(!self.string().isBlank(), "Task title must not be blank");
};

value Status = PENDING | IN_PROGRESS | COMPLETED | CANCELLED;

aggregate Task[TaskId](Title title, mut Status) behavior {
    modify void complete() {
        self.status(Status.COMPLETED);
    }
};
```

`Title` validates its value when created. `Task.create(title, status)` creates a
fresh identity. Only the status is mutable, and the behavior accesses it through
`self`. Generated classes live in `org.example.tasks.domain`.

The Java test in `src/test/java/org/example/tasks/TaskTest.java` creates a task,
completes it and checks the result. Read the generated Java under
`target/generated-sources/vernac` as well; edit the Vernac source, never the
generated classes.

## Add your own behavior

Add this method inside the existing `behavior` block:

```java
modify void start() {
    self.status(Status.IN_PROGRESS);
}
```

Extend the Java test to call `task.start()` and assert `Status.IN_PROGRESS`
before completing the task. Rebuild with `clean test`.

Next, try `Title.of(" ")` in a test. Assert that it throws
`org.vernac.runtime.DomainValidationException`. Think about whether completing an
already cancelled task should be allowed: the initial example deliberately has
no transition policy. Add a policy and tests as a further exercise.

No database, Docker container or running Spring application is needed here.
For persistence and migrations, continue with the tutorials linked from the
[tutorial index](index.md).
