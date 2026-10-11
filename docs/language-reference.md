# Vernac language reference

This reference describes **0.2.0-SNAPSHOT**. It covers the supported project-compilation
pipeline, not every production retained in the parser for internal/legacy tests.
The [specification index](specification/index.md) identifies the normative document
for each rule. A discrepancy between implementation and contract is a defect to
investigate, not permission to silently redefine the contract.

Examples without a namespace or referenced type declarations are fragments to insert
into an existing model. [From model to application](tutorials/from-model-to-application.md)
contains a complete model. For an introduction, follow the [tutorials](tutorials/index.md).

## Files, namespaces and imports

The conventional source root is `src/main/vernac`. Files are UTF-8, end in `.vernac`,
and declare a namespace matching their source-root-relative directory:

```vernac
namespace org.example.delivery;
import org.example.people.OwnerName;
import org.example.shared.*;
```

This file belongs under `org/example/delivery/`. A namespace may span files; names
in it are visible without imports. Type identity is namespace plus simple name,
not the filename. Explicit/wildcard imports refer to Vernac types, never arbitrary
Java classes. Fully qualified Vernac references are supported. Unknown, ambiguous,
duplicate and redundant own-namespace imports are diagnosed according to the
[namespaces specification](specification/namespaces-and-imports.md).

Declarations are resolved project-wide independently of source order. Imports do
not recursively textually include files. Namespace is an organizational concept;
it does not automatically create a DDD bounded context.

Namespace/import statements require semicolons. Declaration terminators may be
optional where the grammar permits; examples use semicolons consistently where useful.
Per-definition Java package overrides are not supported by the reviewed pipeline.

## Names and generated packages

Vernac supports the specified Java-compatible Unicode identifier alphabet without
normalizing spelling. Vernac keywords may be namespace segments when valid for the
Java target. Java keywords, forbidden control characters and API collisions are
rejected. Built-in type names cannot be redeclared as Vernac types.

| Definition | Public Java location |
| --- | --- |
| ID, value, enum, entity, aggregate, event, named collection | `<namespace>.domain` |
| Domain service, repository interface, port interface | `<namespace>.domain` |
| Usecase | `<namespace>.usecase` |
| JDBC repository | `<namespace>.adapter.outbound.jdbc` |
| Outbound adapter | `<namespace>.adapter.outbound` |
| Listener | `<namespace>.adapter.inbound.event` |
| Domain access interfaces | `<namespace>.domain.access` |
| Usecase access interfaces | `<namespace>.usecase.access` |
| Adapter DTO holder | `<namespace>.adapter.outbound.dto.<Adapter>Dtos` |

Omitted field/parameter/dependency names derive from the simple type name:
`StatusReason` → `statusReason`, `URLValue` → `urlValue`, `int` → `intValue`.
An explicit name takes precedence. Single-field values have no `value` exception.
`value Title(String);` exposes `string()`; `value Title(String value);` exposes `value()`.
Collection names default to the element name plus `s`, without dictionary pluralization.
Repository names default to `<Aggregate>Repository`.

Effective names must be unique and legal in the generated Java API. Vernac does not
number or silently rename collisions. Entity/aggregate identity is always named `id`,
independently of how an ID is named when used as an ordinary field.
See [names and Unicode](contracts/names-and-unicode.md).

## Types and optional values

Ordinary value fields accept the explicit [built-in type catalog](specification/domain-field-types.md):
Java primitives and boxed equivalents, String, BigInteger, BigDecimal, UUID, Currency,
and the listed immutable java.time types. The catalog is not a general Java import mechanism.

| Context | Modeled data |
| --- | --- |
| Value object | Approved built-ins, IDs, enums, other VOs and collections of values |
| Entity or aggregate | IDs, enums, VOs, entities and eligible named collections |
| Event payload / usecase input and output / port signature | IDs, VOs, enums and collections of values |
| Adapter schema | Approved transport scalars, local DTOs and technical collections |

An entity/aggregate cannot contain an aggregate, directly or through a collection.
Use an aggregate ID instead. VOs cannot contain mutable entities, even through an
immutable collection. Direct entity containment cycles are forbidden; ID references
are not containment. A primitive/built-in entity state field requires a domain wrapper.

`T?` is optional. Domain storage and public factory inputs use `@Nullable T`;
getters return `Optional<T>`. `int?` is rejected; use `Integer?` or an optional domain
wrapper. Generated top-level types are `@NullMarked`. An Optional container itself
must never be null. DTO/result records have their separately specified Optional
component contracts, not nullable record accessors.

## IDs

```vernac
id TourId;
id StopId set StopIds;
```

An ID is a final UUID wrapper with a private constructor. Its API includes
`create()`, `of(UUID)`, `of(String)`, `value()`, `asString()`, identity-value equality,
hashing and rendering. ID state/behavior cannot be customized. A named list or set
may be requested explicitly. See [ID rules](contracts/names-and-unicode.md).

## Value objects

```vernac
value Title(String) validates {
    require(!self.string().isBlank(), "Title must not be blank");
};
value Money(BigDecimal amount, Currency currency);
value Notes(String? description, String? comment);
```

A VO generates a final class with private construction, final fields, `of(...)`,
record-style getters, `equals`/`hashCode` over all fields and `toString`. It is not
a Java record. There are no `mut` fields or setters; fieldless VOs are rejected.
All-optional VOs are allowed and are distinct from absence of the VO itself; they
have only the full factory, not an additional zero-argument factory.

The full factory accepts all fields. If required and optional fields coexist, one required-only overload
delegates to the full factory; combinations are not enumerated. Null checks precede
validation. Failed construction returns no object. Ordinary VOs have no redundant
`asString`/`asUuid` helper; UUID single-value helpers are specified in the
[value contract](contracts/value-objects.md).

## Enum values

```vernac
value TourStatus = PLANNED | COMPLETED;
```

Enums become Java enums with `@NullMarked` and the ValueObject marker. Standard
`name()`, `values()`, `valueOf(...)` and `==` apply. There is no generated `dbValue()`.
Constants are unique valid names; uppercase naming is a recommendation, not a rule.
`String` or `name` can be a constant without declaring a type or a method.

Enums may have behavior and a named collection. They do not have custom state,
constructors, constant-specific bodies or validates blocks. Persistence codes and
external mappings are separate concerns. See [enum contract](contracts/enum-value-objects.md).

## Named collections

```vernac
value Tag(String) list Tags;
id TourId set TourIds;
```

Collections are explicit final domain classes. Lists preserve order/multiplicity;
sets retain the first equal instance. Input structure is copied, output views are
unmodifiable, and transformations leave the original collection unchanged.
Mutable entity elements are not deep-copied.

The API includes `empty`, `of`, iteration, `stream`, `filter`, `matching`, `find`,
`contains`, `count`, `plus`, `plusAll`, `minus`, `minusAll` and Java collection views.
Lists also provide `get`, `first`, `last`, `distinct` and `duplicates`.
`duplicates()` returns **all occurrences of repeated values, including the first**;
`duplicates().distinct()` selects one representative per group.

Entity/aggregate collections add `by(id)`, `contains(id)`, `minusId(id)` and
`minusAllId(id)`. Collection `by` returns Optional; repository `byId` has a different
missing-result contract. Exact signatures, set equality and ordering are defined
in the [collection contract](contracts/collections.md).

## Behavior and validation

```vernac
value ProductName(String) behavior {
    java imports { java.util.Locale; }
    public String display() {
        return self.string().toUpperCase(Locale.ROOT) + suffix();
    }
    private String suffix() { return "!"; }
};
```

Inline Java is generated into a separate companion, not inserted with privileged
access to private domain state. Public VO/enum/collection methods receive `self`;
private helpers receive only explicitly declared arguments. Public behavior may
instead declare `implemented by fully.qualified.Class;`. The external static method
receives the same self and arguments as its inline equivalent.

Java imports belong in the behavior block. They do not change the resolution of
modeled fields/signatures and cannot shadow protected Vernac/built-in names.
Nullness is checked at generated boundaries and can be checked statically using
NullAway, as configured in the basic template. A null required/Optional result is
a BehaviorContractException, not a business rejection.

`validates { require(condition, "message"); }` runs through a separate read view
using `self.field()`. It does not expose setters or emit. These checks provide API
boundaries, not a purity/security proof of arbitrary Java. See [behavior](contracts/behavior.md).

## Entities and aggregates

```vernac
id StopId;
value Address(String);
entity Stop[StopId](mut Address) behavior {
    read Address currentAddress() { return self.address(); }
    modify void relocate(Address address) { self.address(address); }
} list Stops;
```

Entities and aggregates use `read` and `modify` for public behavior; private helpers
remain private. Read methods receive Read views; modify methods receive Access
views combining Read and Write. The domain object implements neither access
interface. Field setters are not public domain methods.

`create(fields...)` always creates the ID internally. `reconstitute(id, fields...)`
retains identity and validates again. Aggregates additionally reconstruct technical
version and creation/update timestamps; entities do not gain aggregate metadata.
Required-only create overloads omit optional fields, never accept an external ID.

Equality and hashing depend on identity; toString includes identity and domain
fields. Aggregate timestamps start equal, and effective modifications update the
modification time. Technical optimistic version access belongs to infrastructure.
See [entities and aggregates](contracts/entities-and-aggregates.md).

### Validation failures and object state

Modify operations change live objects. Validation occurs at successful outermost
modify completion, including participating nested receivers. Failed behavior or
validation does not restore fields or metadata. Do not continue using a failed
graph as though it had rolled back. Abort the operation and obtain fresh state when
needed. Database rollback does not repair Java objects or undo external I/O.

Read protection is shallow: returned children are ordinary entity instances, not
recursively immutable proxies. The contract does not enforce aggregate ownership
against every direct Java reference. Candidate-state rollback is not implemented.

## Repositories and queries

```vernac
repository TourRepository for Tour {
    find Tours withStatus(TourStatus status)
        where tourStatus = :status order by title;
    find Tour? titled(Title title) where title = :title;
}
```

Declare the referenced aggregate and result collection explicitly. The generated
interface provides `byId`, `save` and `delete`; JDBC requires a surrounding transaction
(MANDATORY). byId returns the complete graph or AggregateNotFoundException. Save
uses optimistic locking; it does not silently reinsert a concurrently deleted root.

Find names do not imply predicates. Supported predicates include `=`, `!=`, `<`,
`<=`, `>`, `>=`, `is absent`, `is present`, `like`, `contains`, `starts with` and
`ends with`, combined with `not`, `and`, `or` and parentheses. Precedence is not,
then and, then or. Fields reference eligible root values or `id`, `createdAt`,
`updatedAt`; `:name` references a typed parameter. Capabilities depend on the scalar.
No arbitrary SQL, nested traversal, projections or pagination is implied.

Find returns a declared aggregate collection or an optional singleton. Multiple
matches for a singleton raise NonUniqueQueryResultException; this does not create
a database uniqueness constraint. Ordered queries require list results. A list
query without order produces a warning; ties remain unspecified. Contained-list
position is independent of the order of root query results.

Equality follows the documented Java semantics, including decimal scale, signed
floating zero and temporal components. Absent optional fields do not match a
non-optional comparison parameter. Text contains/starts/ends treat text literally;
LIKE uses explicit pattern semantics. See [repository queries](contracts/repository-queries.md).

## Persistence and migrations

Repository-backed aggregates map to quoted PostgreSQL schemas named by namespace.
VOs flatten recursively. Optional structures use presence information when necessary;
entity state and relationships are distinct so references can share state. Complete
loads preserve shared Java identity within an aggregate and check concurrent changes.
There is no lazy loading. Scalar representation limits are explicit.

| Maven goal | Effect |
| --- | --- |
| `vernac:compile` | Generate Java; reconcile compiler-owned source files |
| `vernac:schema` | Generate expected schema under target; no database changes |
| `vernac:migration` | Write a candidate migration and versioned model history |
| `vernac:schema-check` | Replay migrations and compare owned schema using disposable databases |

Migrations are candidates, never deployment guarantees. The application owner MUST
review, test and approve them. `force` permits generation despite recognized data-loss
risk; it does not invent a safe conversion or provide approval. Backfills and enum
code changes require deliberate decisions. See [persistence](contracts/postgresql-persistence.md),
[scalar limits](contracts/scalar-storage-limits.md) and [migration tutorial](tutorials/flyway-workflow.md).

## Usecases

```vernac
usecase CompleteTour(TourId) uses TourRepository tours behavior {
    execute {
        var tour = self.tours().byId(tourId);
        tour.complete();
        self.tours().save(tour);
    }
}
```

A usecase is one Spring operation with REQUIRED propagation. Inputs/results are
domain values, not entity graphs. Omit returns for void; `returns Title` returns a
value; `returns Title?` returns Optional; `returns (TourId, Title?)` creates a nested
Result record with `of` factories. Results are explicit, not inferred from Java.

`uses` has comma-separated dependencies without parentheses. Access dependencies
through self. Exactly one execute is required; private helpers and Java imports
are allowed. `execute implemented by ...;` delegates to static external Java.
Optional validates sees only input getters. Spring interception is required for
transaction semantics; nested REQUIRED operations join the same physical transaction.
See [usecase contract](contracts/usecases.md).

## Domain services

A stateless `service Name uses ... behavior { ... }` generates a Spring domain bean
with named public operations, private helpers and optional per-operation preconditions.
Unlike usecases, services do not open transactions and can take entities/aggregates.
Each public implementation receives self Access and its parameters. Dependencies
remain constructor-injected; external methods use the same delegation protocol.
Services do not currently classify methods as read/modify. See [service contract](contracts/domain-services.md).

## Events and listeners

```vernac
event TourCompleted(TourId);
recorded event DispatchRecorded(TourId);
deferred listener NotifyDispatch on TourCompleted behavior {
    execute { System.out.println("Completed: " + event.tourId()); }
}
```

The listener example logs for illustration; logging is not transactional or exactly-once.
An event is immutable and has shared runtime EventId and occurredAt metadata.
`create` creates an occurrence; `reconstitute` retains identity/time and validates.
Equality is occurrence identity, not payload equality. Events can validate but have
no behavior. Fieldless events are allowed.

`self.emit` requires an active transactional EventContext. Generated usecases establish
it; modify behavior, services and listeners may participate. Read views, validators
and VO behavior cannot emit. Publication is independent of save.

A named `synchronous listener ... on Event` runs immediately in the current transaction.
A `deferred listener` creates a durable job handled after commit. Each subscriber has
its own acknowledgement/retries. Event storage is relational per event type, not Java
serialization. `recorded` retains an event without requiring a deferred subscriber.

Workers claim jobs with leases and fencing tokens. Deferred handler work and successful
acknowledgement share a transaction; retry handling and external effects have the
limits in the [event contract](contracts/events.md). Exactly-once external delivery,
strict global ordering and automatic payload evolution are not promised.

## Ports, adapters, DTOs and mappings

A `port Name { Result operation(Input); }` declares an outbound domain capability.
A separate `adapter Name implements Port uses ... { behavior { ... } }` implements
all operations, with self access for inline/external methods. Technical dependencies
use `java uses` inside behavior after optional `java imports`. Adapters do not open
transactions or introduce automatic retries.

Inside an adapter, `schema Name (...)` defines local transport records. Fields support
`named "wire-name"`, `naming snake_case` on the schema, optional `T?`, `absent as null`
and `patch T`/`patch T?`. PATCH distinguishes unchanged, set and (when legal) clear.
Required fields reject missing/null/wrong-type JSON; unknown fields are ignored.

Mappings use one direction: `mapping name: Target <- Source { field <- source.path; }`.
Exact single-value wrapping/unwrapping is allowed; unit conversion and normalization
are not invented. Nested/collection conversion uses explicit named mappings when
required. A mapping can instead use a Java body or external implementation.

An adapter may replace behavior with `rest { client name; base name; ... }`.
Each operation declares, in order, HTTP verb/route; path/query/header bindings;
optional body mapping; response mapping; optional `on 404 absent`; optional error
rules or Java error hook. Supported verbs are GET, POST, PUT, PATCH and DELETE.
Schemas and mappings may precede or follow the implementation.

Declarative error rules use `when ... then reject DomainCode.VALUE "message";`
or `fail local|remote|unknown CATEGORY "message";`. Ordered rules match only when
their condition is true; missing/malformed body operands are not blindly treated
as false. Business codes are declared enums. HTTP status alone cannot determine
business meaning or responsibility. No complete request payload is captured by default.

See [ports](contracts/ports-adapters-and-acl.md), [DTOs](contracts/adapter-dtos.md),
[mappings](contracts/adapter-mappings.md), [REST syntax](contracts/declarative-rest.md)
and [HTTP runtime](contracts/http-adapter-runtime.md) for the complete APIs and rules.

## Compiler and IDE boundary

Maven generates Java and reconciles owned `.java` output using a manifest. It does
not delete arbitrary `.class` files or JARs; use Maven clean for a clean bytecode build.
The LSP shares semantic analysis for diagnostics, completion and navigation. IntelliJ
adds embedded-Java assistance and source-backed navigation; generated APIs must be
built/imported for Java resolution. Arbitrary Java bodies remain subject to javac
and configured static analysis.

[FAQ](faq/index.md) · [Specification](specification/index.md) · [Tutorials](tutorials/index.md)
