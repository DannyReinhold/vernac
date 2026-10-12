# Vernac: self-contained reference for AI code generation

Language baseline: **released 0.2.0**, also the starting language of the
**0.3.0-SNAPSHOT** development branch. Date: 2026-10-12. This document describes
implemented project compilation, not proposed features. It is intentionally
self-contained: provide this entire file to an AI together with the application
requirements and existing model files. Artifact versions must match the project.

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

All fenced `vernac` blocks in sections 15–20 below form **complete examples** with
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
id Name [collection];
value Name(params) [validates {...}] [behavior {...}] [collection];
value Name = CONSTANT { | CONSTANT } [behavior {...}] [collection];
entity Name[IdType](params) [validates {...}] [behavior {...}] [collection];
aggregate Name[IdType](params) [validates {...}] [behavior {...}] [collection];
[recorded] event Name(params) [validates {...}];
collection := (list | set) [CollectionName] [behavior {...}]
```

Use semicolons as shown even where a declaration terminator is optional. A type
can have **one** attached list or set, not both. Default collection name is exact
`ElementName + s`: `Categorys`, not guessed `Categories`.

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

Persistence is PostgreSQL/JDBC, not JPA. It flattens values and keeps entity relations
and list positions. Schema and migration goals produce owned schema/candidates;
never invent SQL identifiers from Java names. Migration candidates require owner
review/testing/approval. No arbitrary .class/.jar cleanup by the Vernac compiler;
use Maven clean after removing generated types to eliminate stale bytecode.

Do NOT generate these as supported language:

- `package` headers/overrides, Java imports at file scope, annotations on model fields.
- Old `collection`, bare entity Java method blocks, `use`, magic load/save/emit statements.
- `new Title(...)`, `Task.create(id, ...)`, implicit value(), ordinary VO asString().
- Generic domain List/Map, built-ins as aggregate fields, aggregate-valued VO fields.
- Unspecified public usecase methods, usecase Result inferred from Java, uses(...).
- `permission`, `requires`, entitlement/quota/subscription declarations or module exports.
- Imports of external Vernac modules from dependency metadata: this is planned, not implemented.
- Generated HTTP controllers/MCP/Kafka/RabbitMQ endpoints, automatic async ports.
- Database rollback restoring object state or guaranteeing rollback of remote effects.

Retained experimental grammar productions do not make an unsupported construct
part of the public language. Where uncertain, inspect the current project compiler
and its generated API, not merely whether the parser accepts text.

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
and an explicit collection. Reference it from another namespace.

**Answer, file 1:** `src/main/vernac/ai/people/names.vernac`

```vernac
namespace ai.people;

value PersonName(String text) validates {
    require(!self.text().isBlank(), "Name must not be blank");
} behavior {
    java imports { java.util.Locale; }
    public String upper() { return self.text().toUpperCase(Locale.ROOT); }
    public int length() implemented by ai.helpers.NameBehavior;
} list PersonNames;

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

For a configured application, run `mvn clean verify` (in IntelliJ's Maven command
field use `clean verify`). When modifying the compiler itself, install its artifacts
before building standalone examples: `mvn clean install`. Do not assume `verify`
updates the local Maven repository. Schema/migration commands require a configured
persistence application and deliberate review; they are not needed for pure VOs.

The complete examples above were checked as one source tree through the project
compiler from Maven Central (0.2.0) and Java 21-target compilation when this
document was authored (seven Vernac files; 91 Java files including the external
implementation fixture). That is
a syntax/type check, not proof that an arbitrary application's Spring configuration,
database or remote provider is correct. Re-run after changing examples or compiler.

Repository readers: [language reference](language-reference.md),
[specifications](specification/index.md), [contracts](contracts/index.md),
[tutorials](tutorials/index.md). These links are supplementary; all essential
code-generation rules and examples in this prompt are included above.
