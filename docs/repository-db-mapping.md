# Database and Repository Mapping in Vernac

This document describes the architecture, conventions, DDL generation, type mappings, aggregate boundaries, and extension mechanisms for **Repositories** and **relational database mapping (JDBC / PostgreSQL)** in the Vernac DSL (derived from `RepositoryGenerator`, `PostgresSchemaUtils`, `AggregateGenerator`, `EntityGenerator`, and `SemanticAnalyzer`).

---

## 1. Architecture & Concept

Vernac implements relational persistence following the principles of **Domain-Driven Design (DDD)** and **Hexagonal Architecture**:

- **Repository Interface (Domain Layer):** Declares business access methods (`byId`, `save`, `delete`, `find...`, `custom...`) and resides in the domain package (`<basePackage>.domain`) by default. The domain has zero dependencies on any persistence framework.
- **JDBC Implementation (Infrastructure Layer):** The compiler automatically generates a `Jdbc<Aggregate>Repository` as a Spring `@Repository` component using Spring's `NamedParameterJdbcTemplate` in the adapter package (`<basePackage>.infrastructure.adapter.db` or `<basePackage>.adapter.db`).
- **No Anti-Corruption Layer (ACL):** Unlike external outbound ports (e.g., third-party REST APIs), internal database mapping intentionally avoids a DTO / Anti-Corruption layer. The relational database is considered an integrated part of the application itself; table columns are mapped directly to domain model attributes (`AggregateRoot`, `Entity`, `ValueObject`).
- **Transaction Binding & Outbox Dispatching:** Every JDBC repository enforces an active transaction (`@Transactional(propagation = Propagation.MANDATORY)`). After each successful `save()`, domain events registered on the aggregate are atomically handed over to the `EventDispatcher` within the same transaction (Transactional Outbox Pattern).

```
+-------------------------------------------------------------------------+
| DOMAIN LAYER                                                            |
|                                                                         |
|  +------------------------+             +----------------------------+  |
|  |  Repository Interface  | <---------- |       Aggregate Root       |  |
|  |  (e.g., OrderRepository)             |  (e.g., Order with Lines)  |  |
|  +------------------------+             +----------------------------+  |
|               ^                                       ^                 |
+---------------|---------------------------------------|-----------------+
| INFRASTRUCTURE / PERSISTENCE LAYER                    |                 |
|               |                                       | Direct Mapping  |
|  +------------------------+                           | (No ACL)        |
|  |   JdbcOrderRepository  | --------------------------+                 |
|  | (NamedParamJdbcTemp.)  |                                             |
|  +------------------------+                                             |
|               |                                                         |
|               v                                                         |
|      [ PostgreSQL / DB ]                                                |
|  (orders, order_lines, vernac_outbox)                                   |
+-------------------------------------------------------------------------+
```

---

## 2. DDL and Table Conventions

Vernac generates a standardized PostgreSQL table schema for every aggregate and entity.

### 2.1 Table and Column Names
- **Table Name:** Strict conversion of the class name to `snake_case` (e.g., `Order` $\rightarrow$ `order`, `OrderLine` $\rightarrow$ `order_line`, `EnergyStorage` $\rightarrow$ `energy_storage`).
- **Primary Key Column:** Always `id UUID PRIMARY KEY`.
- **Foreign Key Column for Child Entities:** `<aggregate_singular_snake_case>_id` (e.g., `order_id`, `project_id`, `energy_storage_id`).
- **Domain Columns:** Field names converted to `snake_case` (e.g., `customer` $\rightarrow$ `customer`, `itemSku` $\rightarrow$ `item_sku`).

### 2.2 Metadata Columns (Aggregate Root Only)
Every aggregate automatically includes three administrative lifecycle columns:
- `created_at TIMESTAMPTZ NOT NULL`: Aggregate creation timestamp.
- `updated_at TIMESTAMPTZ NOT NULL`: Timestamp of the latest update.
- `version BIGINT NOT NULL DEFAULT 0`: Optimistic locking counter.

### 2.3 Constants in Generated Domain Classes
Generated classes expose table and schema definitions as public constants:
```java
public static final String TABLE_NAME = "order";
public static final String SCHEMA_DDL = "CREATE TABLE IF NOT EXISTS order (...)";
```

### 2.4 Example: DDL for Aggregate and Child Entity
```sql
-- Aggregate table (orders)
CREATE TABLE IF NOT EXISTS order (
    id UUID PRIMARY KEY,
    customer UUID NOT NULL,
    total_amount NUMERIC(19, 4) NOT NULL,
    total_currency VARCHAR(255) NOT NULL,
    status VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

-- Child entity table (order_lines)
CREATE TABLE IF NOT EXISTS order_line (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    sku VARCHAR(255) NOT NULL,
    quantity INTEGER NOT NULL,
    unit_price_amount NUMERIC(19, 4) NOT NULL,
    unit_price_currency VARCHAR(255) NOT NULL
);
```

---

## 3. Type Mapping (PostgreSQL $\leftrightarrow$ Java)

Mapping between Vernac/Java types and PostgreSQL column types follows these fixed rules (`PostgresSchemaUtils`):

| Vernac / Java Type | PostgreSQL Data Type | JDBC RowMapper Read Type (`getObject`) | Column Constraint |
| :--- | :--- | :--- | :--- |
| `int`, `Integer` | `INTEGER` | `java.lang.Integer.class` | `NOT NULL` (unless optional `?`) |
| `long`, `Long` | `BIGINT` | `java.lang.Long.class` | `NOT NULL` (unless optional `?`) |
| `short`, `Short` | `SMALLINT` | `java.lang.Short.class` | `NOT NULL` (unless optional `?`) |
| `double`, `Double` | `DOUBLE PRECISION` | `java.lang.Double.class` | `NOT NULL` (unless optional `?`) |
| `float`, `Float` | `REAL` | `java.lang.Float.class` | `NOT NULL` (unless optional `?`) |
| `boolean`, `Boolean` | `BOOLEAN` | `java.lang.Boolean.class` | `NOT NULL` (unless optional `?`) |
| `BigDecimal` | `NUMERIC(19, 4)` | `java.math.BigDecimal.class` | `NOT NULL` (unless optional `?`) |
| `UUID`, `Id` types | `UUID` | `java.util.UUID.class` | `NOT NULL` (unless optional `?`) |
| `Instant`, `LocalDateTime`, `ZonedDateTime` | `TIMESTAMPTZ` | `rs.getTimestamp(...).toInstant()` | `NOT NULL` (unless optional `?`) |
| `LocalDate` | `DATE` | `java.time.LocalDate.class` | `NOT NULL` (unless optional `?`) |
| `String` | `VARCHAR(255)` | `java.lang.String.class` | `NOT NULL` (unless optional `?`) |
| **Enum Value Object** | Pending explicit mapping design | Not generated | Compilation diagnostic |
| **Other Types** | `TEXT` | `TypeResolver.resolve(...)` | `NOT NULL` (unless optional `?`) |

### Nullability & Wrapper Guarantee
- **Required Fields (`T`):** Generated with `NOT NULL`.
- **Optional Fields (`T?`):** Permit `NULL` in the database.
- **Boxed Types:** The generated RowMapper always uses object wrappers (e.g., `Integer.class`, `Boolean.class`) for all primitives to prevent `NullPointerException`s when encountering database NULLs or optional values.

---

## 4. Flattening Rules for Value Objects (Embedded Mapping)

Value Objects do not have separate database tables; instead, they are flattened inline into the table of the enclosing aggregate or entity (recursive embedded pattern).

### 4.1 Single-Field Value Objects (Transparent Column Name)
When a Value Object has exactly one attribute (e.g., `value ItemSku(String value)` or `value WattHours(int value)`), the column name is derived directly from the aggregate's field name **without any additional suffix**:

```vernac
value ItemSku(String value);

entity OrderLine[OrderLineId](
    ItemSku sku,
    int quantity
);
```
- **Column name in `order_line`:** `sku VARCHAR(255) NOT NULL` (not `sku_value`!).
- **Parameter name:** `:sku`
- **Extraction:** `item.sku().value()`
- **Reconstruction:** `ItemSku.of(rs.getObject("sku", String.class))`

### 4.2 Multi-Field Value Objects (Prefixed Columns)
When a Value Object contains multiple attributes, column names are formed by combining `<fieldname>_<attributename>` in `snake_case`:

```vernac
value Currency(String isoCode);
value Money(BigDecimal amount, Currency currency);

aggregate Order[OrderId](
    CustomerId customer,
    Money total
);
```
- **Flattened columns in `order`:**
  - `total_amount NUMERIC(19, 4) NOT NULL`
  - `total_currency VARCHAR(255) NOT NULL` (recursively through `Currency.isoCode`)
- **Parameters:** `:totalAmount`, `:totalCurrency`
- **Extraction:** `aggregate.total().amount()`, `aggregate.total().currency().isoCode()`
- **Reconstruction:** `Money.of(rs.getObject("total_amount", BigDecimal.class), Currency.of(rs.getObject("total_currency", String.class)))`

### 4.3 Enum Value Objects
Enum value objects no longer contain persistence codes or generated `dbValue()`
methods. Automatic enum persistence is currently rejected with a Vernac diagnostic
at the enum field. This applies to enums nested in value objects as well.

An explicit external-code mapping will be designed alongside schema migrations
and ACL mappings. The generator does not silently fall back to `name()`,
`toString()`, or `ordinal()`. The domain API is specified in the
[enum contract](contracts/enum-value-objects.md).

---

## 5. Aggregate Boundaries & Child Entities (1:N Relations)

Vernac enforces the core DDD principle: **An aggregate is the indivisible unit of data change and consistency.**

### 5.1 Strict Encapsulation of Aggregates
- **No Direct External Aggregates:** An aggregate must never contain another aggregate directly as a field. The compiler enforces referencing by ID type via the `SemanticAnalyzer` (e.g., `CustomerId customer` instead of `Customer customer`).
- **No Cross-Aggregate Loading:** Repositories never perform joins across aggregate boundaries.

### 5.2 Loading Child Entities (`fetch...`)
When an aggregate contains a First-Class Collection of child entities (e.g., `OrderLines lines`), the repository loads them eagerly when calling `byId()` or `find...`:

```java
// 1. Load aggregate root row
Order id = ...;
// 2. Fetch child entities via parent foreign key
OrderLines lines = fetchLines(id);
// 3. Reconstitute aggregate
return Order.reconstitute(id, customer, total, status, lines, createdAt, updatedAt, version);
```

The private `fetchLines` method executes a query filtered by the foreign key:
```sql
SELECT * FROM order_line WHERE order_id = :parentId
```

### 5.3 Saving & Synchronizing Child Entities (`sync...` / 3-Way Diff)
When saving the aggregate (`save()`), the generated repository executes a **full 3-way diff synchronization** on child entities:

1. **Query Existing IDs:**
   ```sql
   SELECT id FROM order_line WHERE order_id = :parentId
   ```
2. **Compute Difference:**
   - **`toDelete`:** Present in DB, but no longer in aggregate $\rightarrow$ `DELETE FROM order_line WHERE id IN (:ids)`
   - **`toInsert`:** New in aggregate, not yet in DB $\rightarrow$ `INSERT INTO order_line (...) VALUES (...)` via `jdbcTemplate.batchUpdate`
   - **`toUpdate`:** Present in both DB and aggregate $\rightarrow$ `UPDATE order_line SET ... WHERE id = :id AND order_id = :parentId` via `jdbcTemplate.batchUpdate`

This ensures that child entity changes (additions, modifications, removals) are synchronized atomically and efficiently.

### 5.4 Optimistic Locking
- On initial creation (`aggregate.persistenceState().version() == 0L`):
  - Executes an `INSERT` and sets `version = 1L`.
- On update (`aggregate.persistenceState().version() > 0L`):
  - Executes an `UPDATE` with condition `WHERE id = :id AND version = :currentVersion`.
  - Increments version in the record (`nextVersion = currentVersion + 1L`).
  - If `rows == 0`, the repository immediately throws `org.springframework.dao.OptimisticLockingFailureException`.
- On deletion (`delete()`):
  - Executes a `DELETE` with condition `WHERE id = :id AND version = :version`.
  - If `rows == 0`, `OptimisticLockingFailureException` is thrown as well.

---

## 6. Repository Methods & Queries

Repositories defined in the Vernac DSL generate standardized method signatures:

```vernac
repository OrderRepository for Order {
    find List<Order> findByStatus(String status);
    find Optional<Order> findByOrderNumber(String orderNumber);
    custom List<Order> findTopOrders(BigDecimal minTotal);
}
```

### 6.1 Standard Methods

#### 1. `byId(IdType id)`
- **Signature:** `Order byId(OrderId id);`
- **No `Optional`:** The method directly returns the aggregate instance.
- **Exception on Not Found:** If no record exists for the provided ID, an `org.vernac.runtime.AggregateNotFoundException` is thrown:
  ```java
  List<Order> results = this.jdbcTemplate.query(sql, Map.of("id", id.value()), (rs, rowNum) -> mapRow(rs));
  if (results.isEmpty()) {
      throw new AggregateNotFoundException(Order.class, id.value());
  }
  return results.getFirst();
  ```
  *DDD Rationale:* Loading by ID in a domain use case normally expects the aggregate to exist. Non-existence is an exceptional condition (`NotFound`) that should not be obscured by defensive null-checks in business code.

#### 2. `save(Aggregate aggregate)`
- **Signature:** `Order save(Order aggregate);`
- Executes `insert()` or `update()` depending on `version()`, synchronizes child entities, dispatches outbox events, and returns the aggregate with updated version metadata.

#### 3. `delete(Aggregate aggregate)`
- **Signature:** `void delete(Order aggregate);`
- Deletes the aggregate considering the version token (`OptimisticLockingFailureException` on conflict).

### 6.2 Automatically Generated `find...` Methods
Methods declared with the `find` keyword are automatically translated by the compiler into parameterized SQL queries:

- **DSL Declaration:**
  ```vernac
  find List<Order> findByStatus(String status);
  find Optional<Order> findByCustomerAndStatus(CustomerId customer, String status);
  ```
- **Generated SQL WHERE Clause:**
  - Parameters are converted to `snake_case` column names and joined with `AND`:
    `SELECT * FROM order WHERE status = :status` or
    `SELECT * FROM order WHERE customer = :customer AND status = :status`
- **Return Mapping:**
  - If return type is `Optional<Order>`:
    `return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());`
  - If return type is `List<Order>`:
    `return this.jdbcTemplate.query(sql, params, (rs, rowNum) -> mapRow(rs));`

---

## 7. Custom Repository Methods & Custom Spring Beans (`custom ...`)

For complex queries, reporting, dynamic filters, or database-specific features (e.g., window functions, full-text search, stored procedures), Vernac supports the **fragment interface pattern**.

### 7.1 Mechanism & Code Generation

When a `custom` method is declared in a repository:
```vernac
repository OrderRepository for Order {
    find List<Order> findByStatus(String status);
    custom List<Order> findTopOrders(BigDecimal threshold);
}
```

the compiler generates three components:

1. **Fragment Interface (`OrderRepositoryCustom` in the domain package):**
   ```java
   package com.example.domain;

   import java.math.BigDecimal;
   import java.util.List;

   public interface OrderRepositoryCustom {
       List<Order> findTopOrders(BigDecimal threshold);
   }
   ```
2. **Main Interface (`OrderRepository`):**
   Automatically extends the fragment interface:
   ```java
   public interface OrderRepository extends OrderRepositoryCustom {
       Order byId(OrderId id);
       Order save(Order aggregate);
       void delete(Order aggregate);
       List<Order> findByStatus(String status);
   }
   ```
3. **Delegation in `JdbcOrderRepository`:**
   The generated JDBC repository injects the custom interface and delegates the call:
   ```java
   @Repository
   @Transactional(propagation = Propagation.MANDATORY)
   public class JdbcOrderRepository implements OrderRepository {

       private final NamedParameterJdbcTemplate jdbcTemplate;
       private final OrderRepositoryCustom customDelegate;
       private final EventDispatcher eventDispatcher;

       public JdbcOrderRepository(
               NamedParameterJdbcTemplate jdbcTemplate,
               OrderRepositoryCustom customDelegate,
               EventDispatcher eventDispatcher
       ) {
           this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
           this.customDelegate = Objects.requireNonNull(customDelegate, "customDelegate must not be null");
           this.eventDispatcher = Objects.requireNonNull(eventDispatcher, "eventDispatcher must not be null");
       }

       @Override
       public List<Order> findTopOrders(BigDecimal threshold) {
           return this.customDelegate.findTopOrders(threshold);
       }
       ...
   }
   ```

---

### 7.2 Implementation Example of a Custom Bean

To provide implementations for `custom` methods, create a Spring `@Component` or `@Repository` bean in the infrastructure package that implements the `<RepoName>Custom` interface:

```java
package com.example.infrastructure.adapter.db;

import com.example.domain.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

@Component
public class CustomOrderRepositoryImpl implements OrderRepositoryCustom {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public CustomOrderRepositoryImpl(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<Order> findTopOrders(BigDecimal threshold) {
        String sql = """
            SELECT id, customer, total_amount, total_currency, status, created_at, updated_at, version
            FROM order
            WHERE total_amount >= :threshold
            ORDER BY total_amount DESC
            LIMIT 10
            """;

        Map<String, Object> params = Map.of("threshold", threshold);

        return this.jdbcTemplate.query(sql, params, (rs, rowNum) -> {
            OrderId id = OrderId.of(rs.getObject("id", UUID.class));
            CustomerId customer = CustomerId.of(rs.getObject("customer", UUID.class));
            Money total = Money.of(
                    rs.getObject("total_amount", BigDecimal.class),
                    Currency.of(rs.getString("total_currency"))
            );
            String status = rs.getString("status");
            var createdAt = rs.getTimestamp("created_at").toInstant();
            var updatedAt = rs.getTimestamp("updated_at").toInstant();
            long version = rs.getLong("version");

            // Fetch child entities for matched rows
            OrderLines lines = fetchLinesForOrder(id);

            return Order.reconstitute(id, customer, total, status, lines, createdAt, updatedAt, version);
        });
    }

    private OrderLines fetchLinesForOrder(OrderId orderId) {
        String sql = "SELECT * FROM order_line WHERE order_id = :parentId";
        List<OrderLine> list = this.jdbcTemplate.query(sql, Map.of("parentId", orderId.value()), (rs, rowNum) -> {
            OrderLineId lineId = OrderLineId.of(rs.getObject("id", UUID.class));
            ItemSku sku = ItemSku.of(rs.getString("sku"));
            int qty = rs.getInt("quantity");
            Money unitPrice = Money.of(
                    rs.getObject("unit_price_amount", BigDecimal.class),
                    Currency.of(rs.getString("unit_price_currency"))
            );
            return OrderLine.create(lineId, sku, unitPrice, qty);
        });
        return OrderLines.of(list);
    }
}
```

Spring Boot automatically detects `CustomOrderRepositoryImpl` via component scanning and injects the bean into the generated `JdbcOrderRepository`.

---

## 8. Summary Reference Table

| Topic / Feature | Convention / Rule | Description / Generated Behavior |
| :--- | :--- | :--- |
| **Table Name** | `toSnakeCase(TypeName)` | `Order` $\rightarrow$ `order`, `OrderLine` $\rightarrow$ `order_line` |
| **Primary Key** | `id UUID PRIMARY KEY` | Standard ID column on every aggregate and entity |
| **Foreign Key** | `<aggregate_singular>_id` | Foreign key column in child entity tables (e.g., `order_id`) |
| **Metadata** | `created_at`, `updated_at`, `version` | Automatic auditing and optimistic locking columns on aggregate roots |
| **Value Object (Single)** | Field name without suffix | `ItemSku sku` $\rightarrow$ column `sku VARCHAR(255)` |
| **Value Object (Multi)** | `<field>_<attribute>` | `Money total` $\rightarrow$ columns `total_amount`, `total_currency` |
| **Enum Value Object** | Pending explicit mapping | Rejected until the mapping contract is implemented |
| **1:N Child Entities** | Dedicated table with FK | Automatic eager loading (`fetch...`) & 3-way diff (`sync...`) |
| **byId(id)** | `Agg byId(AggId id)` | Returns aggregate directly; throws `AggregateNotFoundException` on 404 |
| **save(agg)** | `Agg save(Agg agg)` | Distinguishes INSERT (`version == 0`) / UPDATE, synchronizes child entities, dispatches outbox events |
| **find... Methods** | `find <Ret> <name>(<params>)` | Generates SQL with parameterized `WHERE` clause (`AND` combination) |
| **custom... Methods** | `custom <Ret> <name>(<params>)` | Generates `<Repo>Custom` interface; delegates to custom Spring bean |
| **Optimistic Lock** | `version BIGINT` | Throws `OptimisticLockingFailureException` upon concurrent conflicts |
| **Transactions** | `@Transactional(MANDATORY)` | Repositories always execute within an existing active transaction |
