# Vernac language reference

This reference describes the **Vernac 0.4.0-SNAPSHOT development language and generated API**. Use matching
compiler and runtime versions. The next development checkout builds 0.4.0-SNAPSHOT;
published 0.3.0 consumers use its earlier contract, with one collection per element. Obsolete experimental syntax is not supported.
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
value Tag(String) list Tags, set SelectedTags, list ArchivedTags;
id TourId set TourIds;
```

Each comma-separated list/set clause declares its own collection type and may
have its own behavior block. Names are independently optional; `value Wert(String)
list, set Werte;` generates Werts and Werte. Conflicting defaults or explicit names
are errors. Multiple clauses require the 0.4.0-SNAPSHOT compiler.

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
No arbitrary SQL, nested traversal or partial-aggregate projections are implied.
Offset-qualified queries are described in the pagination section below.

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

An adapter can declare technical properties before its implementation:

```vernac
configuration {
    URI baseUri from "carrier.base-url";
}
```

Spring reads required values at construction; `self.baseUri()` exposes the typed
value in behavior. Supported types are String, URI, Duration, Path, int, long and
boolean. See [configuration](contracts/adapter-configuration.md).

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

## HTTP inbound adapters

`adapter Name inbound http uses Usecase dependency { ... }` defines named endpoints
with typed path/query/header bindings, an optional DTO body, explicit assignments to
usecase inputs and a response mapping. Assignments retain target-left `<-` orientation.
The adapter creates no implicit Result; `<Usecase>.Result` is a supported response
mapping source for explicitly declared tuple results. Transactions remain in usecases.
See the [contract](contracts/inbound-http.md) and [complete tutorial](tutorials/inbound-http.md).

## Offset and cursor pagination

```text
repository IssueRepository for Issue {
    find offset Issues withStatus(IssueStatus status)
        where issueStatus = :status order by createdAt desc, id asc;
    find offset Issues countedWithStatus(IssueStatus status)
        where issueStatus = :status order by createdAt desc, id asc counting;
}
```

Requires a declared list of complete aggregates and an explicit order ending in root
id. Without counting the method returns OffsetQuery<Issue, Issues>; with counting it
returns CountedOffsetQuery<Issue, Issues>. Both bind the filters and expose page(request)
and lazy pages(request). Ordinary finds retain their original API.

Use `OffsetRequest.ofPage(3, 20)` or `ofOffset(40, 20)` in a Java body, importing from
org.vernac.runtime.pagination. Pages retain their concrete items list. Counted pages
add totalElements/totalPages; count and root selection share one SQL statement snapshot.
Each load requires an active transaction, reconstructs full graphs and checks versions.
A concurrent root change may raise an optimistic-lock conflict. Independent loads are
not a frozen snapshot. Generated repositories bind `vernac.pagination.max-page-size`
(default 1000), rejecting larger requests before SQL.

Cursor declarations use the same list and explicit final-id requirements:

```text
find cursor Issues following(IssueStatus status)
    where issueStatus = :status order by createdAt desc, id asc;
```

The method returns CursorQuery<Issue, Issues>. In a Java body, call
`page(CursorRequest.first(20))` or continue with `CursorRequest.after(cursor, 20)`.
CursorPage exposes items(), nextCursor() and nextRequest(); it has no count or absolute
page number. All cursor sort keys must be required. Each direction and physical time
component is respected; decimal prefix comparison follows numeric order, not scale.
The saved boundary survives deletion of its root. Tokens bind to the query, filters,
complete owned schema fingerprint and codec version, and are encrypted/authenticated.
Configure `vernac.pagination.cursor-key` (Base64 of 32 random bytes) for durable/shared
continuations. Blank uses a process key and loses validity after restart/on another
replica. Invalid tokens raise InvalidCursorException. Tokens never authorize access.
Paginated usecase results and HTTP/DTO mappings are implemented as described below.
PageNumber/PageSize/PageCursor are technical Java types, not modeled signature built-ins.
See the [tutorial](tutorials/pagination.md) and [normative contract](contracts/pagination.md).

### Usecase result components and DTO fields

```text
usecase ReadBoard(PageNumber openPage, PageNumber closedPage, PageSize)
    returns (Title heading, counted offset Summaries openItems,
        counted offset Summaries closedItems, cursor Summaries recentItems)
    uses IssueRepository behavior { execute { /* return Result.of(...); */ } }
```

Summaries must be a declared list of immutable domain values (IDs/VOs/enums). Pages
are required; aggregates/sets/optional pages are rejected. The single-return form
`returns counted offset Summaries` is also supported. These result qualifiers are
not input/domain-field built-ins. PageNumber/PageSize in this sketch are application
VOs; project their getters into runtime request factories explicitly.

DTO page fields use `schema BoardDto(counted offset list<ItemDto> openItems, ...);`.
A mapping assignment `openItems <- source.openItems using item;` applies the named
entry conversion and retains metadata. Different variants, page flattening and page
internal traversal fail. HTTP still returns a named local schema, whose generated
codecs serialize completed envelopes without a global Jackson module. Invalid query
cursors return 400/INVALID_CURSOR; ordinary domain validation remains 422.
The [complete tutorial](tutorials/pagination.md) includes the model, usecase and GET.

## Inbound error policies

HTTP adapters and endpoints support ordered `errors { when ... then respond ...; }`
policies. Endpoint rules are checked first, then adapter rules, then safe defaults.
Selectors: `rejection EnumType.CONSTANT`, `integration local|remote|unknown CATEGORY`,
`invalid request`, `invalid cursor`, `validation`, `not found`, `optimistic conflict`,
`domain rejection`. A category is an existing IntegrationCategory constant. Typed
rejections match PortRejectedException; no implicit typed usecase rejection is added.
`then respond 409;` preserves the safe default code/message. `then respond 409
"PUBLIC_CODE" "Public message";` replaces them. Both strings are required together.
Statuses are 400–599. Optional `otherwise default;` retains the final default fallback.
A policy block or `errors implemented by ...;` may follow an endpoint response, or
appear last in an adapter. The forms are exclusive per scope. Response-mapping failures
stay technical; policies never change transactions, retries or authentication.
See the [normative inbound contract](contracts/inbound-http.md#declarative-inbound-error-policies-040).

## Reusable type modules (0.4.0)

A producer declares `vernac-module.vmodule` beside its POM, not below
src/main/vernac. Its separate grammar is `module qualified.identifier { export
fully.qualified.Type; ... }`. Explicit exports currently support IDs, VOs, enums
and named value collections, plus port interfaces. Publicly referenced local types and collection elements
need their own exports. No wildcard exports or foreign re-exports.

A normal compile-scoped Maven dependency makes public contracts available to ordinary
Vernac imports, including compile-visible transitive dependencies. Type identities do
not gain a module prefix. The compiler consumes META-INF/vernac/module.json and never
regenerates dependency classes. External VO structure participates in local schema
flattening/diffs. Module dependencies do not activate beans or migrations.

Build producer and consumer with the current Vernac Maven plugin. In IntelliJ,
New → Vernac Module Definition creates the project file. Build after dependency
changes to refresh the shared resolved editor index. For a runnable two-artifact
example see [type modules](../examples/type-modules/README.md).

### Application composition status

The project wizard distinguishes applications and reusable type libraries. An
application starts with an empty `vernac-application.vapplication`; a type library
gets `vernac-module.vmodule` and explicit exports. The application parser accepts `activate`, fixed `bind ... to ...` and
`bind ... from configuration "key"`. Identity checks and IDE support are available,
and adapter bindings are operational. Explicit `activate` entries register module
usecases, services, repositories, listeners and HTTP entrypoints, including required
functional dependencies; persistent histories are coordinated before startup. See the [module contract](contracts/modules.md).

### Export and implement a port across artifacts (0.4.0)

Contract-only modules can also export ports. Explicitly export every local type used
in the port's public methods. Consumers import the port and its parameter/result
values, then write `adapter Name implements ImportedPort { behavior { ... } }` using
normal port/adapter syntax. The dependency supplies the original compiled interface.
Adding a port operation requires implementation rebuilds. Contract metadata is now
`module-contracts-9`; rebuild older development module artifacts. Outbound adapter publication and application bindings are supported as described below;
Persistent and nonpersistent functional activation are supported; module histories run
in dependency order before the application history. Module event contracts, recorded payloads and synchronous/deferred
receivers are supported. Inbound HTTP entrypoints are internal module components, enabled only through activation.

### Select an exported adapter with Spring profiles (0.4.0)

Modules may export outbound adapters that implement exported ports, including ports
from other Maven dependencies. Their bodies are compiled only by their producer;
consumers use the published implementation class. Register a fixed or property-driven
binding in the consuming project's `vernac-application.vapplication`:

```vernac-application
application example.app {
    bind example.shared.PaymentProvider
        from configuration "billing.payment-provider";
}
```

The property value is an exported/local adapter's qualified Vernac identity, e.g.
`example.providers.NoopPaymentProvider`, not a Java class name. Fixed syntax is
`bind example.shared.PaymentProvider to example.providers.NoopPaymentProvider;`.
Only the selected adapter is registered. Spring resolves its constructor dependencies
and required configuration. Unselected module adapters are not component-scanned.
The generated Boot auto-configuration registers the binding automatically; a plain
Spring context imports the generated `<application name>.configuration.__VernacApplication`
explicitly. Persistent and nonpersistent components support `activate`; the startup
coordinator runs required module histories before dependants and the application.
Use `mvn clean install` with the current compiler to rebuild earlier metadata artifacts.

### Activate nonpersistent module functions (0.4.0)

A module may export domain services and usecases in addition to values, ports and
adapters. Export all local types used in public signatures and inline result fields.
Private service dependencies need no export. Consumer imports use original Vernac
identities; implementations come from compiled dependency classes.

```vernac-application
application example.shop {
    activate example.billing;
    bind example.billing.PaymentProvider from configuration "billing.payment-provider";
}
```

Activation registers all services/usecases in that module, including internal helpers,
and transitively activates modules supplying declared functional dependencies. Type
imports and port implementations do not imply activation. Selected adapters remain
profile-controlled. An application-supplied transaction manager is required for
executing usecases; REQUIRED calls join the current transaction. Module-owned entity/aggregate/repository contracts can be published, imported and
activated with coordinated migration histories. Module event contracts and synchronous/deferred receivers are supported; payload
tables belong to producers and the shared delivery table belongs to the application. See [the executable functional model](tutorials/type-modules.md#activate-a-reusable-usecase-without-exposing-its-helpers).

### Prepare a module migration bundle (0.4.0 foundation)

The standalone Maven goal `vernac:module-persistence` packages an ordered, checksum-
verified SQL/snapshot chain and expected schema beneath
`META-INF/vernac/modules/<logical-module>/`. It does not apply SQL or enable persistent
module exports. In projects with a `.vmodule`, schema/migration/check/compile goals
now default SQL sources to `src/main/vernac-schema/migration`, outside ordinary Maven
resources. Applications retain `src/main/resources/db/migration`. An explicit
`vernac.migrations` override remains available.

Generate/review the next migration before preparing a changed schema. Every module
SQL file needs its source snapshot; repeatable migrations are not supported here.
Persistent contract publication automatically prepares/verifies the bundle. Persistent
activation coordinates verified histories at startup. Recorded module events and synchronous/deferred receivers use those verified histories;
the shared delivery schema is generated only by the application.
See [the persistence preparation contract](contracts/modules.md#module-persistence-bundle-preparation-current).

### Persistent module contracts (0.4.0 foundation)

Entities, aggregates, their collections and repositories may be explicitly exported.
Export all local ID, field and public signature types. Consumers use the compiled
classes/interface from the dependency and MUST NOT define a local repository for a
foreign aggregate. Imported repositories contribute no local tables or generated JDBC
classes. External VOs in local aggregates still flatten into locally owned tables.

Persistent publication requires a matching source migration history and automatically
packages/verifies the isolated bundle. Field mutability, read/modify modes and repository
query/pagination signatures are part of `module-contracts-9` compatibility metadata.
Explicit persistent activation coordinates verified module histories in dependency order,
then application migrations, before dependent beans become callable. It requires the
application DataSource, transaction manager and Spring Boot Flyway initializer. No SQL is run by import,
compile or packaging. Do not bypass activation with dependency component scanning.

### Executable persistent-module lifecycle example

See [examples/persistent-modules](../examples/persistent-modules/README.md): two Maven
producer artifacts export values and usecases while retaining private repositories;
the application activates the dependant module explicitly. Required modules migrate
before dependants, then the application's own history runs. If an exported VO shape
changes, every owner embedding it MUST generate and review its own migration. Rebuild
dependants against the changed public contract; a required field needs an explicit
backfill for existing rows. Importing a value alone does not activate its producer.

### Exported events and module listeners (0.4.0)

A module MAY export an `event` or `recorded event` and MUST explicitly export every local payload type
in its public contract. Consumers import the original qualified event name, construct
it through the compiled factory, emit it from an active usecase context, or declare a
synchronous or deferred listener on it. No dependency event classes are regenerated.

Listeners are internal runtime receivers rather than exported callable APIs. Activating
the receiver's module registers its listeners under qualified identities.
A listener-only module can be activated. Deferred receivers require the producer's
compiled storage component and activate that required producer transitively. Event imports alone do not activate a publisher
or receiver. Declared usecase/service/repository dependencies of active listeners can
activate the required functional modules transitively.

A synchronous listener executes immediately in the emitting usecase's transaction.
Its exception fails that transaction. A deferred listener has its own persisted job,
processing transaction, confirmation and retry. Several active receivers may observe
the same event; their deferred jobs succeed/fail independently.

An illustrative publisher model (the receiving code below uses this public contract):

```vernac
namespace example.billing;
id InvoiceId;
value CustomerName(String text);
event InvoiceOpened(InvoiceId, CustomerName);
```

```vernac
namespace example.observer;
import example.billing.InvoiceOpened;
synchronous listener ObserveInvoiceOpened on InvoiceOpened behavior {
    java imports { org.slf4j.LoggerFactory; }
    execute {
        LoggerFactory.getLogger("example.observer").info("Invoice {} opened", event.invoiceId());
    }
}
```

The receiving artifact declares `module example.observer {}` if it exports no callable
API, and the application uses `activate example.observer;`. Metadata is
`module-contracts-9`; rebuild producer and dependant artifacts together.

### Durable event ownership and startup

For a `.vmodule` producer, every local event has a prepared payload table and compiled
storage adapter, including plain events whose later consumers are not known yet.
Creating a table does not imply recording: emission persists the payload only for a
recorded event or at least one active deferred recipient. Module schemas MUST NOT
contain `vernac.@delivery`; the consuming application's migration owns that shared
table. Merely importing a module event does not activate its producer or execute SQL.

A module deferred listener depends on the producer's EVENT_STORAGE component. Local
application deferred listeners on dependency events require explicit activation of
the producer. Likewise, code emitting an imported recorded event must arrange producer
activation; a Java import alone supplies no runtime storage adapter. Do not declare
repositories or storage classes for foreign event payloads in the consumer.

The application determines its delivery schema from local deferred listeners and its
activated/profile-eligible module graph. Generate/review application migrations after
changing this graph. One Boot worker polls all active recipients only after required
module and application migrations/validation have completed. It preserves the existing
lease, at-least-once, per-recipient acknowledgment, retry and blocked-job semantics.
Receivers must make external effects idempotent; SQL rollback does not undo them.

Record flags and storage capability participate in module-contracts-9 metadata.
Rebuild all producers/dependants. Structural event/VO changes can make pending payloads
incompatible; migrate deliberately or introduce a new versioned event type. Review
event evolution independently of an ordinary aggregate upgrade.


### HTTP entrypoints supplied by modules

A module may contain ordinary `adapter ... inbound http` definitions. These are internal
entrypoints, not exported callable types. Activate the owning logical module in the
application's `.vapplication` file to register its controllers and scoped error policies.
Required usecase modules activate transitively. A dependency or type import alone exposes
no routes, even under component scanning. DTOs, mappings and errors remain producer-owned;
consumer compilation does not regenerate them. The application supplies
`vernac-runtime-http` and `spring-boot-starter-web`. Route paths are unchanged; ambiguous
routes fail at MVC startup. See the [persistent module walkthrough](../examples/persistent-modules/README.md)
for the API module and a real cross-module HTTP call.


### Module authoring and dependency refresh checklist

- A `.vmodule` exports public contracts; a `.vapplication` selects/activates runtime
  composition. Do not put application bindings in a producer module.
- Build/install the producer, declare the consumer Maven dependency, then build the
  consumer. Maven selects versions; the LSP reads the build-written index.
- Export every local type referenced by a public signature, including result fields
  and collection types. Internal services/listeners/HTTP adapters need no public export.
- Use Go To Declaration on an activation identity for a read-only module overview.
  Type navigation opens available producer sources or a marked contract view.
- After dependency builds, completion/navigation detect changed indexes. Incompatible
  metadata is diagnosed on open files. Never edit generated contract views as a fix.
- Keep module SQL histories immutable after publication. When an exported VO changes,
  inspect all owners that persist it: the producer's tables and each consumer's own
  flattened columns require separately reviewed migrations/backfills.
- This module scope is implemented. SaaS permission/quota policies, ready-made billing
  providers, distributed transactions and cross-artifact refactoring are separate work.


### Ubiquitous-language terms (0.4.0)

`term Name { description "Meaning."; example "A concrete case."; note "An exclusion."; }`
is a top-level documentation declaration in an ordinary namespaced `.vernac` file.
Exactly one nonblank description is required; examples and notes are repeatable and
optional. Quoted names such as `term "Outstanding balance"` permit spaces. Names must
not be blank, contain controls or have surrounding whitespace. Name equality is exact
and case-sensitive; `term Name` and `term "Name"` are duplicates in the same namespace.

Terms are not types. They may coexist with same-named types and may describe concepts
without classes. Only exact namespace/name equality associates documentation with a
model definition; there are no explicit links, inherited meanings or synonym aliases.
Do not invent term imports or exports. Module metadata carries documentation for
exported types automatically; it does not publish standalone or internal-only terms.

Vernac type hover includes associated text, including imported module types without
sources JARs. JavaDoc is added to associated generated domain/usecase/outbound classes.
Maven compilation writes target/vernac-glossary.md and target/vernac-glossary.json.
Text changes do not alter structural API fingerprints or SQL schemas. Module metadata
is module-contracts-9; rebuild development producers and consumers. No LLM integration
is implied. See [the term contract](contracts/ubiquitous-language.md) and
[the tutorial](tutorials/ubiquitous-language.md).
