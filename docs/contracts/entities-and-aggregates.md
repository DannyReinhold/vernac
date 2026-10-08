# Entity and aggregate contract

This contract covers the reviewed namespace/project compilation path. It builds on the
[value-object contract](value-objects.md), behavior access interfaces, and
[validation delegates](../development/validation-delegates.md).

## Identity and fields

An entity or aggregate has exactly one non-optional Vernac id, declared separately:

```vernac
namespace example.delivery;

id StopId;
id TourId;
value Address(String);
value Units(int);
value TourName(String);

entity Stop[StopId](Address, mut Units) behavior {
    modify void changeUnits(Units units) { self.units(units); }
} list Stops;

aggregate Tour[TourId](TourName, mut Stops, TourName? note) behavior {
    read int totalUnits() {
        return self.stops().stream().mapToInt(stop -> stop.units().intValue()).sum();
    }
    modify void changeDelivery(StopId id, Units units) {
        self.stops().by(id).orElseThrow().changeUnits(units);
    }
};
```

The identity member is always named `id`. It is immutable. Equality and hash code use
identity, not the object's mutable state. Constructors are private; generated classes
live in `<namespace>.domain`. Custom packages and automatic `fromExternal` factories
are not part of this contract.

Allowed field types are:

- Vernac ids, value objects, and enum value objects;
- explicitly declared lists and sets of these types;
- contained entities, and explicitly declared lists and sets of entities.

Direct Java fields, including `String`, `int`, and approved Java wrapper types, are
rejected. Wrap these in a domain value object. The restriction applies to stored fields;
behavior parameters and results may still use the approved Java types.

Aggregates cannot be contained, directly or through collections. Reference another
aggregate through its id, or an id collection. Collections of aggregates may still be
declared and used outside domain containment, for example as query results.

`mut` permits replacing the field value/reference. It does not make the field optional.
A field without `mut` cannot be replaced, but a contained entity can still change through
its public behavior. Immutable collection membership does not imply immutable elements.

Optional fields are stored as nullable references. Constructor/factory/write parameters
carry `@Nullable`; record-style getters return `Optional<T>`. Generated owners and access
interfaces use `@NullMarked`, and `equals` accepts `@Nullable Object`.

## No containment cycles

The compiler checks the resolved entity type graph across files and namespaces. Optional
fields and both list/set elements create containment edges just like required fields.
A cycle is rejected even if an optional field could be empty or a collection could have
no elements at runtime. Direct recursion, such as `Node` containing `Nodes`, is rejected.

An id field is not an edge. `A` containing `B`, with `B` containing `AId`, is allowed.
Diagnostics show the cycle's field path and recommend an id back-reference. Shared
acyclic entity types are allowed; this check does not establish instance ownership.

## Factories

`create(...)` receives all domain fields, in declaration order, and no id or metadata.
It always obtains a fresh id through `IdType.create()`.

If any fields are optional, one additional `create(...)` receives only required fields.
It delegates to the full factory with `null` for omitted optional fields. There are no
factories for every optional-field combination. With only optional fields, this becomes
a zero-argument factory. A fieldless entity is also permitted; a fieldless value object
remains forbidden.

`reconstitute(...)` takes the id first, followed by all domain fields in declaration
order. It preserves the supplied identity and checks required values and invariants,
just as creation does. There is no optional-field convenience overload for reconstitution.

For entities:

```java
Stop.create(address, units);
Stop.reconstitute(stopId, address, units);
```

For aggregates, reconstruction additionally takes `createdAt`, `updatedAt`, and `version`,
in that order, after the domain fields:

```java
Tour.create(tourName, stops); // optional note omitted
Tour.create(tourName, stops, note);
Tour.reconstitute(tourId, tourName, stops, note, createdAt, updatedAt, version);
```

Required domain values that are null cause `DomainValidationException`. A false invariant
also causes `DomainValidationException`. Unexpected exceptions from user expressions
propagate. Reconstitution is not a bypass for invalid historical data: migrations or
explicit domain evolution must provide a valid representation.

## Aggregate metadata

Only aggregates receive generated creation/update timestamps and technical versions.
Entities have none of these generated members. Domain-significant entity timestamps can
be modelled explicitly as value-object fields.

Creation captures one `Instant.now()` and uses it for both `createdAt` and `updatedAt`.
The technical version starts at zero. Reconstitution preserves supplied timestamps and
version; it must not replace them with the current time. Timestamps must be non-null and
versions non-negative. No ordering requirement is imposed on timestamps: system clocks
are not a monotonic logical clock.

`createdAt()` and `updatedAt()` are public read getters, also available through the read
view. They have no domain setters. `createdAt` never changes after construction.

### Effective changes and update time

Generated write operations mark effective changes in the synchronous modification scope.
After the outermost modify body returns successfully, all registered validations run.
Only if they all succeed are changed participants' timestamp callbacks invoked, once per
owner, with one shared current timestamp. Behavior and validation see the previous update
time until this final step.

- Equal ids and value objects, including value collections, count as unchanged.
- Reassigning the same entity instance counts as unchanged.
- Replacing an entity by a different instance counts as changed even with an equal id:
  identity equality does not establish state equality.
- Entity lists compare element instances and occurrence order for change detection.
- Entity sets compare instance membership without order. Their ordinary equality and
  duplicate-handling rules remain unchanged; existing set elements still win on `plus`.
- A no-op setter or modify call does not update the timestamp.
- A write followed by a write back still counts as a modification. There is no final-state
  snapshot comparison or candidate-state machinery.

Changes in nested child modify calls also mark currently enclosing modify participants.
Therefore a child change inside a tour modify method updates the tour timestamp, even
when no tour field itself is reassigned. A completed, unrelated sibling invocation is
not marked by subsequent changes elsewhere in the scope.

**No persistent ownership tracking exists.** A direct call to a child outside its
aggregate's modify behavior cannot discover or timestamp the aggregate. Invoke changes
through the aggregate when aggregate-wide validation and timing are needed. Read views
are shallow and do not provide a purity/security boundary.

### Failure semantics

A body or final-validation exception prevents timestamp publication for the scope.
The previous timestamp remains, but field mutations already performed remain too.
Abort the use case and discard the affected in-memory graph. Database rollback does
not restore Java objects. Generated write views expire at scope exit and cannot be used
from another thread or during validation. The objects themselves are not thread-safe.

### Technical version access

There is no generated public `version()` or `withVersion(...)` domain method, and no
version member in the Read/Write/Access interfaces. Infrastructure uses the explicitly
technical bridge:

```java
long expectedVersion = aggregate.persistenceState().version();
aggregate.persistenceState().version(nextVersion);
```

`PersistenceState` stays attached to the same aggregate instance. Updating it changes
neither identity nor domain timestamps and performs no domain modification. It is an
adapter API, not an access-control mechanism against arbitrary Java code.

The existing JDBC generator now reads/writes this state and returns the same aggregate.
It still uses the expected version in the UPDATE predicate and writes the next version;
failed optimistic locking must not advance the in-memory version. Its current update
point is after successful SQL work, **before transaction commit**. Commit failure and
rollback synchronization, version coverage for child-table changes, and overflow handling
need the dedicated persistence review. Do not infer a completed transactional contract.

## Domain generation and persistence

The reviewed domain generator no longer computes SQL DDL or emits `TABLE_NAME` and
`SCHEMA_DDL` constants. Domain legality must not depend on whether a persistence mapping
exists: enum fields and cross-namespace types are valid domain fields. Low-level legacy
schema tests remain in place pending the persistence redesign. Explicit repository
mapping retains its own checks, including rejection of implicit enum storage formats.

## Verification and remaining work

Compiler tests cover factories, optional overloads, id generation, enum/domain fields,
containment cycles across namespaces and id back-references. Generated-Java execution
tests cover reconstruction, no-op and nested modification timing, failed validation,
retained changes, entity replacement, and technical version access. Runtime tests verify
callback ordering and scope cleanup. LSP tests verify live diagnostics and their removal.

The dedicated persistence step will cover schema generation, enum/ACL mapping, migrations,
transaction boundaries and complete aggregate optimistic locking. Events and aggregate
ownership remain separate topics. The candidate-state design remains postponed; see
[the planning record](../development/entity-behavior-and-change-contexts.md).
