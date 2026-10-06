# Use Cases and Application Layer in Vernac

This document describes the architecture, conventions, compiler rules, and code generation mechanisms for **Use Cases** (`usecase`) and **Domain Services** (`service`) in Vernac (derived from `UseCaseGenerator`, `DomainServiceGenerator`, `SemanticAnalyzer`, and `AstBuilderVisitor`).

---

## 1. Architecture & Role in Domain-Driven Design (DDD)

In Vernac's hexagonal architecture, the use case layer forms the **Application Layer**:

- **Role of Use Cases (`usecase`):** Orchestrating business workflows. They load aggregates through repositories, invoke domain methods, persist state modifications (`save`), delegate to outbound ports, and return business results (DTOs, tuples, or primitives).
- **Role of Domain Services (`service`):** Cross-aggregate calculations or pure domain logic that cannot be naturally attributed to a single aggregate, free of persistence or infrastructure dependencies.
- **Transaction Boundary:** Every generated use case is a Spring `@Service` component annotated with `@Transactional`. As a result, all load, save, and event dispatching operations execute atomically within a single database transaction.

```
+-------------------------------------------------------------------------+
| APPLICATION LAYER (Orchestration)                                       |
|                                                                         |
|  +-------------------------------------------------------------------+  |
|  |  @Service @Transactional UseCase (e.g., PlaceOrder)               |  |
|  |                                                                   |  |
|  |   1. validates { require(...) }                                   |  |
|  |   2. load Order from orderRepository with orderId                 |  |
|  |   3. order.completeOrder();                                       |  |
|  |   4. save order                                                   |  |
|  |   5. return (order.id() as orderId, order.total() as total)       |  |
|  +-------------------------------------------------------------------+  |
|         |                      |                           |            |
+---------|----------------------|---------------------------|------------+
| DOMAIN  v                      v                           v            |
|  +--------------+      +---------------+           +---------------+    |
|  |  Aggregate   |      |  Repository   |           | Outbound Port |    |
|  |  (Order)     |      |  (Interface)  |           | (External API)|    |
|  +--------------+      +---------------+           +---------------+    |
+-------------------------------------------------------------------------+
```

---

## 2. Declaration & Syntax of Use Cases

A use case in Vernac contains the following optional and required sections:

```vernac
usecase PlaceOrder(
    CustomerId customerId,
    OrderLines lines
) use (
    OrderRepository,
    CustomerRepository customerRepo,
    PaymentPort payment
) validates {
    require(lines != null, "Order lines must not be null");
} {
    Customer customer = load Customer from customerRepo with customerId;
    Order order = Order.create(OrderId.random(), customer.id(), lines);
    order.calculateTotals();
    save order;
    return (order.id() as orderId, order.total() as total);
}
```

---

## 3. Parameter and Type Rules (DDD Restrictions)

The `SemanticAnalyzer` enforces strict semantic rules for use case parameters:

1. **No Direct Aggregates as Parameters:**
   - **Forbidden:** `usecase CancelOrder(Order order)`
   - **Allowed:** `usecase CancelOrder(OrderId orderId, Reason reason)`
   - *Rationale:* Aggregates must not be passed into the application layer uncontrolled from outside. The use case must maintain consistency boundaries and load the aggregate itself via the repository.
2. **Allowed Parameter Types:**
   - IDs (e.g., `OrderId`, `CustomerId`, `UUID`)
   - Value Objects (e.g., `Money`, `Quantity`, `EmailAddress`)
   - First-Class Collections (e.g., `OrderLines`, `ProductIds`)
   - Primitive types and standard JDK types (`int`, `String`, `Instant`, `BigDecimal`, etc.)
3. **Parameter Name Uniqueness:** Duplicate parameter names trigger a compiler error.

---

## 4. Dependencies (`use (...)`) & Injection

Via the `use` block, a use case declares its required dependencies (repositories, outbound ports, domain services, etc.).

### 4.1 Naming Conventions & Default Instance Names
If no explicit instance name is specified, Vernac derives the variable name in **camelCase** from the type name:

```vernac
use (
    OrderRepository,                 // -> private final OrderRepository orderRepository;
    PaymentPort,                     // -> private final PaymentPort paymentPort;
    CustomerRepository customerRepo  // -> private final CustomerRepository customerRepo;
)
```

### 4.2 Constructor Generation
The compiler automatically generates the full constructor with `Objects.requireNonNull` guards for Spring constructor injection:

```java
@Service
@Transactional
public class PlaceOrder {

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepo;
    private final PaymentPort paymentPort;

    public PlaceOrder(
            OrderRepository orderRepository,
            CustomerRepository customerRepo,
            PaymentPort paymentPort
    ) {
        this.orderRepository = Objects.requireNonNull(orderRepository, "orderRepository must not be null");
        this.customerRepo = Objects.requireNonNull(customerRepo, "customerRepo must not be null");
        this.paymentPort = Objects.requireNonNull(paymentPort, "paymentPort must not be null");
    }
    ...
}
```

### 4.3 Uniqueness Rules
- Each dependency type may appear only once in the `use` block.
- Instance variable names must be unique.

---

## 5. Preconditions & Validation (`validates`)

Use cases can validate input arguments using the `validates` block:

```vernac
validates {
    require(customerId != null, "Customer ID must not be null");
    require(!lines.isEmpty(), "Order lines must not be empty");
}
```

- **Generation:** Emitted as the first statements in the `execute` method.
- **Error Handling:** If a condition fails, an `org.vernac.runtime.DomainValidationException` is thrown:
  ```java
  if (!(customerId != null)) {
      throw new DomainValidationException("Customer ID must not be null");
  }
  ```

---

## 6. Statements & Orchestration in Use Cases

The body of a use case supports both DSL-specific statements and embedded Java code.

### 6.1 `load` Statement
Loads an aggregate through its responsible repository by ID:

```vernac
// Full syntax
Order order = load Order from orderRepository with orderId;

// Conventional syntax (repository is automatically inferred)
Order order = load Order with orderId;
```

**Generated Java Code:**
```java
Order order = this.orderRepository.byId(orderId);
```

**Compiler Inference Rules:**
- If `from <repoVar>` is omitted, the `SemanticAnalyzer` looks in the `use` block for a matching repository for the aggregate type `Order`.
- If exactly one matching repository is found, it is selected automatically.
- If there are multiple or no matching repositories in the `use` block, the compiler reports an error (`Ambiguous repositories` or `No repository in 'use' manages aggregate...`).
- *Note:* `byId()` does not return `Optional`, but throws an `AggregateNotFoundException` immediately if not found.

### 6.2 `save` Statement
Saves an aggregate through its repository:

```vernac
// Full syntax
save order to orderRepository;

// Conventional syntax (repository name inferred from instance name or single matching repo)
save order;
```

**Generated Java Code:**
```java
this.orderRepository.save(order);
```

### 6.3 Free Java Code
All other domain invocations (methods on aggregates, port calls, control structures) can be written directly as standard Java statements:
```vernac
order.completeOrder();
paymentPort.process(order.id(), order.total());
```

---

## 7. Return Values & Tuple Returns (`return`)

Use cases support three kinds of return values:

### 7.1 No Return Value (Void)
If no `return` statement is declared, the generated `execute(...)` method has a return type of `void`.

### 7.2 Single Return Value
```vernac
usecase GetOrderStatus(OrderId orderId) use (OrderRepository) {
    Order order = load Order with orderId;
    return order.status();
}
```
**Generated Java Code:**
```java
public String execute(OrderId orderId) {
    Order order = this.orderRepository.byId(orderId);
    return order.status();
}
```

### 7.3 Tuple Return (`Result` Record)
When a use case returns multiple values, Vernac supports tuple returns with automatic record generation:

```vernac
usecase PlaceOrder(CustomerId customerId, OrderLines lines) use (OrderRepository) {
    Order order = Order.create(OrderId.random(), customerId, lines);
    save order;
    return (order.id() as orderId, order.total() as totalAmount);
}
```

**Generated Java Code:**
1. **Embedded `Result` Record:**
   ```java
   public static record Result(OrderId orderId, Money totalAmount) {
   }
   ```
2. **Method Signature & Return:**
   ```java
   public Result execute(CustomerId customerId, OrderLines lines) {
       Order order = Order.create(OrderId.random(), customerId, lines);
       this.orderRepository.save(order);
       return new Result(order.id(), order.total());
   }
   ```

**Name and Type Inference for Tuples:**
- **Field Name:** Specified via `as <name>`. If `as` is omitted, the compiler derives the name from the last path segment (`order.id()` $\rightarrow$ `id`).
- **Type:** Inferred by the `ExpressionTypeInferrer` based on aggregate getters, parameters, or literal types.
- **DDD Rule:** Directly returning an entire aggregate root in a tuple is forbidden (`Returning aggregate root is forbidden`) to prevent leaking domain state outside transactional boundaries.

---

## 8. Domain Services (`service`)

For stateless, computational domain logic, Vernac provides `service`:

```vernac
service TaxCalculator(
    Money netAmount,
    double taxRate
) validates {
    require(taxRate >= 0.0, "Tax rate cannot be negative");
} {
    BigDecimal gross = netAmount.amount().multiply(BigDecimal.valueOf(1.0 + taxRate));
    return (Money.of(gross, netAmount.currency()) as grossAmount, taxRate as appliedRate);
}
```

### Differences between `usecase` and `service`:
| Feature | `usecase` (Application Layer) | `service` (Domain Layer) |
| :--- | :--- | :--- |
| **Package** | `<basePackage>.usecase` | `<basePackage>.domain` |
| **Transactions** | `@Transactional` (enforces DB transaction) | No `@Transactional` annotation |
| **Dependencies** | Repositories, Ports, Services (`use (...)`) | No repositories / DB access |
| **Parameters** | Immutable; no aggregate roots | Pure domain model / VOs / parameters |
| **Purpose** | Orchestration, persistence, ports | Complex algorithms, calculations |

---

## 9. Summary Reference Table

| Language Feature | Syntax / Convention | Generated Behavior / Rule |
| :--- | :--- | :--- |
| **Class Definition** | `usecase Name(Params...)` | Generates `@Service @Transactional public class Name` |
| **Dependencies** | `use (RepoA, PortB portVar)` | Generates `private final` fields + constructor injection with null checks |
| **Default Repo Name** | `Type` $\rightarrow$ `camelCase(Type)` | `OrderRepository` $\rightarrow$ `orderRepository` |
| **Validation** | `validates { require(cond, msg); }` | Generates guards throwing `DomainValidationException` |
| **Loading** | `load Agg [from repo] with id;` | Generates `this.<repo>.byId(id)` |
| **Saving** | `save agg [to repo];` | Generates `this.<repo>.save(agg)` |
| **Tuple Return** | `return (expr as alias, ...);` | Generates `public static record Result(...)` and `return new Result(...)` |
| **Aggregate Protection** | Parameter / Return Restriction | Aggregates may neither be passed directly as input nor returned as a whole |
