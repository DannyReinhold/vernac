# Put your domain behind an HTTP API

A web request is not a domain object. An inbound adapter makes that boundary visible:
read transport data, construct validated domain values, call a usecase and map its
answer. Vernac generates the Spring MVC plumbing while the application contract stays
in your usecase.

This complete example needs no database. Save it as
`src/main/vernac/example/tasks/api.vernac`:

```vernac
namespace example.tasks;

id TaskId;
value Title(String) validates {
    require(!self.string().isBlank(), "Title must not be blank");
};
usecase CreateTask(Title) returns TaskId behavior {
    execute { return TaskId.create(); }
}
usecase CompleteTask(TaskId) behavior {
    execute { System.out.println("Completed " + taskId.asString()); }
}

adapter TaskApi inbound http uses CreateTask createTask, CompleteTask completeTask {
    base "/api/tasks";
    create {
        POST "/";
        body CreateTaskRequest;
        execute createTask { title <- body.title; }
        respond 201 CreatedTaskResponse using toResponse;
    }
    complete {
        POST "/{taskId}/complete";
        path UUID taskId;
        execute completeTask { taskId <- path.taskId; }
        respond 204;
    }
    schema CreateTaskRequest(String title);
    schema CreatedTaskResponse(UUID taskId);
    mapping toResponse: CreatedTaskResponse <- TaskId { taskId <- source; }
}
```

The tiny usecases demonstrate transport wiring; they do not store a task. Add the
existing aggregate/repository patterns when building a persistent application. The
[Dispatch showcase](https://github.com/DannyReinhold/vernac/tree/main/examples/dispatch)
uses generated create/release endpoints against its actual persistence usecases.

Use the matching Vernac compiler/runtime versions and Spring Boot's web starter.
Include `vernac-runtime-http`; its JSON dependency supplies the generated DTO codecs.
Your Spring application's component scan must include `example.tasks`.
Build the application with the normal Vernac Maven compile execution and start Spring
Boot. Then POST JSON to `/api/tasks`:

```json
{"title":"Try the generated API"}
```

The response is 201 with `{"taskId":"..."}`. The input String is wrapped through
Title.of, and the returned ID is unwrapped into the UUID DTO field. Neither the
controller nor the mapping adds a transaction: CreateTask already owns it.
POST `/api/tasks/<returned-id>/complete` returns 204.

Try a blank title (422), a malformed UUID (400) and an additional JSON property
(accepted). Missing required JSON fields produce 400. Responses contain a stable
code and safe public message; your internal exception text stays out of the body.

For an operation declared `returns (TaskId, Title)`, map from `CreateTask.Result` and
assign DTO fields from `source.taskId`/`source.title`. This is the usecase's explicit
Result, not another response invented by the adapter. Optional single results require
an explicit absence response such as `on absent respond 404;`.

Read the [HTTP inbound contract](../contracts/inbound-http.md) for wire formats,
query/header declarations, error customization and the deliberately limited scope.
Business orchestration belongs in the usecase; this adapter remains a transport/ACL
boundary. Configure Spring Security normally when exposing a protected application.
