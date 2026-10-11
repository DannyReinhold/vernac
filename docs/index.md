<p><img src="assets/brand/vernac-logo-light.svg" alt="Vernac" width="420" style="max-width: 100%; height: auto;"></p>

# Give your application an executable architecture

**Vernac turns a domain model into a working Java architecture.**

A delivery tour is more than a row in a table. It has identity, valid states,
contained stops, operations that change it, and consequences that other systems
care about. Vernac lets you express these responsibilities together, then generates
the Java and Spring infrastructure that connects them.

You write the business decisions. Vernac supplies consistent value APIs, aggregate
boundaries, transactional usecases, JDBC persistence, event delivery and outbound
integration. Business implementations remain ordinary Java, inline or external.

## Follow one operation through the system

```vernac
namespace example.dispatch;

id TourId;
value Title(String) validates {
    require(!self.string().isBlank(), "Title must not be blank");
};
value TourStatus = PLANNED | COMPLETED;
event TourCompleted(TourId);

aggregate Tour[TourId](Title, mut TourStatus) behavior {
    modify void complete() {
        self.tourStatus(TourStatus.COMPLETED);
    }
} list Tours;

repository TourRepository for Tour {
    find Tours withStatus(TourStatus status)
        where tourStatus = :status order by title;
}

usecase CompleteTour(TourId) uses TourRepository tours behavior {
    execute {
        var tour = self.tours().byId(tourId);
        tour.complete();
        self.tours().save(tour);
        self.emit(TourCompleted.create(tour.id()));
    }
}
```

Follow CompleteTour: load a complete aggregate, invoke domain behavior, save under
optimistic locking, and publish a fact inside the usecase transaction. A listener
chooses synchronous participation or durable deferred handling. An outbound port
can describe the next external action without exposing HTTP DTOs to the domain.

[Follow the architecture step by step →](tutorials/from-model-to-application.md)

This compact example leaves transition policy to the application. Completing a tour
is not automatically idempotent merely because assigning the same status is a no-op.

## Choose your next step

| Your goal | Start here |
| --- | --- |
| Get a model compiling in minutes | [Your first domain model](tutorials/your-first-domain-model.md) |
| Use the editor and project wizard | [IntelliJ tutorial](tutorials/intellij-plugin.md) |
| See persistence, events and external calls work together | [Delivery walkthrough](tutorials/database-persistence.md) |
| Find exact syntax | [Language reference](language-reference.md) |
| Understand guarantees and limits | [Specification and contracts](specification/index.md) |
| Solve a common problem | [FAQ and troubleshooting](faq/index.md) |

## What the language connects

- **Domain:** typed IDs, immutable values, enums, named lists/sets, entities and aggregates.
- **Behavior:** Java implementations behind generated access views, explicit validation,
  private helpers and external delegation.
- **Application:** named domain services and single-operation transactional usecases.
- **Persistence:** PostgreSQL mapping, complete graphs, declarative queries, optimistic
  locking and reviewable Flyway migration candidates; no ORM or lazy loading.
- **Events:** immutable facts, synchronous listeners and relationally stored deliveries
  with retries and worker coordination.
- **Integration:** outbound ports, manual or REST adapters, DTO records, PATCH intent,
  explicit mappings and business/technical error translation.
- **Tooling:** Maven generation and IntelliJ support for Vernac and embedded Java.

## Version and prerequisites

These pages describe **0.2.0-SNAPSHOT**, not the earlier published language.
Install matching snapshot artifacts locally before following this branch's tutorials.
Use JDK 25 to build the repository and examples; application output targets Java 21.
The persistence examples use PostgreSQL 17. Choose the runtime modules your application
needs; using domain models does not require JDBC.

The [tutorial path](tutorials/index.md) starts without a database and gradually adds
architecture. The [example guide](https://github.com/DannyReinhold/vernac/tree/feature/redefining_the_language/examples)
contains build commands and separates database-free tests from PostgreSQL checks.

Vernac is under active development. Migration review, external-call idempotency and
business invariants remain application responsibilities. See the [FAQ](faq/index.md)
for practical boundaries and the [release plan](development/release-0.2.0.md) for planned work.

[GitHub](https://github.com/DannyReinhold/vernac) · [Contributing](development/index.md) ·
[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)

## Explore a complete application

**Vernac Dispatch** puts the delivery story on screen. Balance tour capacity, release
a plan and follow a durable event through a simulated carrier outage and retry.
The browser is a Java web adapter; the business model and application operations are
Vernac. [Start the Dispatch walkthrough](tutorials/dispatch.md).
