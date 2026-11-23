---
title: Render web views from Vernac
---

# Render web views from Vernac

A page view takes a typed model from an inbound MVC adapter. Use a read usecase's
value, collection or result directly, or declare a local schema and map the result
before rendering. Views can display value fields and pass data to typed components.
Aggregate and entity types, repository calls and behavior methods are unavailable
in view bindings.

```vernac
component RoomName(String name) {
    text name;
}

view Rooms(RoomModel room) {
    page "Rooms" layout workspace theme "/house.css" {
        heading "Rooms";
        include RoomName(room.name);
        form submit HouseWeb.add {
            textField name label "Room name";
            button "Add room" variant primary;
        }
    }
}

adapter HouseWeb inbound mvc uses ReadRoom, AddRoom {
    base "/rooms";
    access authenticated;
    error view "problem";

    schema RoomModel(String name);
    mapping toPage: RoomModel <- RoomSummary {
        name <- source.name;
    }

    index {
        GET "/";
        execute readRoom {}
        render Rooms model room as RoomModel using toPage;
    }

    add {
        POST "/";
        form String name;
        execute addRoom { roomName <- form.name; }
        redirect index message "Room added.";
        errors {
            when validation then redisplay index status 422
                "INVALID_ROOM_NAME" "Enter a valid room name.";
        }
    }
}
```

Here `ReadRoom` returns a `RoomSummary` with a String field `name`, and
`AddRoom` takes a `RoomName` value. `render Rooms model room as RoomModel using
toPage` applies the exact declared mapping. A direct render uses
`render Rooms model room` and a view parameter with the same type and name as
the binding. Page and component declarations resolve by name in their namespace.

- **Lists.** Use `list tasks as task { include TaskRow(task); } empty { text "No tasks."; }`.
  The source can be a value collection or a paginated value collection.
- **Forms.** A form resolves to a named MVC POST endpoint. Bind route parameters with
  `path id <- task.taskId.value;` inside the form.
  A field uses the endpoint's binding name. The generated input uses its declared
  external name. Sensitive fields render as password inputs and are never
  repopulated. Spring MVC and Thymeleaf integrate the form with Spring Security's
  CSRF protection when enabled. The endpoint remains the authority for access,
  validation and writes.
- **Presentation.** `layout workspace` adds a class to the main container.
  `theme "/house.css"` links an application stylesheet from
  `src/main/resources/static/house.css`. Shared CSS can style layout classes and
  button variants such as `primary`.
- **Rendering.** The Maven plugin registers generated Thymeleaf templates as
  resources. Text bindings are escaped on render. Existing quoted template
  references continue to work for custom views and error pages.

The Spring Boot starter renders its board at `/` from Vernac. It shows a page of
task cards with `pagination tasks at "/";`. Enum conditions select which row
action to display. The adapter uses `error view TaskProblem;` to render a
model-free Vernac page on errors. Its web test covers the rendered page, CSRF, validation redisplay,
redirect and escaped output.
