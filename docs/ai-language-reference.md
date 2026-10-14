# Vernac: self-contained reference for AI code generation

Language baseline: **Vernac 0.4.0**. Source checkouts default to 0.5.0-SNAPSHOT.
Multiple attached collections require the 0.4.0 compiler/runtime/tooling described
here; HTTP inbound adapters require at least 0.3.0. This document describes implemented
project compilation, not proposed features. Provide this entire file to an AI together
with the application requirements and existing model files. Match artifact versions;
do not assume a development feature is available in an older published compiler.

## 1. Instruction to the code-generating assistant

Generate Vernac, not an approximation of Java, Kotlin, a generic DDD DSL or an older
Vernac syntax. Follow the rules below. Preserve the supplied project's names and
contracts. Supply each source-root-relative file path followed by its complete
content. Resolve all references: include required definitions, identify existing
project definitions, or explicitly identify required handwritten Java dependencies.
Do not invent framework APIs, generated getters, factories or configuration keys.

When a requirement is unsupported, explain that limitation and use an explicit
Java implementation at a supported extension point. Do not invent a keyword.
Keep domain concepts meaningful, use validated factories and explicit persistence.
Prefer small coherent aggregates, IDs across aggregate boundaries, and usecases
for orchestration. Do not silently add architectural responsibilities to a model.

Verification is part of correctness: compile the whole Vernac source root and then
compile the generated Java and handwritten implementations. Parsing alone cannot
validate Java bodies, dependency injection, SQL behavior or external protocols.
If compilation tools are unavailable, say the output is unverified; never claim
that this document alone guarantees compilation or business correctness.

All fenced `vernac` blocks in the worked-example sections below form **complete examples** with
specified paths. They can be compiled together. Other grammar displays are labeled
`text` and use metavariables; they are not literal source files.

## 2. Files, imports and names

- UTF-8 files with suffix `.vernac`; conventional root `src/main/vernac`.
- Every file begins `namespace a.b;` and resides under `a/b/` relative to that root.
- Next come zero or more `import a.b.Type;` or `import a.b.*;` statements.
- Imports name Vernac namespaces/types, never a source filename, Java class or
  generated `.domain` package. No alias syntax. No Java imports at file scope.
- Same-namespace declarations in all files are visible without imports. Importing
  your own namespace or its types is redundant and rejected.
- Declaration order and filenames do not determine visibility. All project files
  participate in resolution. An unresolved/ambiguous name is an error, never a
  request to guess a Java class.
- Fully qualified Vernac names resolve known types; they do not permit arbitrary
  fully qualified Java classes in modeled fields.
- Names are case-sensitive Java-compatible Unicode identifiers. No Unicode
  normalization or transliteration. Invisible/control/format characters are not
  allowed in identifiers. Java keywords remain invalid where Java forbids them.
  Vernac keywords can be namespace segments when valid Java package segments.
- Built-in type names are reserved against model type declarations. Generated and
  inherited Java API collisions are errors; no automatic renaming/numbering.
- Technical generated namespaces/types are compiler-owned. Never name handwritten
  helpers like compiler companions or depend on `__Vernac...` implementation names.
- There is no per-definition custom Java package.

Omitted names derive from the simple type name, identically for fields, parameters,
results and dependencies. Primitive `int` becomes `intValue` (likewise `byteValue`,
`booleanValue`, etc.). References lowercase the initial uppercase run, retaining
its last uppercase letter when followed by lowercase: `Title` → `title`, `URLValue`
→ `urlValue`, `URL` → `url`, `HTML2XMLMapper` → `html2XMLMapper`. No dictionary.
`String` → `string`; `BigDecimal` → `bigDecimal`. Optionality does not change names.
There is **no single-value `value` default**. Supply explicit names for repeated
types or collisions, including different types deriving the same field name.

An ID used as an ordinary field follows these rules. The identity in `[TaskId]`
always exposes `id()`, not `taskId()`.

## 3. Modeled types

Built-in field catalog (simple names, no imports):

| Category | Exact names |
| --- | --- |
| Primitive | boolean, byte, short, int, long, float, double, char |
| Boxed | Boolean, Byte, Short, Integer, Long, Float, Double, Character |
| Other values | String, BigInteger, BigDecimal, UUID, Currency |
| Time | Instant, LocalDate, LocalTime, LocalDateTime, OffsetDateTime, OffsetTime, ZonedDateTime, Duration, Period, Year, YearMonth, MonthDay, Month, DayOfWeek, ZoneId, ZoneOffset |

A class being immutable does not add it to this catalog. Path and URI are adapter
configuration types, not ordinary domain-field built-ins. Object, Number, Date,
arrays, Java List/Set/Map and Optional are not domain state types.

| Position | Allowed modeled types |
| --- | --- |
| VO field | Catalog, ID, enum, VO, declared collections of immutable domain values |
| Entity/aggregate field | ID, enum, VO, entity, eligible declared collections |
| Event payload | ID, VO, enum, value collections; runtime EventId also supported |
| Usecase input/result, port input/result | ID, VO, enum, value collections |
| Service operation | Domain values, entities/aggregates/collections, approved built-ins |
| DTO schema | Catalog scalars, local schemas, `list<T>`; no domain objects |

Entities/aggregates cannot directly hold built-in state: wrap it in a VO.
An aggregate cannot be contained in any entity/aggregate, including via collections.
Use its ID. An entity cannot appear in a VO, including via collections. Cyclic
containment is forbidden; an ID reference is not containment. Domain collections
must be declared explicitly; writing `List<Title>` does not create one.

`T?` denotes optionality, not Java's `Optional<T>` syntax. Optional primitives are
invalid in domain fields/signatures: use `Integer?` or an optional VO. DTO schemas
have their own boxing rules. All generated types use JSpecify `@NullMarked`.

## 4. Declaration shapes and clause order

Notation: brackets below mean optional syntax, braces mean repetition; these are
metanotation, not literal source. `params` is comma-separated `[mut] Type[?] [name]`;
`mut` is allowed only for entity/aggregate fields, not general parameters.

```text
id Name [(list | set) [CollectionName] [behavior {...}] {, (list | set) [CollectionName] [behavior {...}]}];
value Name(params) [validates {...}] [behavior {...}] [list/set clauses];
value Name = CONSTANT { | CONSTANT } [behavior {...}] [list/set clauses];
entity Name[IdType](params) [validates {...}] [behavior {...}] [list/set clauses];
aggregate Name[IdType](params) [validates {...}] [behavior {...}] [list/set clauses];
[recorded] event Name(params) [validates {...}];
```

`list/set clauses` is explanatory notation for the same comma-separated suffix
shown on id, never literal Vernac. The old `collection` keyword is forbidden.
Use semicolons as shown even where a declaration terminator is optional. A type
can declare multiple lists and sets, including several of the same kind. Each
clause has its own optional name and behavior. Default collection name is exact
`ElementName + s`: `Categorys`, not guessed `Categories`. For example
`value Wert(String) list, set Werte;` produces Werts and Werte. `list, set` with both
names omitted collides and is rejected. No numbering or automatic selection occurs.
Repository queries select their declared return collection, not the first suffix.

## 5. Generated domain Java API

| Definition | Construction | Access/equality |
| --- | --- | --- |
| ID | `create()`, `of(UUID)`, `of(String)` | `value(): UUID`, `asString()`, UUID equality |
| Ordinary VO | `of(all fields)` | Record-style getters; equals/hashCode over all fields |
| Enum VO | `Status.OPEN` | Standard Java enum API and equality |
| Entity | `create(fields)`, `reconstitute(id, fields)` | `id()`; equality by identity |
| Aggregate | `create(fields)`; technical reconstitution API | `id()`, `createdAt()`, `updatedAt()`; equality by identity |
| Event | `create(payload)`, `reconstitute(EventId, Instant, payload)` | `eventId()`, `occurredAt()`; occurrence identity |

Constructors are private; VOs are classes, not records. All provide toString.
IDs have fixed UUID state, no custom behavior/validation. A VO requires at least
one field, cannot use mut and has no setters. An enum has no extra fields,
constructors, per-constant body, validates or dbValue. Uppercase enum spelling is
recommended, not mandatory. An event may be fieldless; it has no behavior block.

Full factories accept nullable T for optional fields; stored optional state is
exposed as Optional<T>. VOs with both required and optional fields add a required-only
of overload. All-optional VOs have only their full factory, no automatic of().
Event convenience create omits optional payloads, including the all-optional case.
Entity/aggregate create never accepts an ID: it calls the ID factory internally.
Do not guess aggregate reconstitute metadata order; inspect its generated API when
writing custom persistence. Ordinary application code uses create and repositories.

`value Title(String);` → `Title.of("A").string()`, not `.value()`/`.asString()`.
`value Title(String value);` → `.value()`. Ordinary VOs have no asString/asUuid alias.
Equality preserves Java semantics: BigDecimal scale matters; floating signed zeros
are distinct and NaNs compare according to the wrapper equality contract.

## 6. Behavior, self, validation and optional arguments

VO/enum/collection behavior:

```text
behavior {
    [java imports { fully.qualified.JavaType; ... }]
    public Result operation(Type parameter) { Java statements }
    public Result operation(Type parameter) implemented by example.JavaClass;
    private Result helper(Type parameter) { Java statements }
}
```

Entity/aggregate behavior replaces public with **read** or **modify**. Do not write
`public modify`. Read gets a generated Read view; modify gets Access (Read + Write).
A mutable field setter is `self.field(newValue)` inside modify; no public setter
is generated on the entity. Read views are shallow: returned child entities remain
ordinary objects. They are not a deep immutability/security mechanism.

Public implementations receive self implicitly. Java bodies use `self.field()`
and `self.otherOperation(...)`, not `this`, bare field access or magical getter
rewriting. Private helpers are static, have no implicit self and receive everything
explicitly. Call a private helper by its name. No user fields/constructors/method
annotations/protected methods in behavior. Java imports precede methods and use
`java imports { java.util.Locale; }`, without `import` inside the block; no wildcard
or static-import syntax. Imports cannot change modeled signature types or shadow
protected modeled names.

External implementations provide a **public static method** with the declared
operation name, self as first parameter and remaining parameters in declaration
order. VO self is the VO; entity/aggregate self is the appropriate Read/Access
interface. Services/adapters/usecases/listeners use their own generated Access.
Private helpers cannot use implemented by. The external class is not instantiated
or injected by this mechanism; javac checks its signature.

Nullness differs between API and implementation; do not generalize one context:

| Position | Optional input representation |
| --- | --- |
| Public factories and public operation arguments | `@Nullable T` |
| VO/enum/collection/entity/aggregate behavior arguments, including helpers | Nullable T |
| Usecase/service/adapter implementation arguments | Optional<T> |
| Validation self getters and domain optional getters | Optional<T> |
| Optional method results | Non-null Optional<T> |

Return Optional.empty(), never null, for optional results. Required null or null
Optional results violate BehaviorContractException. For optional calls from an
Optional implementation parameter into a nullable public API, explicitly use
`argument.orElse(null)`. Do not supply Optional to a factory expecting nullable T.

Validation clause: `validates { require(condition, "message"); ... }` before
behavior. Conditions use **self getters**, not bare field/parameter names. Operators
are `!`, `&&`, `||`, `==`, `!=`, `<`, `<=`, `>`, `>=`, `+`, `-`, `*`, `/`, calls,
member access, literals and parentheses. Use parentheses for intended grouping.
This is a limited expression grammar: no Java statement blocks, lambda/new/ternary
expressions or arbitrary Java programs in require. Delegate complex pure work to
a suitable read operation where supported. Supply explicit messages.

Required null checks run before invariants. Factories/reconstitute validate.
Mutable behaviors validate on successful outermost completion, including nested
participants. Mutations are live: exceptions do **not** restore fields or metadata.
A DB rollback does not restore Java objects. Validators must not have side effects;
the Read API is a design boundary, not a proof of Java purity.

## 7. Collections

Named collections are final classes, not Java List/Set implementations. Factories
copy structure, reject null containers/elements and keep element references.
Methods do not mutate collection structure; reassign returned collections explicitly.

| API | Contract |
| --- | --- |
| `C.empty()`, `C.of(T...)`, `C.of(Iterable<? extends T>)` | Explicit construction |
| `iterator()`, `stream()`, `size()`, `isEmpty()` | Read access |
| `asList()` / `asSet()` | Only on respective kind; unmodifiable view |
| `contains(T)`, `count(T)`, `find(T)` | Equality membership, int count, Optional first instance |
| `plus(T)`, `plusAll(Iterable)` | Same collection type; original unchanged |
| `minus(T)` | First equal occurrence removed |
| `minusAll(T)`, `minusAll(Iterable)` | All matching source occurrences removed |
| `filter(Predicate)`, `matching(T)`, `matching(Iterable)` | Same collection type |
| list `get(int)`, `first()`, `last()` | Element / Optional / Optional |
| list `distinct()` | First representative per equality group |
| list `duplicates()` | ALL occurrences of every repeated group, including first |
| entity/aggregate collection `by(Id)`, `contains(Id)` | Optional first instance / boolean |
| entity/aggregate collection `minusId(Id)`, `minusAllId(Id)` | Remove first/all matching IDs |

`[A,B,A].duplicates()` is `[A,A]`; `.duplicates().distinct()` is `[A]`.
There is no collection `.map(...)`: use `Destination.of(source.stream().map(...).toList())`.
Sets keep the first equal instance; plus never replaces it with a newer entity.
Set equality ignores order; lists include order and multiplicity. Mutable elements
are not deep-copied. ID collections use ordinary find/contains, not by(UUID).

## 8. Repositories and declarative searches

```text
repository [Name] for AggregateName {
    find AggregateCollection method(params) [where predicate] [order by field [asc|desc], ...];
    find AggregateName? method(params) [where predicate];
}
```

Default name: AggregateNameRepository. Explicitly declare a result collection on
the aggregate. byId(id) returns a whole aggregate or throws AggregateNotFoundException;
save(aggregate) persists its complete graph with optimistic locking and returns
the aggregate; delete(aggregate) removes it. These operations require an existing transaction (MANDATORY). There is no automatic repository per aggregate.

A find result is a declared aggregate list/set or an optional singleton. An optional
singleton with >1 match throws NonUniqueQueryResultException; it does not establish
a unique database constraint. No method-name-derived predicates, projections,
custom SQL declarations, pagination or implicit collection definitions.

Predicates: `field = :parameter`, `!=`, `<`, `<=`, `>`, `>=`, `like`, `contains`,
`starts with`, `ends with`, `field is absent`, `field is present`. Combine with
`not`, `and`, `or`, parentheses (precedence in that order). RHS comparisons use typed
parameters, not inline constants. Text pattern operations accept String parameters;
contains/starts/ends are literal text, while like uses `%`/`_` pattern semantics.
Fields are eligible aggregate-root scalar/required single-value chains, plus id,
createdAt and updatedAt. Do not use arbitrary dotted navigation, collection joins
or multivalue-object comparison. Operator availability depends on scalar capability.

Ordered results require list, not set. A list without order generates a warning;
equal sort keys have unspecified relative order. Root search order is unrelated
to persisted child-list positions. Absent fields do not match ordinary nonoptional
comparison parameters, including !=; use explicit absence predicates. Equality
preserves the supported scalar's Java equality semantics; ordering is separately
specified and is not a promise of Java ordering for every type.

## 9. Usecases and services

```text
usecase Name(params) [returns Type | returns (resultFields)]
    [uses Dependency [name], OtherDependency [name]]
    [validates {...}]
behavior {
    [java imports {...}]
    execute { Java statements }
    // OR execute implemented by example.JavaClass;
    [private helpers]
}
```

One execute, no repeated signature or public modifier. Omitted returns means void;
`returns T?` means Optional<T>; tuple returns generate nested `Name.Result`, accessible
as Result in the implementation. Return `Result.of(...)`, not tuple syntax. The
record's optional components are Optional<T>; of accepts nullable T and has a
required-only overload (including of() for all-optional result tuples). Canonical
record construction requires Optional components. Inputs/results cannot be raw
scalars or mutable graphs. Wrap them in domain values.

uses is comma-separated, **without parentheses**. Dependencies are repositories,
ports, services and eligible other usecases, not DTOs or concrete adapters. self
exposes dependencies and emit, not usecase input getters in execute: use local
parameters there. In validates, self instead exposes input getters only.
Usecases are Spring beans with REQUIRED transactions; nested proxied calls join.
External execute signature: static Return execute(NameAccess self, inputs...).
Java application calls: `bean.execute(...)`. Do not construct Spring dependencies
inside the Java body; rely on declared injection.

Services: `service Name [uses ...] behavior { public T method(params)
[validates {...}] { Java } }`. Public named operations, private helpers, no execute
special case and no read/modify modifiers. Service self exposes dependencies,
operations and emit. Per-operation validation self contains only input getters.
Service methods can accept entities and aggregates and coordinate their behavior.
Services do not open transactions. External method delegation is the same static
self-first protocol. No custom service state or field-list constructor syntax.

## 10. Events/listeners

`event Completed(TaskId);` or `recorded event Completed(TaskId);`.
Fields are values, not live aggregates. Emit explicitly through self.emit(event).
Saving an aggregate does not implicitly emit. Usecases establish the transaction
and EventContext; services/modify behavior/listeners can participate. Read behavior,
VO behavior and validators cannot emit. A service invoked outside the necessary
transaction/context cannot emit successfully just because its Access has emit.

```text
(synchronous | deferred) listener Name on EventType [uses ...] behavior {
    [java imports {...}]
    execute { Java using event and self }
    [private helpers]
}
```

Exactly one execute (or external static execute(NameAccess self, Event event)).
Synchronous runs immediately in the current transaction. Deferred creates a durable
per-listener delivery and runs after commit in a worker transaction. Handlers may
invoke usecases. Multiple listeners are allowed; do not assume their ordering.
Events are stored when recorded or needed by deferred listeners. No memory/outbox
modifier on event, no delivery mode on emit. Event evolution and reachable VO
changes need deliberate compatibility decisions. External effects can repeat;
no automatic exactly-once promise or rollback of HTTP/mail effects.

## 11. Ports, Java-backed adapters and configuration

```text
port Name { ReturnType operation(params); }
adapter AdapterName implements PortName [uses ...] {
    [configuration { ConfigType field from "property.key"; ... }]
    [schemas/mappings]
    behavior {
        [java imports {...}]
        [java uses { fully.qualified.JavaType dependency; ... }]
        public ReturnType operation(params) { Java using self }
        // OR public ReturnType operation(params) implemented by example.JavaClass;
        [private helpers]
    }
    [schemas/mappings]
}
```

Adapters implement the entire port; public signatures must match. Exactly one
behavior or rest implementation block. Domain uses dependencies and technical
java uses dependencies are accessed via self. `implemented by` is per operation,
not an instruction to inject an implementation instance. Provide a static self-first
method. Runtime selection among multiple adapter beans is explicit application
Spring configuration; there is no implicit winner or DSL profile selector.
Adapters/ports do not open transactions or automatically retry.

Configuration types: String, URI, Duration, Path, int, long, boolean. Entries need
explicit names and quoted property keys; values are required at startup. Access
with self.field(). Do not put credentials or fixed deployment URLs in source.
Configuration comes before schemas/mappings and implementation. No default/optional
property syntax is implied by this contract.

## 12. DTOs and mappings

Adapter-local `schema Payload [naming snake_case] (fields);` creates nested public
Java records under `<namespace>.adapter.outbound.dto.<Adapter>Dtos`.
Fields are `Type [name] [named "wire-name"] [absent as null]`, optionally prefixed
`patch`. Technical arrays are `list<T>`, not `List<T>`; elements cannot be optional.
Local schemas may nest; cycles are forbidden. They cannot escape into domain port
signatures. Names and paths in mappings refer to source member names, not wire names.

Required fields reject missing/null/wrong JSON types. Unknown fields are ignored.
Ordinary optional fields use Optional components, nullable factory inputs and are
omitted when absent unless `absent as null` is specified. PATCH uses PatchField<T>:
unchanged() omits, clear() writes null, set(value) writes a value. `patch T?` allows
clear; `patch T` does not. Factory inputs are explicit nonnull PatchFields. No
implicit merge, mutation or optional-to-patch conversion. Schemas expose full of
factories, not an assumed family of omitted-field overloads.

```text
mapping name: Target <- Source {
    targetField <- source.sourceField [using nestedMapping];
}
mapping name: Target <- Source {
    [java imports {...}]
    java { return explicitConversionOfSource; }
}
mapping name: Target <- Source implemented by example.JavaClass;
```

Mappings are adapter-local, have nonoptional roots and no overloads or cycles.
Every required target field must be assigned; unassigned optional/PATCH targets
become absent/unchanged. Use immediate target fields, never dotted target assignment.
Source paths start source; no method calls/indexing in declarative paths. Optional
source paths cannot satisfy required targets. All-declarative or all-Java body.

Exact single-value wrapping/unwrapping (including IDs) is automatic; no guessed
units, enum codes, string parsing, defaults or numeric widening. Nested multivalue
and element transformations use named mappings. Whole-field mapping matching takes
precedence over element mapping matching. No implicit list-to-set conversion.
Scalar/enum/collection root targets need Java mappings. Declarative targets are DTOs,
IDs or ordinary VOs. Domain factories enforce their validations.

Java mapping has only source, **no self** or injected collaborators. External
signature: public static Target name(Source source). Generated callable holder:
`<namespace>.adapter.outbound.mapping.<Adapter>Mappings.name(source)`. Generated
DTO/mapping code requires matching vernac-runtime-json. Known contract failures
are MappingContractException with paths; do not use mapping for orchestration.

## 13. Declarative REST

Replace the adapter behavior with:

```text
rest {
    client http;
    base baseUri;
    ReturnType operation(params) {
        GET "/items/{id}";
        path "id" <- parameter;
        [query "wireName" <- parameter.field;]
        [header "Header-Name" <- parameter.field;]
        [body <- parameter using requestMapping;]
        [response LocalSchema using responseMapping;]
        [on 404 absent;]
        [errors {...} | errors implemented by example.JavaClass;]
    }
}
```

Preserve the displayed clause order. Verbs: GET/POST/PUT/PATCH/DELETE. No GET body.
Value results require a DTO response mapping; void omits it. Body mapping targets a
local DTO. `on 404 absent` is explicit and legal only for optional results. It is
not automatic; other status codes cannot be declared absent. Optional query/header
values are omitted. Path/body must be present. Route placeholders and bindings
must match exactly. Paths are relative, without URL/query/fragment/..; runtime encodes
values and retains the base URI path. No implicit enum/collection wire conversion.

client names an injected HttpAdapterClient; base selects a URI configuration member
(or legacy URI bean if no configuration member exists). Prefer configuration. Add
matching vernac-runtime-http and provide its required client bean according to the
application setup; do not assume a declaration alone supplies every Spring bean.

Declarative non-2xx rules:

```text
errors {
    [response LocalErrorSchema;]
    when status = 422 and response.code = "UNSUPPORTED"
        then reject DeclaredRejection.UNSUPPORTED "Unsupported request";
    when status >= 500
        then fail remote REMOTE_FAILURE "Remote service unavailable";
    otherwise default;
}
```

Business codes must be declared enum constants. Technical categories must come from
the runtime's supported catalog: CONFIGURATION, INVALID_REQUEST, INVALID_RESPONSE,
AUTHENTICATION, AUTHORIZATION, RATE_LIMITED, REMOTE_FAILURE, UNEXPECTED_STATUS,
TIMEOUT, COMMUNICATION, INTERRUPTED. Responsibility is local/remote/unknown. It is an explicit application
interpretation, not a deduction from status alone. Status integer comparisons,
string/boolean equality, exact integral/decimal comparisons and presence predicates
are supported; logical not/and/or and parentheses apply. Messages are fixed strings.
First matching TRUE rule wins. Missing/malformed response DTOs yield unavailable
body predicates, not a match under not. With on 404 absent, declarative error rules
must exclude 404. Successful invalid payloads are errors, not Optional.empty().
External error method returns a VernacException: static operation(AdapterAccess self,
inputs..., HttpResponseData response). It runs for non-2xx after the 404 shortcut.

## 14. Java packages, tooling and unsupported assumptions

| Artifact | Generated package |
| --- | --- |
| ID/VO/enum/entity/aggregate/collection/event/service/port/repository | namespace.domain |
| Usecase | namespace.usecase |
| Mutable/service access views | namespace.domain.access |
| Usecase access views | namespace.usecase.access |
| Outbound adapter / access / DTOs / mappings | namespace.adapter.outbound[.access/.dto/.mapping] |
| JDBC repository | namespace.adapter.outbound.jdbc |
| Listener | namespace.adapter.inbound.event |

Use Maven compilation for whole projects, not parser-only success. JDK 25 builds
the current tooling; emitted application code targets Java 21. Matching runtimes:
vernac-runtime for domain support, runtime-json for DTO/mapping, runtime-http for
REST, runtime-jdbc/starter for persistence. Supply Spring/application dependencies.
Generated Java is not handwritten source: change the model or an external delegate.

### 14.1 Relational mapping: use the generated model as the authority

Persistence is PostgreSQL/JDBC, not JPA. An explicit repository enables storage of
its aggregate and the complete reachable entity graph. No lazy loading or automatic
repository per aggregate. Domain reconstitution still checks invariants.

| Modeled element | Mapping convention |
| --- | --- |
| Namespace and aggregate | SQL schema is the exact namespace; root table is the exact aggregate name, e.g. `"org.example.delivery"."Tour"` |
| SQL identifiers | Always quoted; no snake_case conversion, case folding or Unicode normalization. A dot inside `"price.amount"` belongs to one identifier |
| Single-value VO field | Reuses its containing field path: `Title(String)` used as `Title title` becomes `"title"`, not `"title.string"` |
| Multi-value/nested VO | Append member names recursively: `Money(BigDecimal amount, Currency currency)` at `price` becomes `"price.amount"`, `"price.currency"` plus scalar components |
| Optional VO | Nullable representation; a required scalar leaf witnesses presence. If all leaves can be null, a `@present:<path>:<qualified-VO>` marker distinguishes absence from a present all-null value |
| Root identity and metadata | `"id"` UUID, `"@version"` BIGINT, `"@createdAt"` and `"@updatedAt"` timestamps plus nanosecond remainders |
| Entity state | One `<Root>.@entity:<qualified-Entity>` table per root/entity type, in the root namespace's SQL schema; primary key `("@aggregateId", "id")` |
| Direct entity field | Entity UUID under its field name; aggregate-scoped composite FK to the entity state table |
| Entity collection | Separate relationship table `<owner-state-table>.<field>`; root relationships contain `@aggregateId`, `@entityId`; entity-owned relationships also contain `@ownerId` |
| Entity list | Adds zero-based `@position`; primary key is owner columns plus position. Repeated references are allowed. Load explicitly orders by position |
| Entity set | No position; primary key is owner columns plus entity ID; no ordering promise |
| Value collection | Separate containment-path table with `@ownerId`, technical `@rowId` and flattened value columns; lists add `@position`. Entity-owned value collections also carry `@aggregateId`; deeper value rows reference their owner's `@rowId` |

Optional collections distinguish absent from present-but-empty through a presence
marker. Multiple paths to an entity within one loaded aggregate return the same
Java instance. Save synchronizes entity state and relationships; an entity is removed
only after its last reachable reference disappears. It does not indiscriminately
replace every child graph. Value-collection rows may be replaced as a unit.

Technical names start with `@`, unavailable to user-defined fields. Identifiers over
63 UTF-8 bytes are shortened deterministically: longest complete-codepoint prefix
fitting 30 bytes, then `~` and the first 32 lowercase SHA-256 hex digits of the full
UTF-8 name. Collisions fail generation. **Read the generated schema; do not hand-guess
physical names**, especially for long names, nested values and technical components.

Scalars are not all single SQL columns. BigDecimal uses finite NUMERIC plus
`@scale:<path>` to preserve Java equality. Timestamp/time types store microseconds
plus remaining nanoseconds; zone-bearing types preserve additional offset/zone data.
Other multi-component values use named technical columns. Enum TEXT codes live in
schema metadata: initial codes match constants; subsequent generation retains recorded
codes. Explicit `vernac.enumCodes` mappings support deliberate evolution. Never use
ordinals or add domain `dbValue()` methods. Database/text/date representability limits
still apply; consult the project's scalar-storage contract before choosing edge values.

The root version protects the **whole graph**, not each entity separately. Fresh
objects start at version 0; insertion persists version 1. An update checks the expected
`@version`, increments it and fails on a stale snapshot before child writes. A missing
existing root is not recreated via INSERT fallback. All repository operations require
a surrounding transaction (MANDATORY); usecases provide REQUIRED transactions.
A failed transaction does not undo mutations to the Java objects. Structural DB
constraints protect the representation, not arbitrary Java `validates` expressions.

### 14.2 Migration lifecycle

The versioned **source-controlled schema snapshots** are the diff baseline. Migration
generation does not diff against an arbitrary running application database and does
not infer schema evolution merely by parsing old SQL.

Default locations (unless the project's plugin configuration overrides them):

| Artifact | Path / format |
| --- | --- |
| Input model | `src/main/vernac/**/*.vernac` |
| Expected schema preview | `target/vernac-schema/schema.json` and `schema.sql` |
| Versioned schema history | `src/main/vernac-schema/history/V<version>__<description>.json` |
| Flyway candidates | `src/main/resources/db/migration/V<version>__<description>.sql` |

Use the version configured for `org.vernac:vernac-maven-plugin` in the application POM.
The commands below assume its `vernac` prefix resolves there. In a terminal include
`mvn`; in IntelliJ's Maven command field enter the goals/options as that UI requires.
Quote the entire dotted `-D...` argument in **PowerShell**; the unquoted examples below
are Maven arguments for IntelliJ/POSIX shells.

1. Inspect the target model with `mvn vernac:schema`. It writes expected JSON and
   complete initial-DDL **preview** under `target`; it changes neither the database
   nor source history. Do not copy this full-schema preview into the next incremental
   migration of an existing application.
2. Generate the first candidate with
   `mvn vernac:migration -Dvernac.migrationDescription=initial_schema`.
   After changing the model, use the same goal with a new description, for example
   `mvn vernac:migration -Dvernac.migrationDescription=add_dispatch_reference`.
   It compares the last snapshot (or empty model initially) with the current target
   and writes a **new SQL candidate and matching JSON snapshot**. No change means no
   new files. The default version is UTC `yyyyMMddHHmmssSSS`, not an epoch count;
   descriptions use lowercase words separated by underscores.
3. Review the SQL, including order, existing data, locking, deployment compatibility,
   backups and recovery. The application developer alone is responsible for testing
   and approving it. Neither generation nor automatic verification guarantees safety.
4. Run `mvn vernac:schema-check` using `VERNAC_CHECK_URL`, `VERNAC_CHECK_USER` and
   `VERNAC_CHECK_PASSWORD` supplied securely for a **disposable PostgreSQL server**.
   The role needs CREATEDB. The goal creates two temporary databases: one receives
   all Flyway migrations; the other receives the expected complete DDL. It compares
   Vernac-owned schema objects and removes those databases afterwards. Foreign
   application tables are ignored. It also requires the current model to match the
   latest snapshot. It does **not** test production data, backfill correctness on
   populated tables, operational duration or rolling-deployment compatibility.
5. Separately exercise migration of representative existing data and the application
   behavior. Commit the model, SQL, snapshot and any transformation specification
   together. Deploy through the application's configured Flyway integration, e.g.
   Spring Boot's Flyway startup migration against the application datasource.
   Vernac's `schema` and `migration` goals themselves never apply application SQL.

Adding a required column to existing rows requires an explicit SQL backfill. Supply
`-Dvernac.transformations=path/to/transformations.json`, whose shape is
`{"backfills":{"<namespace>/<table>/<column>":"<SQL expression>"},"conversions":{}}`.
Use generated logical identities for keys and trusted, reviewed SQL expressions.
The generator can then add a nullable column, fill existing rows, and set NOT NULL.
It must not invent a domain-valid value. Potentially destructive changes normally
abort; `-Dvernac.force=true` only allows candidate generation to continue through
recognized risks. It neither supplies missing transformations nor approves/runs SQL.

**Example evolution:** after the initial `Tour` migration has been recorded, define
`value DispatchReference(String);` and append `DispatchReference? reference` to Tour's
fields. Generate `add_dispatch_reference`. Its schema delta adds
`ALTER TABLE "org.example.delivery"."Tour" ADD COLUMN "reference" TEXT;` (with the
standard review notice), plus the new snapshot. It does not recreate Tour, require a
backfill for this optional field, or add tautological presence checks for its single
nullable column. Update handwritten create/reconstitute calls as required by the
new generated API. Test both absent historical values and newly populated references.

Never overwrite/delete an applied or published migration to make a build green.
Do not manually recalculate snapshot fingerprints or merge divergent snapshot chains.
Rebase and regenerate **unpublished** candidates against the accepted history; once
released/applied, use forward migrations and coordinate deployment. A type/field rename
may appear as drop/add: the tool cannot infer data-preserving intent. Inspect that
candidate rather than accepting it as a safe rename. Keep non-Vernac tables separate;
do not casually extend owned tables outside their model. Git rollback does not roll
back Flyway or the database. Reset only explicitly disposable demo data when repeating
an exercise, never a real environment as a migration shortcut.

### 14.3 Unsupported assumptions

Do NOT generate these as supported language:

- `package` headers/overrides, Java imports at file scope, annotations on model fields.
- Old `collection`, bare entity Java method blocks, `use`, magic load/save/emit statements.
- `new Title(...)`, `Task.create(id, ...)`, implicit value(), ordinary VO asString().
- Generic domain List/Map, built-ins as aggregate fields, aggregate-valued VO fields.
- Unspecified public usecase methods, usecase Result inferred from Java, uses(...).
- `permission`, `requires`, entitlement/quota/subscription declarations.
- Module export declarations inside `.vernac` sources; exports belong in `.vmodule`.
- Generated MCP/Kafka/RabbitMQ endpoints, automatic async ports.
- Database rollback restoring object state or guaranteeing rollback of remote effects.

Module usecases and domain services support explicit activation. Module-owned
entity/aggregate/repository contracts can be published and imported. Persistent
activation, coordinated migrations, module events and synchronous/deferred receivers
are supported. See the module sections below for their exact syntax and limits.

Obsolete entity/package/custom-repository productions have been removed from the
0.3.0 compiler. Parsing still does not validate embedded Java: verify the current
project compiler and generated API, not just syntax acceptance.
The compiler cleans up its owned generated Java sources, not arbitrary .class/.jar
files; use Maven clean after removing types to eliminate stale bytecode.

## 15. One-shot example: complete task application model

**Request:** Model tasks with validated titles, completion, persistence, a search,
a creation usecase and a completion event. Return a task ID when creating.

**Answer:** `src/main/vernac/ai/tasks/tasks.vernac`

```vernac
namespace ai.tasks;

id TaskId;
value Title(String) validates {
    require(!self.string().isBlank(), "Title must not be blank");
};
value TaskState = OPEN | COMPLETED;
event TaskCompleted(TaskId);

aggregate Task[TaskId](Title, mut TaskState) behavior {
    modify void complete() {
        self.taskState(TaskState.COMPLETED);
    }
} list Tasks;

repository TaskRepository for Task {
    find Tasks withState(TaskState state)
        where taskState = :state order by title;
    find Tasks search(String text)
        where title contains :text order by title;
}

usecase CreateTask(Title) returns TaskId uses TaskRepository tasks behavior {
    execute {
        var task = Task.create(title, TaskState.OPEN);
        self.tasks().save(task);
        return task.id();
    }
}

usecase CompleteTask(TaskId) uses TaskRepository tasks behavior {
    execute {
        var task = self.tasks().byId(taskId);
        if (task.taskState() != TaskState.COMPLETED) {
            task.complete();
            self.tasks().save(task);
            self.emit(TaskCompleted.create(task.id()));
        }
    }
}

synchronous listener ObserveCompletion on TaskCompleted behavior {
    execute { System.out.println("Task completed: " + event.taskId().asString()); }
}
```

Java callers inject CreateTask/CompleteTask beans and call execute. The log is an
illustration, not a transactional effect. Application startup still needs Spring,
JDBC, a transaction manager and reviewed schema/migrations. No web endpoint is
implicitly generated. Completing an already completed task emits no new event.

## 16. Few-shot pair A: names, optionals and cross-file imports

**Request:** Define a person name with uppercase display, an optional nickname,
and several explicit collections. Reference it from another namespace.

**Answer, file 1:** `src/main/vernac/ai/people/names.vernac`

```vernac
namespace ai.people;

value PersonName(String text) validates {
    require(!self.text().isBlank(), "Name must not be blank");
} behavior {
    java imports { java.util.Locale; }
    public String upper() { return self.text().toUpperCase(Locale.ROOT); }
    public int length() implemented by ai.helpers.NameBehavior;
} list PersonNames, set UniquePersonNames, list ArchivedPersonNames;

value PersonProfile(PersonName legalName, PersonName? nickname);
```

**Answer, file 2:** `src/main/vernac/ai/welcome/welcome.vernac`

```vernac
namespace ai.welcome;
import ai.people.PersonProfile;

value Greeting(String);
usecase Greet(PersonProfile) returns Greeting behavior {
    execute {
        var name = personProfile.nickname().orElse(personProfile.legalName());
        return Greeting.of("Hello " + name.upper());
    }
}
```

**Required handwritten Java:** `src/main/java/ai/helpers/NameBehavior.java`

```java
package ai.helpers;

import ai.people.domain.PersonName;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class NameBehavior {
    private NameBehavior() {}
    public static int length(PersonName self) {
        return self.text().length();
    }
}
```

`PersonProfile.of(name)` and `PersonProfile.of(name, null)` are valid. nickname()
returns Optional. There is no direct `personProfile.nickname().upper()`.

## 17. Few-shot pair B: contained entities, service and Result

**Request:** A route contains stops. Add a stop and return its identity and label.
Demonstrate a service that inspects the route without loading it itself.

**Answer:** `src/main/vernac/ai/routes/routes.vernac`

```vernac
namespace ai.routes;

id RouteId;
id StopId;
value Label(String);
entity Stop[StopId](Label) list Stops;

aggregate Route[RouteId](mut Stops) behavior {
    modify void add(Stop stop) { self.stops(self.stops().plus(stop)); }
    read int stopCount() { return self.stops().size(); }
} list Routes;

repository RouteRepository for Route {}

service RouteInspection behavior {
    public int count(Route route) { return route.stopCount(); }
}

usecase AddStop(RouteId, Label) returns (StopId, Label)
uses RouteRepository routes
behavior {
    execute {
        var route = self.routes().byId(routeId);
        var stop = Stop.create(label);
        route.add(stop);
        self.routes().save(route);
        return Result.of(stop.id(), stop.label());
    }
}
```

A Route is created with `Route.create(Stops.empty())`, never with a supplied ID.
`new Stop(...)` and `route.stops().add(stop)` are invalid APIs. The service count
operation does not open a transaction; the usecase does.

## 18. Few-shot pair C: optional return and logical search

**Request:** Search tasks not completed, or matching text, with explicit multi-key
ordering; also look up an optional singleton by title.

**Answer:** `src/main/vernac/ai/search/search.vernac`

```vernac
namespace ai.search;

id EntryId;
value Caption(String);
value EntryState = OPEN | CLOSED;
aggregate Entry[EntryId](Caption, EntryState) list Entries;
repository EntryRepository for Entry {
    find Entries select(EntryState state, String text)
        where not (entryState = :state) or (caption contains :text)
        order by caption asc, createdAt desc;
    find Entry? titled(Caption caption) where caption = :caption;
}
usecase CaptionFor(EntryId) returns Caption uses EntryRepository entries behavior {
    execute { return self.entries().byId(entryId).caption(); }
}
```

The ordering is explicit but ties in both keys remain unspecified. titled may throw
if multiple entries share a caption. This declaration adds no uniqueness constraint.
An optional singleton cannot be returned directly from a usecase because it is an
aggregate; return a value or a declared Result projection instead.

## 19. Few-shot pair D: outbound REST with nested ACL and error policy

**Request:** Request a shipping quote over HTTP, using a configured URL, DTOs and
explicit mappings. Treat a particular 422 as a domain rejection.

**Answer:** `src/main/vernac/ai/shipping/shipping.vernac`

```vernac
namespace ai.shipping;

value Destination(String);
value Weight(BigDecimal);
value Shipment(Destination destination, Weight weight);
value Reference(String);
value CurrencyCode(String);
value Price(BigDecimal amount, CurrencyCode currency);
value Quote(Reference reference, Price price);
value ShippingRejection = UNSUPPORTED;

port Shipping {
    Quote quote(Shipment shipment);
}

adapter ShippingHttp implements Shipping {
    configuration { URI baseUri from "shipping.base-url"; }
    rest {
        client http;
        base baseUri;
        Quote quote(Shipment shipment) {
            POST "/quotes";
            body <- shipment using toRequest;
            response QuotePayload using toQuote;
            errors {
                response ErrorPayload;
                when status = 422 and response.code = "UNSUPPORTED"
                    then reject ShippingRejection.UNSUPPORTED "Destination unsupported";
                when status = 401
                    then fail local AUTHENTICATION "Check configured credentials";
                otherwise default;
            }
        }
    }
    schema RequestPayload naming snake_case (String destination, BigDecimal weightKg);
    schema QuotePayload(String reference, PricePayload price);
    schema PricePayload(BigDecimal amount, String currency);
    schema ErrorPayload(String code);
    mapping toRequest: RequestPayload <- Shipment {
        destination <- source.destination;
        weightKg <- source.weight;
    }
    mapping toPrice: Price <- PricePayload {
        amount <- source.amount;
        currency <- source.currency;
    }
    mapping toQuote: Quote <- QuotePayload {
        reference <- source.reference;
        price <- source.price using toPrice;
    }
}
```

Configure `shipping.base-url` externally and an HttpAdapterClient. Runtime-http
brings transport support; the public port never exposes RequestPayload/QuotePayload.
The 401 interpretation is a deliberate example policy, not universal HTTP semantics.

## 20. Few-shot pair E: Java mapping and transport PATCH definitions

**Request:** Provide an in-process port implementation and normalize text through
an explicit Java mapping. Include a transport PATCH DTO without pretending that
it automatically updates a domain object.

**Answer:** `src/main/vernac/ai/catalog/catalog.vernac`

```vernac
namespace ai.catalog;

value ProductTitle(String);
port Catalog { ProductTitle title(); }
adapter LocalCatalog implements Catalog {
    behavior {
        java imports { ai.catalog.adapter.outbound.mapping.LocalCatalogMappings; }
        public ProductTitle title() {
            return LocalCatalogMappings.normalize("notebook");
        }
    }
    schema ProductPatch(patch String name, patch String? comment);
    schema ProductPayload(String name, String? comment absent as null);
    mapping normalize: ProductTitle <- String {
        java imports { java.util.Locale; }
        java { return ProductTitle.of(source.toUpperCase(Locale.ROOT)); }
    }
}
```

ProductPatch.of takes two PatchField arguments, not strings. ProductPayload.of
accepts a string and nullable comment. A DTO declaration alone performs no update.

## 21. Generation checklist and verification recipe

Before returning generated source, check:

1. Namespace/path match; all referenced types are declared/imported unambiguously.
2. Field categories and optionality are legal; no mutable graphs in values/payloads.
3. Effective names are unique, including generated method conflicts.
4. Clause order is correct; Java stays in supported Java bodies.
5. Getter/factory names match explicit/default field names; optional representations
   follow the relevant public-vs-implementation contract.
6. self has the right view; helpers receive needed inputs; writes use modify methods.
7. Spring dependencies and external static implementations are explicitly supplied.
8. Required repository result collections exist; queries have eligible field types.
9. DTOs stay adapter-local; mappings assign all required targets without guessed conversions.
10. Matching runtime dependencies, transaction infrastructure and configuration exist.
11. Full project generation AND javac succeed. Run meaningful runtime/DB/HTTP tests
    for the behavior being claimed; report skipped infrastructure tests as skipped.

For a configured application, run `mvn clean verify`; when an IntelliJ field already
supplies the Maven executable, enter its goals/options as that UI requires. When modifying the compiler itself, install its artifacts
before building standalone examples: `mvn clean install`. Do not assume `verify`
updates the local Maven repository. Schema/migration commands require a configured
persistence application and deliberate review; they are not needed for pure VOs.

The repository's `DocumentationExamplesTest` extracts every complete Vernac block
in this file, resolves them as one project and compiles all generated Java together
with the supplied external Java examples using `--release 21`. It uses the current
checkout's compiler, not an older compiler fetched from Central. Re-run this test
after editing examples. This is a syntax/type check, not proof that arbitrary Spring
configuration, databases or remote providers are correct.

Repository readers: [language reference](language-reference.md),
[specifications](specification/index.md), [contracts](contracts/index.md),
[tutorials](tutorials/index.md). These links are supplementary; all essential
code-generation rules and examples in this prompt are included above.

## 22. HTTP inbound adapters (0.3.0)

Implemented synchronous Spring MVC endpoints around existing usecases. Syntax:

```text
adapter Api inbound http uses Create create {
    base "/api/items";
    post { POST "/"; body Request; execute create { title <- body.title; }
        respond 201 Response using output; }
    schema Request(String title); schema Response(UUID id);
    mapping output: Response <- ItemId { id <- source; }
}
```

This shape assumes declared Create(Title) returns ItemId. Each endpoint has an explicit
unique name, verb/route, typed path/query/header declarations, at most one required
local DTO body, exactly one injected-usecase execute block and an explicit response.
Use `path UUID itemId named "id";` for `/{id}`, then `itemId <- path.itemId;`. Query/header
values may be optional; path values cannot. GET bodies are forbidden. Source paths
start with path/query/header/body; assignment targets are actual usecase parameter
names. Required parameters must be assigned once; omitted optional parameters are
absent. Exact single-value wrapping uses validated factories; complex conversions
use named mappings. No automatic enum or String-to-UUID conversion in assignments.

No inferred Result. A tuple return may be referenced as `<Usecase>.Result` (qualified
when needed) as a mapping source for injected usecases. Void responses have no mapper;
value results require one. Success is 2xx; 204/205 have no body. Optional single results
require `on absent respond 204;`, `404;` or `410;`. Absent values skip response mapping.
Schemas/mappings can appear before or after endpoints. Base route `/` means the base
resource without a trailing slash. Controllers/DTOs/mappings live in
`namespace.adapter.inbound.http[.dto/.mapping]`. Add Spring MVC/Jackson, matching
vernac-runtime-http and component scanning; usecases retain REQUIRED transactions.

Errors default to safe `{code,message}` bodies: invalid transport 400, domain validation
422, missing aggregate 404, optimistic conflict 409, other domain rejection 422,
technical/response-mapping failure 500. Optional final adapter clause
`errors implemented by fully.qualified.JavaClass;` calls public static
`InboundHttpErrors.Reply handle(String endpoint, Exception error)`. No error-matching
DSL, retry/security/OpenAPI/async behavior is implied. Public messages are chosen
explicitly; internal exception messages are never automatically exposed.

### 22.1 Complete example: command endpoint and explicit Result

**Request:** Accept a label, invoke an application command, return the newly created
identity and label. Expose a second command using a path ID. Do not invent a response
contract inside the controller.

**Answer:** `src/main/vernac/ai/http/entries.vernac`

```vernac
namespace ai.http;

id EntryId;
value Label(String) validates {
    require(!self.string().isBlank(), "Label must not be blank");
};
usecase CreateEntry(Label) returns (EntryId, Label) behavior {
    execute { return Result.of(EntryId.create(), label); }
}
usecase ConfirmEntry(EntryId) behavior {
    execute { System.out.println("Confirmed " + entryId.asString()); }
}

adapter EntryApi inbound http uses CreateEntry createEntry, ConfirmEntry confirmEntry {
    base "/api/entries";
    create {
        POST "/";
        body CreateEntryRequest;
        execute createEntry { label <- body.label; }
        respond 201 EntryResponse using toResponse;
    }
    confirm {
        POST "/{id}/confirm";
        path UUID entryId named "id";
        execute confirmEntry { entryId <- path.entryId; }
        respond 204;
    }
    schema CreateEntryRequest(String label);
    schema EntryResponse(UUID id, String label);
    mapping toResponse: EntryResponse <- CreateEntry.Result {
        id <- source.entryId;
        label <- source.label;
    }
}
```

This demonstrates wiring; it does not store entries. Include a Spring Boot web
starter, matching vernac-runtime-http (which includes vernac-runtime-json), component
scanning for ai.http, and a transaction manager for the usecases. Spring context
and transaction libraries alone do not supply a transaction manager. Existing JDBC
applications already provide one; a small exercise can use Spring JDBC and an H2
in-memory datasource. Do not disable the usecase transaction to make startup pass.

POST /api/entries with `{"label":"Review schedule"}` maps String through Label.of,
then maps CreateEntry.Result to `{"id":"...","label":"Review schedule"}`. A blank
label fails validation (422); missing/wrong JSON fails wire validation (400).
POST /api/entries/<id>/confirm parses UUID explicitly and returns 204. The Result is
a declared application result; its mapping source is not an inferred DTO or a
standalone Vernac type. Source paths use actual generated getter names.

A response-mapping failure can occur after the usecase transaction commits. Its 500
response is not permission to repeat a command. Authentication, authorization,
idempotency keys, OpenAPI, streaming and asynchronous transports are not generated
by this syntax. Configure those concerns explicitly outside this initial slice.

## 23. Offset and cursor pagination (0.4.0)

Accepted repository syntax (Issues MUST be a declared list of Issue):

```text
find offset Issues withStatus(IssueStatus status)
    where issueStatus = :status order by createdAt desc, id asc;
find offset Issues countedWithStatus(IssueStatus status)
    where issueStatus = :status order by createdAt desc, id asc counting;
```

Only offset- or cursor-qualified methods return query objects. Ordinary finds return their
existing collection/singleton. `counting` is optional and requires offset. The compiler
requires a list result and an explicit sort whose final field is the root id.
Sets, optional singleton pages, missing order and a non-id final key are errors.
No hidden ordering or implicit pagination parameters are generated.

In Java bodies import OffsetRequest from org.vernac.runtime.pagination:
`self.issueRepository().withStatus(status).page(OffsetRequest.ofPage(3, 20))`.
Or use `ofOffset(40, 20)`. The counted query returns CountedOffsetPage with totalElements
and totalPages; the uncounted query returns OffsetPage without totals. Both expose
items(), request(), hasNext(), nextRequest(), offset(), pageSize() and pageNumber()
(OptionalLong: present only for offsets aligned to pageSize). Page numbers start at 1.
Empty/out-of-range counted pages retain the true total; an empty dataset has 0 pages.

Use `.pages(request).items().forEach(...)` for sequential lazy traversal **inside the
usecase transaction**; do not return a lazy sequence. Each page is materialized and
loads complete aggregates. Project eagerly with `page.mapItems(TargetList::of, mapper)`:
explicit destination factory FIRST; no filtering/reordering while keeping page metadata.
Generated lists implement DomainList<T>, sets do not. Filters bind at query creation.
The runtime checks an active transaction for every JDBC load, even on an escaped query.

Count and selected roots share one PostgreSQL statement snapshot. Child loads verify
root versions and can raise optimistic-lock failures under concurrent modification.
No automatic retry, isolation change or frozen multi-request dataset is promised.
`vernac.pagination.max-page-size` defaults to 1000; each request exceeding it fails
before SQL.

Cursor syntax is implemented:

```text
find cursor Issues following(IssueStatus status)
    where issueStatus = :status order by createdAt desc, id asc;
```

It returns CursorQuery<Issue, Issues>. Import CursorRequest in Java bodies and use
`.page(CursorRequest.first(20))`, `.page(CursorRequest.after(cursor, 20))` or
`.pages(CursorRequest.first(20)).items().forEach(...)` inside the usecase transaction.
CursorPage has items(), request(), pageSize(), nextCursor(), nextRequest(), mapItems();
NO totalElements, totalPages or absolute pageNumber. All sort fields MUST be required,
and the final explicit sort key MUST be id. `counting` on a cursor query is an error.
The cursor stores the last returned storage-key tuple, so deleting that root does not
break continuation. Mixed directions, decimal numeric ties and nanosecond components
follow SQL ordering. Changing the page size is allowed; changing filters is not.
Tokens are v1 authenticated/encrypted, binding method identity, filters, ordering,
complete owned schema fingerprint and codec. Invalid/modified/incompatible tokens raise
InvalidCursorException. Configure vernac.pagination.cursor-key with Base64 of 32 random
bytes for shared durable tokens; blank uses a process-local key invalidated on restart.
Reapply all permission/tenant conditions every request: cursors do not authorize access.
No frozen dataset, backward cursor navigation, expiration or automatic key rotation.

### Paginated usecase results and HTTP DTO fields (implemented)

Result qualifiers are explicit: `offset ValueList`, `counted offset ValueList`,
`cursor ValueList`. Use them as a single result or independent tuple components:

```text
returns (Title heading, counted offset Summaries openItems,
    counted offset Summaries closedItems, cursor Summaries recentItems)
```

The corresponding Java types are OffsetPage<Value, ValueList>,
CountedOffsetPage<Value, ValueList>, CursorPage<Value, ValueList>. ValueList MUST be a
named list of IDs/VOs/enums; no aggregate/entity entries or sets. Project repository
aggregate pages eagerly with mapItems(ValueList::of, mapper). Pages MUST be required,
not optional/mut, and never lazy. A tuple uses the existing Result.of; a single page
has no implicit Result. Repeated collection types require explicit different names.

Do NOT use qualifiers in domain fields, port signatures or usecase input headers.
Technical runtime requests are still Java APIs, not global modeled built-ins. Define
ordinary application PageNumber/PageSize/CursorToken VOs as needed and explicitly use
their intValue()/longValue()/string() getters when building runtime requests. Avoid
importing runtime PageNumber into a scope containing your own PageNumber type.

Adapter DTO fields accept `offset list<LocalDto>`, `counted offset list<LocalDto>` or
`cursor list<LocalDto>` (also approved scalar entries). Entries cannot be nullable,
containers or PATCH fields. Outer optional/naming rules still apply. Metadata names
are fixed lowerCamelCase. Offset envelopes have items/offset/pageSize/hasNext and
pageNumber only for aligned offsets; counted envelopes add totalElements/totalPages.
Cursor envelopes have items/pageSize and nextCursor only when continuation exists.
No internal request/Optional/query/aggregate is serialized.

Map a page as a whole: `openItems <- source.openItems using item;`. This applies the
named item mapping to every entry and retains all metadata. Exact scalar/VO automatic
entry conversions also work. Source and target page variants MUST match. Do NOT map
`source.openItems.items`, flatten a page into a list, silently drop counts or rebuild
a domain page from DTO metadata declaratively. DTO-to-DTO page projection is supported.
Use explicit Java for deliberately different semantics. Root HTTP responses remain
named local schemas; a single usecase page may be a field in that schema.

Multiple pages do not implicitly share a frozen multi-query snapshot. Usecases keep
REQUIRED transactions and complete each page/projection before returning. Invalid
cursors return HTTP 400/INVALID_CURSOR; ordinary domain validation stays 422. Never
use cursors as authorization. See docs/tutorials/pagination.md for a complete compiled
multi-page board and GET, and examples/dispatch for its executable repository-backed
counterpart. Dynamic filters, backward cursors and global technical request built-ins
remain outside this increment.

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

### Domain diagram tooling (0.4.0)

`mvn vernac:diagram` exports every local namespace; `-Dvernac.diagramNamespace=example.orders`
selects one. Output defaults to target/vernac-diagrams, with Markdown/Mermaid, .mmd,
PlantUML .puml and versioned JSON per namespace. Diagrams come from resolved declarations,
not generated Java or guessed behavior. Referenced outside types are boundary nodes.
IntelliJ's Show Domain Diagram action includes unsaved source snapshots and opens Markdown;
enable its Mermaid preview extension if needed. No rendered image or live model explorer
is implied. See [the diagram tutorial](tutorials/domain-diagrams.md).
