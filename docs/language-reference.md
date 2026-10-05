# Vernac Language Reference (v0.1.x)

This document is the comprehensive reference for the Vernac Domain-Specific Language (DSL) as implemented in this
repository.
If in doubt, the ANTLR grammar (`vernac-compiler/src/main/antlr4/org/vernac/compiler/parser/Vernac.g4`),
`AstBuilderVisitor`, and `SemanticAnalyzer` serve as the underlying implementation baseline.

Vernac files use the `.vernac` extension and typically live under `src/main/vernac/*.vernac`.

---

## 1. Architectural Philosophy & "Convention over Configuration"

Vernac is designed around core Domain-Driven Design (DDD) building blocks. A primary design principle is **Convention
over Configuration**:

- You can omit parameter/variable names, repository names, collection names, instance names, and dispatch modes whenever
  a clear convention applies.
- Sensible defaults (naming conventions, package structures, JDBC implementations, CRUD signatures) are generated
  automatically.
- Write only what varies from the standard.

---

## 2. File Structure & Package Organization

### 2.1 File Header

```vernac
package com.example.shop;

import java.math.BigDecimal;
import java.util.UUID;
import com.example.other.*;

// Top-level declarations...
```

- `package <qualifiedName>;` is optional. If omitted, the root package is used.
- `import <qualifiedName>.*;` or single-type imports are supported and forwarded to generated Java classes.

### 2.2 Default Package Conventions

By default, generated classes are placed into DDD-oriented subpackages based on the compilation unit's base package:

| Construct                                     | Default Target Subpackage                            | Example (`base = com.example.shop`)                       |
|:----------------------------------------------|:-----------------------------------------------------|:----------------------------------------------------------|
| `id`, `value`, `entity`, `aggregate`, `event` | `<basePackage>.domain`                               | `com.example.shop.domain`                                 |
| `repository` (Interface)                      | `<basePackage>.domain`                               | `com.example.shop.domain`                                 |
| `repository` (JDBC Implementation)            | `<basePackage>.adapter.db`                           | `com.example.shop.adapter.db`                             |
| `port` (Interface)                            | `<basePackage>.domain`                               | `com.example.shop.domain`                                 |
| `port` (REST Adapter, Schemas, Delegates)     | `<basePackage>.infrastructure.outbound.<portname>`   | `com.example.shop.infrastructure.outbound.paymentgateway` |
| `usecase`                                     | `<basePackage>.usecase`                              | `com.example.shop.usecase`                                |
| `service`                                     | `<basePackage>.domain`                               | `com.example.shop.domain`                                 |
| `listener`                                    | `<basePackage>.domain`                               | `com.example.shop.domain`                                 |
| `collection`                                  | Same as enclosing entity/value object (or `.domain`) | `com.example.shop.domain`                                 |

### 2.3 Package Overrides

Virtually every Vernac declaration supports an inline block override via `package <customPackage>;`:

```vernac
id SharedId {
    package com.example.shared.kernel;
}

value Money(BigDecimal amount) {
    package com.example.billing;
}
```

---

## 3. Name and Type Inference Rules

One of Vernac's most powerful features is the ability to omit explicit variable, field, parameter, or component names.
The compiler resolves them deterministically:

### 3.1 Field & Parameter Name Derivation (`deriveFieldName`)

When a parameter or field name is omitted in definitions (e.g. `value Document(String, UUID);` or
`aggregate Customer[CustomerId](String, mut String customAlias);`):

1. **Single-field Value Object Fallback**:
    - In a single-field value object without an explicit name (e.g. `value WattHours(int)`), the field is named `value`.
2. **Multi-field / General Parameter Fallback**:
    - **Primitives** (`int`, `long`, `double`, `float`, `boolean`, `byte`, `short`, `char`):
      Appends `Value` to the primitive type (e.g., `int` -> `intValue`, `double` -> `doubleValue`).
    - **Acronyms / Multi-uppercase Types** (starts with $\ge 2$ uppercase letters):
      Converted entirely to lowercase (e.g., `UUID` -> `uuid`, `URL` -> `url`, `ISBN` -> `isbn`).
    - **Standard PascalCase Types**:
      First letter decapitalized (e.g., `CustomerId` -> `customerId`, `ProjectName` -> `projectName`, `String` ->
      `string`, `BatterySoc` -> `batterySoc`).

### 3.2 Collection Name Derivation

- When omitting the name in a `collection` clause (e.g. `value LineItem(...) collection;` or
  `entity Task[...] collection;`), the compiler automatically appends `s` to the element type name (e.g., `LineItems`,
  `Tasks`, `Cars`).

### 3.3 Repository Name Derivation

- `repository for Order { ... }` automatically derives the repository interface name as `<AggregateName>Repository`
  (e.g., `OrderRepository`).

### 3.4 Event Dispatch Mode Default

- `event OrderPlaced(...)` defaults to `outbox` dispatching mode.

### 3.5 UseCase Dependency & Variable Derivations

- `use OrderRepository;` derives the field/instance name as `orderRepository` (decapitalized type name).
- `load Order;` derives instance variable `order`, queries repository `orderRepository` (or the single matching repo in
  `use`), and defaults the ID lookup parameter to `orderId`.
- `save order;` automatically targets `orderRepository`.
- `return (order.id(), order.status());` in tuples automatically derives component names from the property name after
  the dot (`id`, `status`).

---

## 4. Typed Identifiers (`id`)

Typed identifiers wrap standard UUIDs to guarantee type safety in domain logic and avoid parameter transposition bugs.

```vernac
id OrderId;
id CustomerId;
```

### Generated Java Code:

- A `final` class implementing standard `equals`, `hashCode`, and `toString`.
- Encapsulates `private final UUID value;`.
- Factory methods:
    - `public static OrderId create()`: Generates a new random identifier via `UUID.randomUUID()`.
    - `public static OrderId of(UUID value)`: Wraps an existing `UUID`.
    - `public static OrderId of(String value)`: Parses a UUID string via `UUID.fromString(value)`.
- Accessor: `public UUID value()`.

---

## 5. Value Objects (`value`)

Value Objects represent immutable domain concepts without independent identity. They are strictly immutable (fields
cannot be marked `mut`).

### 5.1 Single-Field Value Objects

```vernac
value WattHours(int) validates {
    require(value >= 0, "WattHours cannot be negative");
}
```

- When the parameter name is omitted, it defaults to `value`.
- Factory methods: `Type.of(...)`.
- Getter: `value()`.
- Validations defined in `validates { require(condition, "error message"); }` are checked at construction time, throwing
  `DomainValidationException` on violation.
- **UUID Value Object Special Helpers**: If a single-field value object wraps a `UUID` (e.g. `value ProjectId(UUID)`),
  the generator also produces:
    - `create()` (new random UUID), `of(String)`
    - `asString()`, `asUuid()`

### 5.2 Multi-Field and Nested Value Objects

```vernac
value Currency(String);
value Money(BigDecimal amount, Currency currency);
value Document(String, UUID); // field names inferred as 'string' and 'uuid'
```

- Generates constructor, `of(...)` factory methods with null-checks (`Objects.requireNonNull`), record-like getters
  (`amount()`, `currency()`), and `equals`/`hashCode`/`toString`.

### 5.3 Optional / Nullable Fields (`?`)

Any field can be marked as optional using the `?` suffix (e.g., `String? comment`):

```vernac
value Money(BigDecimal amount, String? comment) validates {
    require(amount.compareTo(BigDecimal.ZERO) >= 0, "Amount must be positive");
}
```

- The backing field is annotated with `@Nullable`.
- The getter returns `java.util.Optional<T>` (e.g.,
  `public Optional<String> comment() { return Optional.ofNullable(this.comment); }`).
- An overloaded factory `Money.of(amount)` is generated alongside `Money.of(amount, comment)` for ergonomics.
- *Note:* Primitive types cannot be optional directly (`int?` is invalid; use `Integer?` instead).

### 5.4 First-Class Collections (`collection`)

Vernac supports first-class typed domain collections directly attached to value objects or entities:

```vernac
value Tag(String name) collection; // Inferred name: Tags
value Money(BigDecimal amount) collection MoneyTransactions {
    public Money sum() {
        // Custom domain logic in raw Java
        return null;
    }
}
```

Generated collection class:

- `public final class Tags implements Iterable<Tag>`
- Immutably wraps `List<Tag>` using `List.copyOf(...)`.
- Methods generated:
    - `empty()`, `of(Tag... items)`, `of(Collection<Tag> items)`
    - `toList()`, `elements()`, `toSet()`
    - `plus(Tag item)`, `plusAll(Iterable<Tag> others)`
    - `minus(Tag item)`
    - `filter(Predicate<Tag> predicate)`

### 5.5 Enums

Enums are declared as value objects using `=` and pipe `|` separators:

```vernac
value OrderStatus = NEW | PAID | SHIPPED;
```

With custom database persistence values:

```vernac
value CountryCode = DE("DEU") | US("USA");

value AcMode = ECO("eco") | COOL | HEAT("heat") | OFF {
    public boolean isActive() {
        return this != OFF;
    }
}
```

- Generates a Java `enum` implementing `ValueObject`.
- `public String dbValue()`: Returns the explicit string or defaults to the constant's name (e.g. `"COOL"`).
- `public static AcMode of(String value)`: Lookup matching `dbValue` or enum name.
- Enums can contain custom Java methods in a trailing block `{ ... }`.

---

## 6. Aggregates and Entities (`aggregate`, `entity`)

Aggregates and Entities represent stateful domain models with identity.

### 6.1 Basic Syntax

```vernac
entity OrderLine[OrderLineId](
    String sku,
    mut int quantity,
    Money unitPrice
) collection OrderLines;

aggregate Order[OrderId](
    CustomerId customer,
    mut String status,
    mut OrderLines lines
) validates {
    require(status != null, "Status must not be null");
} {
    public void completeOrder() {
        status("PAID");
        registerEvent(OrderPaid.create(this.id, this.customer));
    }
}
```

### 6.2 Key Features & Generated Code

- **Identity**: Bound to an ID type via `[IdType]`.
- **Factory Methods**:
    - `public static Type create(IdType id, ...)` for initial domain creation.
    - `public static Type reconstitute(IdType id, ..., Instant createdAt, Instant updatedAt, long version)` for
      persistence rehydration.
- **Mutable Fields (`mut`)**:
    - Immutable fields are generated as `final`.
    - Fields marked `mut` generate setter-like methods (e.g. `public void status(String status)`).
    - When a field value changes, generated mutators assign the new value
      and then execute all `validates { ... }` invariant checks.
      Aggregate mutators also call `markAsUpdated()` before validation.
    - Assigning an equal value is a no-op and does not re-run invariant checks.- **Lifecycle & Auditing**:
    - Aggregates implement `AggregateRoot<IdType>` and maintain `createdAt`, `updatedAt`, and `version` (optimistic
      locking).
    - Entities implement `Entity<IdType>`.
- **Domain Events in Aggregates**:
    - Aggregates maintain a transient domain event queue.
    - Inside methods, call `registerEvent(event)` to queue events.
    - Repositories or infrastructure pull and dispatch events via `pullDomainEvents()`.
- **Entity Collections (`minusId`)**:
    - If a collection is defined on an `entity` (e.g. `entity OrderLine[...] collection OrderLines;`), the collection
      includes an additional `minusId(OrderLineId id)` method for identity-based removal.
- **SQL Schema Constants**:
    - Aggregates and entities generate `public static final String TABLE_NAME = "..."` (snake_case) and
      `public static final String SCHEMA_DDL = "..."`.
    - Multi-field and nested value objects are recursively flattened into SQL column definitions (e.g. `Money budget`
      becomes `budget_amount NUMERIC(19, 4)` and `budget_currency VARCHAR(255)`).

### 6.3 Strict DDD Invariant Rules Checked by Compiler

- Aggregates **cannot** be directly embedded inside other Aggregates/Entities as fields; reference them by their
  `IdType`.
- Raw collections (`List`, `Set`, `Map`) are forbidden as aggregate/entity fields; use first-class `collection` types.

---

## 7. Events (`event`)

Events capture immutable facts that occurred in the domain. For an in-depth guide on the Transactional Outbox pattern,
`vernac_outbox` DDL, event dispatching, and asynchronous event listeners,
see [Event-Driven Architecture & Outbox Documentation](event-driven-architecture.md).

```vernac
// Default is outbox dispatch mode
event OrderCreated(OrderId orderId, CustomerId customerId);

// Explicit dispatch modes
memory event OrderValidated(OrderId orderId);
outbox event ProjectBudgetExceeded(ProjectId projectId, Money currentCost, String? reason);
```

### Generated Java Code:

- Implements `DomainEvent`.
- Generates `UUID eventId` and `Instant occurredOn`.
- Generates constant `public static final String EVENT_TYPE = "PROJECT_BUDGET_EXCEEDED";`.
- Factory methods: `create(...)` and `of(UUID eventId, Instant occurredOn, ...)`.
- For `outbox` events: Generates `OUTBOX_TABLE_NAME = "vernac_outbox"` and `OUTBOX_SCHEMA_DDL`.

---

## 8. Repositories (`repository`)

Repositories provide Spring JDBC-based persistence with optimistic locking and event dispatching. For an in-depth guide
on database mapping rules, DDL generation, column flattening, child entity synchronization, and custom Spring repository
beans, see [Repository Database Mapping Documentation](repository-db-mapping.md).

```vernac
repository for Order {
    find List<Order> findByStatus(String status);
    custom List<Order> findTopOrders(BigDecimal threshold);
}
```

### Generated Artifacts:

1. **Repository Interface** (`<basePackage>.domain.OrderRepository`):
    - Standard CRUD methods: `Order byId(OrderId id);`, `Order save(Order aggregate);`, `void delete(Order aggregate);`.
    - Any `find` methods declared in the block.
    - Extends custom fragment `OrderRepositoryCustom` if `custom` methods are present.
2. **Custom Interface** (`OrderRepositoryCustom`):
    - Contains signatures for all `custom ...` methods so you can implement complex queries in standard Java.
3. **Spring JDBC Repository** (`<basePackage>.adapter.db.JdbcOrderRepository`):
    - Annotated with `@Repository` and `@Transactional(propagation = Propagation.MANDATORY)`.
    - Optimistic locking check via `version`.
    - Automatically dispatches queued domain events on `save(...)` using `JdbcEventDispatcher`.
    - Automatically generates 1:N entity child persistence (`sync<Entities>`, `fetch<Entities>`) for any aggregate
      collection fields.

---

## 9. Ports and Adapters (`port`)

Ports define outbound boundaries (Hexagonal Architecture / Clean Architecture). For an in-depth guide on mapping rules,
conventions, and code generation details, see [Outbound Port Mapping Documentation](outbound-port-mapping.md).

### 9.1 REST Adapters and Schemas

```vernac
port WeatherProvider {
    schema WeatherApiResponse {
        String description;
        BigDecimal tempCelsius;
    }

    Optional<Temperature> getCurrentTemperature(CityName city) {
        adapter rest {
            GET "/api/v1/weather";
            on 404 return Optional.empty();
            on 5xx throw ServiceUnavailableException;
        }
        mapping {
            response.tempCelsius -> Temperature.celsius;
        }
    }
}
```

### Generated Artifacts:

- **Port Interface**: Domain interface with method signature.
- **Schema DTO**: DTO class suitable for Jackson JSON mapping.
- **REST Adapter** (`RestWeatherProviderGetCurrentTemperatureAdapter`):
    - Spring `@Component` using `org.springframework.web.client.RestClient`.
    - Base URL injected via Spring `@Value("${vernac.outbound.weather-provider.base-url:http://localhost:8080}")`.
    - Automatic query parameter interpolation: Method parameters like `CityName city` are automatically added as query
      parameters (e.g. `?city={city}`), unwrapping typed IDs via `.value()`.
    - Status code matching: `on 404 return Optional.empty();`, `on 5xx throw ...`.
    - Catch-all fallback error handling on status errors.
    - Response mapping via `mapping { ... }`: Instantiates domain types via `.of(...)` (Value Objects) or
      `.fromExternal(...)` (Entities).

### 9.2 Custom Adapters

```vernac
port InvoiceGenerator {
    PdfDocument generate(InvoiceData data) {
        adapter custom InvoiceGeneratorDelegate;
    }
}
```

- Generates interface `InvoiceGeneratorDelegate` for you to implement in your infrastructure package.

---

## 10. Use Cases (`usecase`)

Use Cases represent transactional application services orchestrating domain logic. For an in-depth guide on parameter
validation, dependency injection, repository conventions, load/save semantics, and tuple returns,
see [Use Cases & Application Layer Documentation](usecase-application-layer.md).

```vernac
usecase CancelOrder(OrderId id, String reason) validates {
    require(!reason.isBlank(), "Reason required");
} {
    use OrderRepository;
    use NotificationPort notifications;

    load Order by id;
    order.cancel(reason);
    save order;

    return (order.id(), reason as cancellationReason);
}
```

### Generated Artifacts:

- Spring `@Service` annotated with `@Transactional`.
- Constructor with Spring dependency injection for all `use` declarations (with null-checks).
- `execute(...)` method matching usecase parameters and pre-condition validation.
- Embedded static `Result` record for tuple returns:
  `public static record Result(OrderId id, String cancellationReason) {}`
- Automatic type inference: Types of `Result` record components are inferred automatically from parameter types,
  aggregate getters, or literal expressions.
- High-level DSL statements:
    - `use <Dependency> [varName];`
    - `load <Aggregate> [varName] [from <repoVar>] [by <idExpr>];`
    - `save <varName> [to <repoVar>];`
    - `return (<expr> [as <alias>], ...);` (Tuple return)
    - `return [expr];` (Single return)
    - Raw Java statements can be intermixed freely.

### DDD Constraints:

- Use cases cannot accept aggregate roots directly as input parameters or return aggregate roots directly in results
  (pass IDs, DTOs, or Value Objects instead).

---

## 11. Domain Services (`service`)

Domain Services represent domain logic that does not naturally belong to a single entity or value object.

```vernac
service TariffCalculator(WattHours capacity, WattHours storedEnergy, WattHours) : WattHours validates {
    require(capacity.value() > 0, "Capacity must be positive");
} {
    int available = Math.max(0, storedEnergy.value() - wattHours.value());
    return WattHours.of(Math.max(0, capacity.value() - available));
}
```

- Generated as a Spring `@Service` with a `public <ReturnType> execute(<params>)` method.
- Parameter names can be omitted and are derived automatically.
- Inputs are strictly immutable (no `mut` parameters).

---

## 12. Event Listeners (`listener`)

Event listeners subscribe to domain events to trigger asynchronous side-effects or inter-aggregate coordination.

```vernac
listener StorageCharged {
    use NotificationPort notifications;

    notifications.sendAlert("Storage charged: " + event.storageId().value());
}
```

- Generated as a Spring `@Component` named `<EventName>Listener`.
- Generates handler method:
  `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`
  `public void on(StorageCharged event) { ... }`
- Injected dependencies via `use`.
- The current event is available in raw Java statements as the implicit variable `event`.

---

## 13. Summary of Inference & Omission Rules

For quick reference, this table summarizes everything you can omit in Vernac:

| Context               | What can be omitted               | Default / Inferred Behavior                                                                                  |
|:----------------------|:----------------------------------|:-------------------------------------------------------------------------------------------------------------|
| `value Name(T)`       | Parameter name (single field)     | Named `value` (e.g. `value()`).                                                                              |
| `value Name(T1, T2)`  | Parameter names (multi-field)     | Inferred via `deriveFieldName` (e.g., `UUID` $\to$ `uuid`, `String` $\to$ `string`, `int` $\to$ `intValue`). |
| `entity`, `aggregate` | Field names                       | Inferred via `deriveFieldName` (e.g. `String` $\to$ `string`).                                               |
| `collection`          | Collection name                   | Pluralized type name (e.g. `Car collection;` $\to$ `Cars`).                                                  |
| `event`               | Dispatch mode                     | Defaults to `outbox`.                                                                                        |
| `event`               | Parameter names                   | Inferred via `deriveFieldName`.                                                                              |
| `repository`          | Repository name                   | `<AggregateName>Repository`.                                                                                 |
| `usecase`             | `use Dependency;` instance name   | Decapitalized type name (e.g. `orderRepository`).                                                            |
| `usecase`             | `load Aggregate;` target variable | Decapitalized aggregate name (e.g. `order`).                                                                 |
| `usecase`             | `load Aggregate;` repo name       | Single matching repository in `use` statements.                                                              |
| `usecase`             | `load Aggregate;` by expression   | `<aggregateName>Id` (e.g. `orderId`).                                                                        |
| `usecase`             | `save aggregate;` repo name       | Single matching repository in `use` statements.                                                              |
| `usecase`             | `return (a.b(), c as alias);`     | Component alias defaults to property after dot (`b`), types inferred automatically.                          |
| `enum`                | Constant dbValue                  | String name of the constant (e.g. `COOL` $\to$ `"COOL"`).                                                    |
| Top-level & blocks    | `package`                         | Inherits file package or default DDD subpackage (`.domain`, `.usecase`, etc.).                               |
