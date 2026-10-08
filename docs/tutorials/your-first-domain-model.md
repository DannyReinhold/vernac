# Your First Domain Model

In this tutorial, you will build a small task model and use it from Java.
Then you will change the model, explore validation, and add a business method.

You do not need a database, Docker, or a running Spring application.

## Before You Begin

You need:

- A Java development environment compatible with your project's configuration.
- A Maven project with the Vernac Maven plugin configured.
- The Vernac runtime available as a project dependency.
- JUnit Jupiter and AssertJ if you want to run the tests shown below.

The `vernac-example` module in the Vernac repository is a suitable place
to try these exercises.

The Vernac IntelliJ plugin is optional, but provides highlighting,
completion, diagnostics, and file templates.

> The IntelliJ plugin supports editing Vernac files. Java source generation
> runs through the Vernac Maven plugin.

## 1. Create the Model

Create `tasks.vernac` in your project's configured Vernac source directory,
normally `src/main/vernac`.

With the IntelliJ plugin installed, you can use:

**New → Vernac Getting Started**

Enter `tasks` as the file name and `org.example.tasks` as the base package.

Alternatively, create the file manually:

```vernac
package org.example.tasks;

id TaskId;

value Title(String value) validates {
    require(!self.value().isBlank(), "Task title must not be blank");
};

value Status = PENDING | IN_PROGRESS | COMPLETED | CANCELLED;

aggregate Task[TaskId](
    Title title,
    mut Status
) {
    public void complete() {
        status(Status.COMPLETED);
    }
};
```

Choose a different base package if your project already contains this example.

### What Does This Define?

| Definition   | Purpose                                                      |
|--------------|--------------------------------------------------------------|
| `TaskId`     | A dedicated identity type for tasks                          |
| `Title`      | An immutable value object that rejects blank titles          |
| `Status`     | An enum describing possible task states                      |
| `Task`       | An aggregate with an identity, a title, and a mutable status |
| `complete()` | A business method that completes the task                    |

The declaration `mut Status` omits the field name. Vernac derives
`status` from the type name.

The title is not marked `mut`, so the generated API does not provide
a title mutator.

## 2. Generate the Java Classes

Run the Maven build from the root of the Maven project:

```shell
mvn compile
```

In the Vernac repository, run this from the repository root to build the
example module and its required modules:

```shell
mvn -pl vernac-example -am compile
```

After a successful build, refresh the Maven project in IntelliJ if the
generated classes are not yet recognized.

The model's generated domain types belong to:

```java
org.example.tasks.domain
```

The `.domain` suffix is added to the base package from your Vernac file.

You can inspect the generated Java sources to see how the model is
implemented. Make changes in the `.vernac` source, then regenerate;
manual changes to generated Java files will be lost.

## 3. Use the Model from Java

Create this test under `src/test/java/org/example/tasks/TaskTutorialTest.java`:

```java
package org.example.tasks;

import org.junit.jupiter.api.Test;
import org.example.tasks.domain.Status;
import org.example.tasks.domain.Task;
import org.example.tasks.domain.Title;

import static org.assertj.core.api.Assertions.assertThat;

class TaskTutorialTest {

    @Test
    void completesATask() {
        Task task = Task.create(
                Title.of("Try Vernac"),
                Status.PENDING
        );

        assertThat(task.id()).isNotNull();
        assertThat(task.status()).isEqualTo(Status.PENDING);

        task.complete();

        assertThat(task.status()).isEqualTo(Status.COMPLETED);
        assertThat(task.title().value()).isEqualTo("Try Vernac");
    }
}
```

Run the test in IntelliJ or use:

```shell
mvn test
```

In the Vernac repository:

```shell
mvn -pl vernac-example -am test
```

Notice that you did not pass an ID to `Task.create(...)`.
The factory creates a new `TaskId` automatically.

The accessor `task.title()` returns a `Title`, while
`task.title().value()` returns its underlying string.

### Try It

Change `"Try Vernac"` to another title and run the test again.
Update the expected title in the assertion as well.

You are using ordinary Java objects. No Spring context or repository
is needed for this test.

## 4. Explore Value Object Validation

What should happen when a task title contains only spaces?

Add these imports to your test:

```java
import org.vernac.runtime.DomainValidationException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

Then add:

```java

@Test
void rejectsABlankTitle() {
    assertThatThrownBy(() -> Title.of("   "))
            .isInstanceOf(DomainValidationException.class)
            .hasMessageContaining("Task title must not be blank");
}
```

Run the test.

The failure occurs while creating the `Title`. No invalid title is
returned to the caller, and no task is created from it.

### Try It

Change the title rule so that titles must contain at least three
non-whitespace characters:

```vernac
value Title(String value) validates {
    require(
        self.value().strip().length() >= 3,
        "Task title must contain at least 3 characters"
    );
};
```

Regenerate the Java classes and update the expected error message
in your test.

Experiment with:

- `"Hi"` — rejected
- `"   "` — rejected
- `"Run"` — accepted
- `"  Run  "` — accepted

The validation checks a stripped version of the string, but does not
modify the stored value. `"  Run  "` still contains its surrounding spaces
when read through `value()`.

## 5. Add a Business Method

A completed task may need to be reopened.

Add `reopen()` beside `complete()` in the aggregate body:

```vernac
aggregate Task[TaskId](
    Title title,
    mut Status
) {
    public void complete() {
        status(Status.COMPLETED);
    }

    public void reopen() {
        status(Status.PENDING);
    }
};
```

Regenerate the Java classes, then add this test:

```java

@Test
void reopensACompletedTask() {
    Task task = Task.create(
            Title.of("Try Vernac"),
            Status.PENDING
    );

    task.complete();
    task.reopen();

    assertThat(task.status()).isEqualTo(Status.PENDING);
}
```

The methods are embedded Java code. They use the generated
`status(...)` mutator to change the aggregate.

### Try It

Add a method named `start()` that changes the status to `IN_PROGRESS`.

Write a test that:

1. Creates a pending task.
2. Calls `start()`.
3. Checks that its status is `IN_PROGRESS`.

## 6. Discover a Modeling Decision

Try this sequence:

```java
Task task = Task.create(
        Title.of("Try Vernac"),
        Status.CANCELLED
);

task.complete();
```

What status do you expect?

With the current model, the result is `COMPLETED`.

The enum defines the available states, but it does not define which
transitions are allowed. Our `complete()` method currently permits
completion from every state.

Also, because `status` is marked `mut`, callers can use its generated
mutator directly:

```java
task.status(Status.COMPLETED);
```

This example demonstrates typed state and business methods.
It is not yet a model that enforces a complete task workflow.

Before adding rules, decide what the domain should allow:

- Can a cancelled task be reopened?
- Can a task be completed before it has been started?
- Should completing an already completed task do nothing or report an error?

Those are domain decisions. Writing down the expected behavior and
adding tests is a useful next step.

## A Note on Aggregate Validation

The title validation in this tutorial runs during value-object construction.

Vernac also supports aggregate and entity invariants. Currently, generated
mutators apply a change before checking those invariants. A validation
exception does not automatically restore the object's previous state.

After a failed mutation, abort the operation and discard the affected
aggregate instance.

See [Validation failures and object state](../language-reference.md#validation-failures-and-object-state)
for details.

## What You Have Built

You now have:

- A typed task identity.
- An immutable, validated title.
- Explicit task states.
- An aggregate with business methods.
- Java tests exercising generated code.

The same domain model can later be used from application services
and connected to persistence. Neither is required to explore its behavior.

[Back to tutorials](index.md) · [Language reference](../language-reference.md)