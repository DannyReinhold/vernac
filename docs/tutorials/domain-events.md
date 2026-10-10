# Publish a domain event

This walkthrough starts with synchronous events, then introduces recording and queued
delivery. Deferred worker execution is the next stage; see the
[delivery plan](../development/event-delivery.md).

In a Spring Boot application with Vernac generation enabled, create
`src/main/vernac/example/tasks/events.vernac`:

```vernac
namespace example.tasks;
id TaskId;
value Title(String);
event TaskRenamed(TaskId, Title);

usecase ReportRename(TaskId, Title)
behavior {
    execute { self.emit(TaskRenamed.create(taskId, title)); }
}

synchronous listener PrintRename on TaskRenamed
behavior {
    execute {
        System.out.println("Renamed " + event.taskId().asString()
                + " to " + event.title().string());
    }
}
```

This small example reports a fact; it does not persist or rename an aggregate.
In a real operation, perform the domain change before reporting its completion.
Place the application's component scan above `example.tasks`, and configure a Spring
transaction manager as in the persistence demo. Inject the generated
`example.tasks.usecase.ReportRename` bean, then call:

```java
reportRename.execute(TaskId.create(), Title.of("Review delivery plan"));
```

Call through the injected bean, not `new ReportRename(...)`: Spring supplies the
transaction interceptor. The listener runs before execute returns, in the same
transaction. Printing demonstrates execution only; console output cannot be rolled back.

Try a second synchronous listener with a different name. Both receive the same event
occurrence. Do not depend on which listener runs first. Then make one listener throw:
the usecase fails and its database transaction rolls back. Catching the listener exception
inside application code does not make the transaction committable again.

## External implementation

Replace the listener body with:

```vernac
synchronous listener PrintRename on TaskRenamed
behavior {
    execute implemented by example.tasks.custom.PrintRenameImplementation;
}
```

Supply a Java source file (outside generated sources):

```java
package example.tasks.custom;

import example.tasks.domain.TaskRenamed;
import example.tasks.adapter.inbound.event.access.PrintRenameAccess;

public final class PrintRenameImplementation {
    public static void execute(PrintRenameAccess self, TaskRenamed event) {
        System.out.println(event);
    }
}
```

There is no Spring annotation on this implementation. The generated listener bean
and companion delegate to it. Declared `uses` dependencies are available through
self getters, and `self.emit(...)` can report another event. Validators never receive
that capability.

See the [event contract](../contracts/events.md) for factories, identity and nullability.
Next: durable relational event storage and per-listener delivery jobs, with a separate
PostgreSQL walkthrough. Do not simulate durability by switching this example to an
asynchronous thread or by catching and ignoring listener failures.

## Next stage: recording and queued delivery

Replace `event Changed(...)` with `recorded event Changed(...)` to retain occurrences
without a deferred consumer. Alternatively, declare a `deferred listener` to request
storage and a delivery job for that listener. Keep synchronous listeners where their
work belongs to the caller's transaction.

Run the normal Vernac migration goal, review the candidate, then apply it through
Flyway before publishing. Event tables use the same schema history as aggregates.
Never edit an already applied migration to add these tables. Review and test every
candidate yourself; generation is not an approval or a guarantee of data compatibility.

After committing a publication, inspect the event table and `"vernac"."@delivery"`.
Two deferred listeners produce two jobs pointing to one payload. A rolled-back
publication produces neither. `recorded` without deferred listeners stores only the
payload. **In this implementation stage jobs remain PENDING: no worker runs yet.**

The generated-code and PostgreSQL demonstration tests are in
`vernac-maven-plugin/src/test/resources/persistence/events` and
`EventStoragePostgresTest`. From the reactor root, with the existing PostgreSQL test
environment variables configured, run this in IntelliJ's Maven command window:

```text
-pl vernac-maven-plugin -am test -Dtest=EventStoragePostgresTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expect two tests and zero skipped tests when PostgreSQL is configured. The compilation
test always runs; the database test is skipped when VERNAC_PG_TEST_URL is absent.
The configured PostgreSQL account must be able to create and drop disposable databases.
