# Domain events

Status: event classes, synchronous listeners, relational event storage and durable
per-listener jobs are implemented. **Deferred workers are not implemented yet:**
`deferred listener` creates PENDING jobs; it does not execute after commit in this
increment. See the [delivery plan](../development/event-delivery.md).

## Definition and payload

```vernac
namespace example.delivery;
id StopId;
id TourId;
value Reason(String);
event StopMoved(StopId, TourId sourceTourId, TourId targetTourId, Reason?);
```

An event describes a fact that has happened. Its type does not choose how a listener
reacts. Payload fields are IDs, value objects, enum values, or declared collections
of those values. Entities, aggregates and collections of mutable domain objects
are forbidden, as are raw Java scalars (wrap them in a domain value). EventId is a
shared runtime identifier and may also be used explicitly, for example as a
correlation field. Fieldless events are allowed.

Fields are immutable. Names follow the common default-name rules; repeated types
need explicit roles. Metadata names `eventId` and `occurredAt`, generated factory
names and conflicting Object API names are reserved. `id` remains available as a
payload name. No custom packages or behavior blocks are supported.

## Generated Java contract

A final, @NullMarked class is generated in `<namespace>.domain` and implements
`org.vernac.runtime.DomainEvent`:

- Private constructor and final fields; no setters.
- `create(payload...)` creates an EventId and an Instant occurredAt internally.
- `reconstitute(EventId, Instant, payload...)` retains identity and time and validates
  the payload just as create does. This is reconstruction, not a new occurrence.
- If any payload is optional, a required-only create overload delegates to the full
  factory. All-optional events also get create().
- Non-null inputs use DomainChecks; optional inputs are @Nullable, stored nullable,
  and exposed through Optional-returning record-style getters.
- `eventId()` and `occurredAt()` expose metadata. EventId wraps a UUID and offers
  create(), of(UUID), of(String), value(), asString(), equals/hashCode/toString.
- equals/hashCode represent event identity: same generated type and EventId. Equal
  payloads do not make two newly created occurrences equal.
- toString includes identity, occurrence time and every payload field.
- There are no transport constants, DDL, dbValue methods or Java serialization
  requirements on the domain class.

Identity equality does not authorize changing an existing stored event payload.
The event store rejects conflicting payloads or occurrence times for one stored occurrence.

An optional `validates` block uses `self` and a generated read interface, as for
value objects. It runs for both factories. It exposes payload and metadata getters,
not emit or dependencies. Java compilation checks Java expressions; this is not a
purity/security sandbox.

## Synchronous listeners

```vernac
synchronous listener NotifyPlanning on StopMoved
    uses PlanningService planning
behavior {
    execute { self.planning().stopMoved(event.stopId()); }
}
```

The service in this example is an application declaration, not an implicit builtin.
Every listener has its own name. Multiple listeners may target the same event.
Their ordering is unspecified; do not encode an operation sequence between them.
Listeners reside in `<namespace>.adapter.inbound.event` and are Spring beans.
They do not open a transaction. Synchronous execution participates in the emitter's
transaction. They may invoke usecases whose REQUIRED transaction joins that transaction.

One execute block is required, or `execute implemented by my.application.Handler;`.
External implementations supply `public static void execute(NameAccess self,
EventType event)`. They are not required to be Spring beans. Both implementations
use the generated companion class. Optional private inline helpers and a first
`java imports { fully.qualified.Type; }` block work as for usecases. `event` is the
fixed input name. Dependencies are constructor-injected into the generated bean and
exposed through self; the external class does not receive a Spring container.

## Publication and transaction context

`self.emit(event)` is available to entity/aggregate modify implementations, domain
services, usecase implementations and listeners. It is absent from Read interfaces,
validators and value-object behavior. Domain services do not yet have read/modify
classification; revisiting this is a roadmap item.

Generated usecase execute methods establish a scoped EventContext. Nested REQUIRED
usecases reuse it; the outer scope closes in a finally-equivalent try-with-resources.
Handlers are discovered lazily through an injected ObjectProvider, avoiding eager
constructor cycles between usecases and listeners. The context is thread-bound and
is not inherited by worker threads. It cannot span independent transactions.

Emit requires an active synchronized transaction. Calling a generated usecase on a
manually constructed object does not activate Spring interception. For handwritten
transactional entry points, open `EventContext.open(() -> handlers)` inside the
transaction with an explicit list of EventHandler instances; always close the scope.
No global application context lookup is performed by domain objects.

Synchronous listeners run immediately during emit. A failure propagates and prevents
a successful transaction commit even if application code catches the exception.
A failed context cannot continue emitting. Recursive publication of the same active
event, duplicate listener identities, and excessive nested dispatch are rejected.
There is no ordering guarantee between independent listeners. Without listeners,
publication stores only events explicitly declared `recorded`.

Repository save never publishes buffered events. The previous registerEvent /
pullDomainEvents / EventDispatcher mechanism and its generic JSON outbox have been
removed. Previously unreviewed examples using that syntax need deliberate migration.

Database rollback does not restore Java objects or undo remote calls. Use synchronous
listeners for work belonging to the current transaction. Durable external work uses deferred listeners; synchronous handling is not a durability
substitute. Worker execution is a subsequent implementation stage.

## Recording and durable delivery jobs

```vernac
recorded event StopMoved(StopId, TourId sourceTourId, TourId targetTourId, Reason?);
deferred listener NotifyDriver on StopMoved behavior {
    execute { /* delivery implementation */ }
}
```

Recording is optional. A normal event is also persisted when at least one deferred
listener is registered. A recorded event implements the runtime RecordedEvent marker
(which extends DomainEvent); this requests storage even without listeners.

The compiler generates `Jdbc<EventName>Events` in
`<namespace>.adapter.outbound.jdbc.event`. It is a Spring component implementing the
technical EventStorage interface, discovered with the EventHandler beans. It requires
NamedParameterJdbcTemplate, the JDBC runtime and the generated Flyway migration.
Include these components in application component scanning. Missing or duplicate
storage adapters cause publication to fail; storage is never silently skipped.

EventContext persists the immutable payload and one job per deferred listener before
calling synchronous listeners. All writes participate in the publisher's transaction.
A synchronous listener failure rolls back the event, jobs and other transactional
application writes. Deferred bodies do not run during emit.

The event table is `<namespace>."@event:<EventName>"`; `@eventId` is its primary key,
`@occurredAt` and its nanosecond remainder retain the original time. Payload fields
reuse the normal scalar, flattened value-object and collection mappings. A payload
field named `id` remains independent of event identity. Domain VOs do not need Java
serialization or Jackson annotations.

The common `"vernac"."@delivery"` table stores event identity, listener identity,
status, attempts, scheduling timestamps, claim token, lease and last error. The unique
key is (eventType, eventId, listenerId). New jobs are PENDING with zero attempts and
no claim. Worker-related columns are reserved for the next stage. There is no
polymorphic SQL foreign key from delivery to the different event tables; generated
storage creates both atomically. Direct changes to owned tables are unsupported.

Republishing an identical stored occurrence creates neither another payload nor new
jobs, including for listeners added later. A different payload or time with the same
event identity fails. Synchronous listeners still run on every emit: this is durable
storage deduplication, not global suppression of publication. Payload comparison uses
value equality, not the event class's identity-based equals method.

No automatic deletion, retention or replay runs in this increment. All stored payloads
remain, including those stored only for deferred delivery. Renaming event/listener
identities, removing the last deferred listener, changing payload types or changing
nested VO validations requires deliberate deployment and migration review. The schema
and migration goals include event and delivery tables; a generated migration candidate
does not guarantee that old payloads can still be reconstructed and validated.

## Tooling and verification

Namespace resolution, payload diagnostics, event/listener completion, definition
navigation, and Java projection share the compiler model. Tests compile generated
storage adapters and listeners and check recording selection, collection ownership,
schema changes and synchronous/deferred routing.

`EventStoragePostgresTest` additionally verifies payload round trips, nanoseconds,
BigDecimal scale, nested lists, duplicate occurrences, two listener jobs and transactional
rollback. Its database test requires VERNAC_PG_TEST_URL, VERNAC_PG_TEST_USER and
VERNAC_PG_TEST_PASSWORD; without a URL that test is explicitly skipped. Compilation
coverage runs independently. Worker concurrency and crash recovery are not yet tested
because workers are not implemented in this increment.
