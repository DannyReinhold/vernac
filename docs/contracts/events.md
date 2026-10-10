# Domain events

Status: event classes and synchronous listeners are implemented. Durable delivery
and explicit recording follow the [delivery plan](../development/event-delivery.md).
The compiler rejects `recorded event` and `deferred listener` until that implementation
exists. It never silently substitutes in-memory handling for requested persistence.

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
The planned event store must reject conflicting representations of one occurrence.

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
publication performs no further action in this first increment.

Repository save never publishes buffered events. The previous registerEvent /
pullDomainEvents / EventDispatcher mechanism and its generic JSON outbox have been
removed. Previously unreviewed examples using that syntax need deliberate migration.

Database rollback does not restore Java objects or undo remote calls. Use synchronous
listeners for work belonging to the current transaction. Durable external work will
use deferred listeners; synchronous handling is not a durability substitute.

## Tooling and verification

Namespace resolution, payload diagnostics, event/listener completion, definition
navigation, and Java projection of listener bodies/event validations use the same
compiler model. Generated technical interfaces remain navigable as Java interfaces.
Tests compile and execute generated events and listeners, exercise external code,
multiple consumers, validation, REQUIRED participation, context cleanup and failure
handling. These tests use Spring transaction infrastructure and a counting manager;
they do not claim PostgreSQL outbox concurrency coverage, which belongs to the next
increment.
