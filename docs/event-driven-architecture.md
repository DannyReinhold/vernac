# Event-Driven Architecture & Transactional Outbox in Vernac

This document describes the architecture, lifecycles, database tables, and generation rules for **Domain Events** (`event`), the **Transactional Outbox Pattern** (`vernac_outbox`, `JdbcEventDispatcher`), and **Event Listeners** (`listener`) in Vernac (derived from `EventGenerator`, `ListenerGenerator`, `JdbcEventDispatcher`, `AggregateRoot`, and `SemanticAnalyzer`).

---

## 1. Overview & Architecture

Vernac provides built-in support for Event-Driven Architectures (EDA) following DDD principles:

- **Domain Events (`event`):** Represent immutable business facts that occurred in the past (e.g., `OrderPlaced`, `PaymentReceived`, `StorageCharged`).
- **Event Registration in Aggregates:** State-mutating methods on aggregates create and register events via `registerEvent(...)`.
- **Atomic Persistence & Transactional Outbox:** When calling `repository.save(aggregate)`, registered events are written to the outbox table `vernac_outbox` within the same database transaction as the aggregate (or published directly for in-memory events).
- **Asynchronous Decoupling & Listeners:** After a successful transaction commit (`AFTER_COMMIT`), Spring listeners (`listener`) process the events without blocking the primary business transaction.

```
+---------------------------------------------------------------------------------------+
| 1. DOMAIN AGGREGATE (e.g., Order)                                                     |
|    order.completeOrder()                                                              |
|      -> registerEvent(OrderPaid.create(this.id, this.customer))                       |
+---------------------------------------------------------------------------------------+
                                           |
                                           v
+---------------------------------------------------------------------------------------+
| 2. REPOSITORY & TRANSACTIONAL BOUNDARY (@Transactional MANDATORY)                     |
|    orderRepository.save(order)                                                        |
|      |                                                                                |
|      +---> INSERT / UPDATE order SET ...                                              |
|      |                                                                                |
|      +---> eventDispatcher.dispatch("Order", order.id(), order.registeredEvents())     |
|              |                                                                        |
|              v (Transactional Outbox)                                                 |
|            INSERT INTO vernac_outbox (id, event_type, payload, status, ...)           |
+---------------------------------------------------------------------------------------+
                                           |
                              COMMIT TRANSACTION (PostgreSQL)
                                           |
                                           v
+---------------------------------------------------------------------------------------+
| 3. ASYNCHRONOUS CONSUMPTION / RELAY                                                   |
|                                                                                       |
|   A. Spring Context Listener:                                                         |
|      @TransactionalEventListener(phase = AFTER_COMMIT)                                |
|      public class SendInvoiceOnOrderPaid { void on(OrderPaid event) { ... } }         |
|                                                                                       |
|   B. Outbox Poller / Relay (Message Broker: Kafka, RabbitMQ, SQS, etc.)               |
|      SELECT * FROM vernac_outbox WHERE status = 'PENDING' FOR UPDATE SKIP LOCKED      |
+---------------------------------------------------------------------------------------+
```

---

## 2. Domain Events (`event`)

### 2.1 DSL Declaration
Domain events are declared with their attributes and an optional dispatch mode:

```vernac
// Default: Outbox Event (persistently stored in vernac_outbox)
event OrderPaid(
    OrderId orderId,
    CustomerId customer,
    Money amount
) outbox;

// In-Memory Event (published directly within the Spring ApplicationContext)
event CacheInvalidated(
    String cacheKey
);
```

### 2.2 Properties & Generated Code
Every generated event implements the runtime interface `org.vernac.runtime.DomainEvent` and is strictly **immutable**:

```java
public final class OrderPaid implements DomainEvent {

    public static final String EVENT_TYPE = "ORDER_PAID";

    private final UUID eventId;
    private final Instant occurredOn;
    private final OrderId orderId;
    private final CustomerId customer;
    private final Money amount;

    // Static factory method (automatically generates UUID and timestamp)
    public static OrderPaid create(OrderId orderId, CustomerId customer, Money amount) {
        return new OrderPaid(UUID.randomUUID(), Instant.now(), orderId, customer, amount);
    }
    
    // Getters, immutability guarantees, equals & hashCode based on eventId
}
```

- **Automatic Metadata:**
  - `eventId`: Unique UUID for idempotency checks and deduplication.
  - `occurredOn`: UTC timestamp (`Instant.now()`) when the event occurred.
  - `eventType`: Standardized `UPPER_SNAKE_CASE` type identifier (`ORDER_PAID`).
- **Compiler Restrictions:** The `SemanticAnalyzer` forbids mutable fields (`mut`) in events (`Events must represent past facts and be strictly immutable`).

---

## 3. Event Registration in Aggregates

Aggregates inherit from `org.vernac.runtime.AggregateRoot`:

```vernac
aggregate Order[OrderId](
    CustomerId customer,
    mut String status,
    Money total
) {
    public void pay() {
        status("PAID");
        registerEvent(OrderPaid.create(this.id, this.customer, this.total));
    }
}
```

### Lifecycle in `AggregateRoot`:
1. `registerEvent(event)`: Adds the event to an internal list `registeredEvents`.
2. `registeredEvents()`: Returns an unmodifiable copy of registered events.
3. `clearEvents()`: Invoked by the repository after successfully handing events over to the `EventDispatcher`, preventing duplicate dispatching during the same aggregate lifecycle.

---

## 4. The Transactional Outbox Pattern (`vernac_outbox`)

To solve the dual-write problem (concurrently writing to the database and a message broker without two-phase commit / 2PC), Vernac uses the **Transactional Outbox Pattern**.

### 4.1 Table Schema & DDL (`vernac_outbox`)
In PostgreSQL, the `vernac_outbox` table is created as follows:

```sql
CREATE TABLE IF NOT EXISTS vernac_outbox (
    id UUID PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    aggregate_type VARCHAR(128) NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    payload JSONB NOT NULL,
    occurred_on TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    last_error TEXT
);

CREATE INDEX IF NOT EXISTS idx_vernac_outbox_pending 
ON vernac_outbox (status, created_at) 
WHERE status = 'PENDING';
```

### 4.2 Column Descriptions:
| Column | Type | Description |
| :--- | :--- | :--- |
| `id` | `UUID` | Unique event ID (corresponds to `event.eventId()`) |
| `event_type` | `VARCHAR(128)` | Business event type name (e.g., `ORDER_PAID`) |
| `aggregate_type` | `VARCHAR(128)` | Name of the aggregate (e.g., `Order`) |
| `aggregate_id` | `VARCHAR(128)` | String representation of the aggregate ID |
| `payload` | `JSONB` | Full JSON serialization of the event (Jackson) |
| `occurred_on` | `TIMESTAMPTZ` | Timestamp when the business event occurred |
| `created_at` | `TIMESTAMPTZ` | Insertion timestamp into the outbox |
| `processed_at` | `TIMESTAMPTZ` | Timestamp of successful delivery to message broker |
| `status` | `VARCHAR(32)` | Status: `PENDING`, `PROCESSED`, `FAILED` |
| `retry_count` | `INT` | Number of previous delivery attempts |
| `last_error` | `TEXT` | Last error message upon delivery failure |

---

## 5. Dispatcher & Execution (`JdbcEventDispatcher`)

The `JdbcEventDispatcher` component in `vernac-runtime` implements the distinction between outbox and in-memory events:

```java
@Component
public class JdbcEventDispatcher implements EventDispatcher {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Override
    public void dispatch(String aggregateType, String aggregateId, List<DomainEvent> events) {
        if (events == null || events.isEmpty()) return;

        List<MapSqlParameterSource> outboxBatch = new ArrayList<>();

        for (DomainEvent event : events) {
            if (event instanceof OutboxDomainEvent outbox) {
                outboxBatch.add(new MapSqlParameterSource()
                        .addValue("id", outbox.eventId())
                        .addValue("eventType", outbox.eventType())
                        .addValue("aggregateType", aggregateType)
                        .addValue("aggregateId", aggregateId)
                        .addValue("payload", serializePayload(outbox))
                        .addValue("occurredOn", Timestamp.from(outbox.occurredOn()))
                        .addValue("createdAt", Timestamp.from(Instant.now()))
                        .addValue("status", "PENDING"));
            } else {
                // Direct publication to Spring ApplicationContext
                this.eventPublisher.publishEvent(event);
            }
        }

        if (!outboxBatch.isEmpty()) {
            String sql = """
                    INSERT INTO vernac_outbox 
                    (id, event_type, aggregate_type, aggregate_id, payload, occurred_on, created_at, status)
                    VALUES (:id, :eventType, :aggregateType, :aggregateId, :payload::jsonb, :occurredOn, :createdAt, :status)
                    """;
            this.jdbcTemplate.batchUpdate(sql, outboxBatch.toArray(MapSqlParameterSource[]::new));
        }
    }
}
```

---

## 6. Event Listeners (`listener`)

Listeners declared in Vernac subscribe to published events:

```vernac
listener SendOrderConfirmation on OrderPaid use (
    EmailPort emailService
) {
    emailService.sendConfirmation(event.customer(), event.orderId(), event.amount());
}
```

### Generated Java Code:
```java
@Component
public class SendOrderConfirmation {

    private final EmailPort emailService;

    public SendOrderConfirmation(EmailPort emailService) {
        this.emailService = Objects.requireNonNull(emailService, "emailService must not be null");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(OrderPaid event) {
        emailService.sendConfirmation(event.customer(), event.orderId(), event.amount());
    }
}
```

### Key Characteristics:
- **`@TransactionalEventListener(phase = AFTER_COMMIT)`:** The listener executes only after the transaction that triggered the event has successfully committed to the database.
- **Fault Isolation:** If the listener throws an exception, it does not jeopardize or roll back the already committed aggregate state in the database.
- **Dependency Injection:** All services or ports declared in the `use (...)` block are automatically injected via constructor injection.

---

## 7. Summary Reference Table

| Concept | Vernac Syntax | Generated Artifact / Behavior |
| :--- | :--- | :--- |
| **Domain Event** | `event Name(...) [outbox];` | Immutable class with `eventId`, `occurredOn`, `EVENT_TYPE` |
| **Event Registration** | `registerEvent(...)` | Registers event inside `AggregateRoot` |
| **Outbox Persistence** | `save(aggregate)` | Atomically executes `INSERT INTO vernac_outbox (...)` |
| **Outbox Index** | Partial Index | `idx_vernac_outbox_pending` for performant polling |
| **Event Listener** | `listener L on Event use (...)` | Spring `@Component` with `@TransactionalEventListener(phase = AFTER_COMMIT)` |
| **In-Memory Event** | `event Name(...)` (without outbox) | Publishes directly via Spring `ApplicationEventPublisher` |
