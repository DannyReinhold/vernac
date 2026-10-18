---
title: DDD for a small project
---
# DDD for a small project, without a large framework of your own

You do not need a large team or a distributed system to care about clear names,
valid data and understandable changes. A task board, booking tool or small SaaS
can benefit from those properties on its first day.

The usual objection is practical: a handful of business operations can turn into
many Java files for values, factories, access methods, repositories and transaction
wrappers. **Vernac makes this structure affordable by generating the repeated
code.** You still make the design decisions and write the business behavior.
Less boilerplate does not mean less responsibility, but it lets a small project
start cleanly without maintaining its own architectural scaffolding.

New to the terms? Read [What is DDD?](what-is-ddd.md) first. This chapter is a design
walkthrough, not a second project to install. For a running UI and database, use
the [Spring Boot starter](spring-boot-starter.md).

## Start with one useful operation

Imagine a personal task board. A task needs a nonblank title and can be completed.
The application should remember it after a restart. We need one aggregate, a
repository and two usecases. We do not need a separate domain service, external
port, event stream or reusable module yet.

Here is a complete alternative model, independent of the generated starter model.
It uses the same vocabulary introduced in the preceding chapter:

```vernac
namespace example.tasks;

id TaskId;
value Title(String) validates {
    require(!self.string().isBlank(), "A task needs a title");
};
value Status = PENDING | COMPLETED;

aggregate Task[TaskId](Title, mut Status) behavior {
    modify void complete() {
        self.status(Status.COMPLETED);
    }
} list Tasks;

repository TaskRepository for Task {
    find Tasks withStatus(Status status) where status = :status order by createdAt;
}

usecase AddTask(Title) returns TaskId uses TaskRepository behavior {
    execute {
        var task = Task.create(title, Status.PENDING);
        self.taskRepository().save(task);
        return task.id();
    }
}

usecase CompleteTask(TaskId) uses TaskRepository behavior {
    execute {
        var task = self.taskRepository().byId(taskId);
        task.complete();
        self.taskRepository().save(task);
    }
}
```

This expresses architectural decisions that otherwise require several hand-maintained
Java types. `Title` owns its rule; Task owns its identity and behavior; each usecase
owns one transaction. The repository contract belongs to the domain, while generated
JDBC code belongs outside it. `byId` throws if the task does not exist.

The example's repeated completion is deliberately allowed. The query orders by
creation time but does not promise an order among identical timestamps. If that
matters, declare an additional tie-breaker; use [pagination](pagination.md) when
results may grow. Neither the query nor the model silently adds a business policy.

## Keep the application small

| Need today | Smallest useful choice | Add only when there is a reason |
| --- | --- | --- |
| Valid task titles | One validated value | More fields or richer behavior when the domain needs them |
| Identity and state changes | One Task aggregate | Child entities for independently identifiable parts inside it |
| A complete business action | A usecase | A domain service for a rule that fits no individual domain object |
| Remember tasks | One declared repository | Search and pagination driven by actual screens |
| A browser interface | An inbound MVC adapter and templates | A JSON API for clients that need one |
| Talk to another system | Nothing until needed | A port, adapter and ACL for that integration |
| React after an operation | A direct call when it is the operation's responsibility | An event/listener when an explicit reaction and its timing make sense |
| Reuse across products | Local definitions | A versioned module when the shared contract is understood |

Generated files are ordinary Java and remain inspectable. They are not the place
to add custom behavior: edit the Vernac model or its handwritten implementation.
No ORM is required. No service-per-entity or repository-per-value convention is
imposed.

## Get a real application before adding architecture

The [Spring Boot starter](spring-boot-starter.md) creates a small task application
with a Thymeleaf interface, declarative MVC, usecases, PostgreSQL, Flyway and tests.
With Docker running, open `TasksApplication` and click Run. There is no need to
choose a local profile first.

Use that project if you want to work on a product immediately. Choose the minimal
domain project if you want to learn with a database-free Java test. These are two
entry points, not two different architectural philosophies.

The starter does not supply user management, authorization, subscriptions or a
production operating model. Add those deliberately if your product needs them;
architecture generation does not make private data safe automatically.

## Grow by changing the model, not by predicting everything

Suppose tasks later need a due date. Start by defining its meaning and absence
rules. Add it to the model, update the affected callers and tests, then generate
and review the migration candidate. Keep user-interface request formats outside
the domain. If a calendar integration arrives, put the translation at a port/adapter
boundary rather than making Task depend on a vendor's request object.

These are small, local decisions. You do not have to start with microservices or
split a single product into Maven modules to leave room for growth.

## Know when a plain Java solution is enough

If an application only copies a few records without meaningful rules, ordinary
Java and SQL may be entirely sufficient. Vernac is most useful when the repeated
structure is real and explicit domain decisions will improve the application.
It does not remove the need to learn its conventions, understand transactions,
review database migrations or test business rules.

**Next:** create the [working web application](spring-boot-starter.md), or take the
[database-free first step](your-first-domain-model.md). Later, follow
[one operation through the architecture](from-model-to-application.md) and consult
the [documentation map](../documentation-map.md) only for the topics you need.
