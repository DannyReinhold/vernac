# From a domain model to an application

A tour has just finished. What should the application do?

A setter alone cannot answer that question. You need a valid state transition,
a transaction, persistent state and a way to tell interested parties. This chapter
connects the language features before you explore their individual APIs.

Start with [your first domain model](your-first-domain-model.md). In that Maven
project, create `src/main/vernac/example/dispatch/tour.vernac`:

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

usecase CompleteTour(TourId) uses TourRepository tourRepository behavior {
    execute {
        var tour = self.tourRepository().byId(tourId);
        tour.complete();
        self.tourRepository().save(tour);
        self.emit(TourCompleted.create(tour.id()));
    }
}
```

This is a separate namespace from the Task model. Build to generate its Java API.
For execution against PostgreSQL, use the configured [delivery demo](database-persistence.md):
adding a repository declaration alone does not configure a DataSource or apply migrations.

The architecture view below separates application orchestration from transport
and storage. Each generated adapter connects to a declared contract.

![Application and domain boundaries](../assets/vernac-architecture.svg)

## 1. Make invalid values difficult to create

Title rejects blank text when constructed. TourId prevents passing an unrelated ID
by accident. TourStatus describes the closed set of states. These are domain choices,
not database column declarations. The compiler supplies their Java representation.

Try `Title.of(" ")` in a test. Construction fails with DomainValidationException;
no invalid Title is returned. [Explore values and behavior](behavior.md).

## 2. Give change a business name

Call `tour.complete()`, not a public status setter. Inside the modify implementation,
`self` exposes permitted mutation operations. Java callers see the business operation.
The example allows repeated completion; add a transition policy if your domain requires
otherwise. Invariants are checked on construction and successful modify completion.
A failed modification does not restore old object state.

The larger delivery model contains Stops and Parcels. A Stop shared by several
relationships is one entity instance when the aggregate is loaded. Collections are
explicit domain types; list position and set membership have different meanings.
[Explore collections](collections.md), then [persist the complete graph](database-persistence.md).

## 3. Put the transaction around the operation

CompleteTour is a generated Spring bean with one execute method. Its REQUIRED
transaction contains loading, behavior, saving and event publication. Calling it
through Spring starts or joins a transaction. Calling a manually constructed bean
does not activate transaction interception.

The repository is a domain interface; its JDBC implementation lives in the outbound
adapter package. It requires a transaction and protects writes with optimistic locking.
[Usecases](usecases.md) explain the application boundary. [Domain services](domain-services.md)
show how to share a rule that moves a Stop between two Tours without owning a transaction.

## 4. Separate a fact from its consequences

TourCompleted states what happened. It does not decide how a subscriber reacts.
A synchronous listener participates now; a deferred listener receives a durable job
processed after commit. Publishing is explicit and is not coupled to repository save.

Adding a deferred listener introduces stored event/delivery state: generate and review
its migration before execution. Payload evolution includes changes to referenced
value objects, not only the event declaration. [Explore event delivery](domain-events.md).

## 5. Keep the outside world outside

A carrier's JSON payload is not your domain model. The delivery demo's ShippingCarrier
port accepts Shipment and returns ShippingQuote. Its adapter maps DTOs, invokes HTTP,
and classifies failures. Unknown response properties are tolerated; missing required
values are not. A remote 404 becomes absence only when explicitly declared.

[Run the ports walkthrough](ports-walkthrough.md), then change the local fixture and
watch the error contract explain which boundary failed. A database rollback cannot
undo a successful remote request.

## Continue the story

[Persistence](database-persistence.md) → [Schema evolution](flyway-workflow.md) →
[Events](domain-events.md) → [Ports and ACL](ports-walkthrough.md).

The demo's runners assert these behaviors and show the steps in the log. They are
executable lessons, not a complete logistics product. Your application's policies,
UI and external operational requirements build on this architecture.
