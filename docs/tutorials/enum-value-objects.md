# Closed Domain Values with Enums

This tutorial uses the revised enum compiler. Start with a Vernac Maven project
and the matching IntelliJ plugin. A released older compiler may still implement
the previous enum API.

## Model the allowed states

Create `src/main/vernac/example/tasks/status.vernac`:

```vernac
namespace example.tasks;

value Status = PENDING | IN_PROGRESS | COMPLETED behavior {
    public boolean isComplete() {
        return self == Status.COMPLETED;
    }
};
```

This generates `example.tasks.domain.Status`, a Java enum. The constants are the
complete set of values. There are no constructors to call and no `of` factory.

From Java:

```java
import example.tasks.domain.Status;

Status status = Status.PENDING;
boolean complete = status.isComplete(); // false
String name = status.name();            // "PENDING"
Status parsed = Status.valueOf("COMPLETED");
boolean same = parsed == Status.COMPLETED; // true
```

Try `Status.valueOf("completed")`: standard Java enum parsing is case-sensitive
and rejects this input. Vernac does not trim or normalize external input implicitly.
Use explicit application-boundary mapping when external data needs other rules.

## Use an enum in another value object

Create `src/main/vernac/example/tasks/task-state.vernac`:

```vernac
namespace example.tasks;

value TaskState(Status, Status? previousStatus);
```

No import is necessary inside the same namespace. In Java:

```java
import example.tasks.domain.Status;
import example.tasks.domain.TaskState;

TaskState initial = TaskState.of(Status.PENDING);
Status current = initial.status();
boolean hasPrevious = initial.previousStatus().isPresent(); // false

TaskState changed = TaskState.of(Status.IN_PROGRESS, Status.PENDING);
String previous = changed.previousStatus().map(Status::name).orElse("none");
```

The enum's required/optional field behavior is exactly the ordinary VO contract.
Try `TaskState.of(null)`: the VO rejects a missing required status.

Neither this enum nor TaskState defines permitted transitions. A future Task
aggregate should decide whether a transition is valid in its business context.

## Explore names and compiler diagnostics

Add these declarations to a file in the same namespace:

```vernac
value MeinTyp(int);
id MeineId;
value Unusual = MeinTyp | MeineId | String | name | values;
```

These names are legal. `Unusual.MeinTyp` is an enum value, not the MeinTyp type.
Constants do not pollute the namespace's type imports. UPPER_SNAKE_CASE is the
recommended spelling, but is not mandatory.

Try adding a duplicate `name` constant: Vernac reports it. Restore the declaration,
then add `public String name() { return "custom"; }` in its method block: this
conflicts with the final Java enum method. The constant `name` itself remains legal.

A custom `public String toString() { return "State: " + self.name(); }` is legal.
`name()` still returns the declared name while `toString()` uses your presentation.

## Questions

**Why does String.valueOf(...) fail in a method on Unusual?**

The constant String can obscure the class in expression context. Use
`java.lang.String.valueOf(...)` in this example, or choose an uppercase constant
name. Vernac field and method type positions still resolve String as the built-in.

**Can two enums both have PENDING?**

Yes. Each constant belongs to its own type. `Status.PENDING` and another enum's
PENDING are different values, not interchangeable states.

**Where is dbValue()?**

It is not generated. A database or foreign API may use different codes for the
same domain value. Those representations belong to mappings. Automatic JDBC enum
mapping currently reports an explicit unsupported-mapping diagnostic while that
contract is being designed. Do not persist ordinal() as a stable identifier.

**Can I add fields or constructor arguments?**

Not in this enum model. Use methods for derived behavior; use an ordinary VO or a
richer domain model if each value needs additional data.

**Does the plugin understand this?**

It highlights declared constants as enum members, completes and navigates enum
type references across files/namespaces, and reports declaration/API conflicts.
Completion and navigation of constant references inside arbitrary embedded Java
bodies are not yet implemented. Java-body compilation remains javac's job.

[Enum contract](../contracts/enum-value-objects.md) · [All tutorials](index.md)
