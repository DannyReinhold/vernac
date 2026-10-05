# Event-Driven Architecture & Transactional Outbox in Vernac

Dieses Dokument beschreibt die Architektur, Lebenszyklen, Datenbanktabellen und Generierungsregeln für **Domain Events** (`event`), das **Transactional Outbox Pattern** (`vernac_outbox`, `JdbcEventDispatcher`) und **Event Listener** (`listener`) in Vernac (abgeleitet aus `EventGenerator`, `ListenerGenerator`, `JdbcEventDispatcher`, `AggregateRoot` und `SemanticAnalyzer`).

---

## 1. Übersicht & Architektur

Vernac bietet integrierte Unterstützung für ereignisgesteuerte Architekturen (EDA) nach DDD-Prinzipien:

- **Domain Events (`event`):** Repräsentieren unveränderliche fachliche Fakten, die in der Vergangenheit stattgefunden haben (z. B. `OrderPlaced`, `PaymentReceived`, `StorageCharged`).
- **Event-Registrierung im Aggregat:** Zustandsändernde Methoden auf Aggregaten erzeugen und registrieren Events über `registerEvent(...)`.
- **Atomares Persistieren & Transactional Outbox:** Beim Aufruf von `repository.save(aggregate)` werden die registrierten Events innerhalb derselben Datenbanktransaktion wie das Aggregat in die Outbox-Tabelle `vernac_outbox` geschrieben (oder bei In-Memory-Events direkt publiziert).
- **Asynchrone Entkopplung & Listener:** Nach erfolgreichem Commit der Transaktion (`AFTER_COMMIT`) verarbeiten Spring-Listener (`listener`) die Events ohne Blockierung der Haupttransaktion.

```
+---------------------------------------------------------------------------------------+
| 1. DOMAIN AGGREGATE (z.B. Order)                                                      |
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

### 2.1 Deklaration in der DSL
Domain Events werden mit ihren Attributen und dem optionalen Dispatch-Modus deklariert:

```vernac
// Standard: Outbox Event (wird persistent in vernac_outbox gespeichert)
event OrderPaid(
    OrderId orderId,
    CustomerId customer,
    Money amount
) outbox;

// In-Memory Event (wird direkt im Spring ApplicationContext publiziert)
event CacheInvalidated(
    String cacheKey
);
```

### 2.2 Eigenschaften & Generierter Code
Jedes generierte Event implementiert das Runtime-Interface `org.vernac.runtime.DomainEvent` und ist strikt **unveränderlich (immutable)**:

```java
public final class OrderPaid implements DomainEvent {

    public static final String EVENT_TYPE = "ORDER_PAID";

    private final UUID eventId;
    private final Instant occurredOn;
    private final OrderId orderId;
    private final CustomerId customer;
    private final Money amount;

    // Statische Factory-Methode (erzeugt automatisch UUID und Zeitstempel)
    public static OrderPaid create(OrderId orderId, CustomerId customer, Money amount) {
        return new OrderPaid(UUID.randomUUID(), Instant.now(), orderId, customer, amount);
    }
    
    // Getters, immutability guarantees, equals & hashCode basierend auf eventId
}
```

- **Automatische Metadaten:**
  - `eventId`: Eindeutige UUID zur Idempotenz-Prüfung und Deduplizierung.
  - `occurredOn`: UTC-Zeitstempel (`Instant.now()`) des Auftretens.
  - `eventType`: Standardisierter `UPPER_SNAKE_CASE` Typ-Identifikator (`ORDER_PAID`).
- **Compiler-Restriktionen:** Der `SemanticAnalyzer` verbietet veränderliche Felder (`mut`) in Events (`Events must represent past facts and be strictly immutable`).

---

## 3. Event-Registrierung in Aggregaten

Aggregate erben von `org.vernac.runtime.AggregateRoot`:

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

### Lebenszyklus im `AggregateRoot`:
1. `registerEvent(event)`: Fügt das Event einer internen Liste `registeredEvents` hinzu.
2. `registeredEvents()`: Gibt eine unveränderliche Kopie der bisher registrierten Events zurück.
3. `clearEvents()`: Wird nach erfolgreicher Übergabe an den `EventDispatcher` im Repository aufgerufen, um Doppelauslieferungen im selben Aggregat-Lebenszyklus zu verhindern.

---

## 4. Das Transactional Outbox Pattern (`vernac_outbox`)

Um das Problem von "Dual Writes" (gleichzeitiges Schreiben in Datenbank und Nachrichtentreiber ohne 2PC-Transaktionen) zu lösen, verwendet Vernac das **Transactional Outbox Pattern**.

### 4.1 Tabellenschema & DDL (`vernac_outbox`)
In PostgreSQL wird die Tabelle `vernac_outbox` angelegt:

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

### 4.2 Spaltenbedeutung:
| Spalte | Typ | Beschreibung |
| :--- | :--- | :--- |
| `id` | `UUID` | Eindeutige Event-ID (entspricht `event.eventId()`) |
| `event_type` | `VARCHAR(128)` | Fachlicher Ereignistyp (z. B. `ORDER_PAID`) |
| `aggregate_type` | `VARCHAR(128)` | Name des Aggregats (z. B. `Order`) |
| `aggregate_id` | `VARCHAR(128)` | String-Repräsentation der Aggregat-ID |
| `payload` | `JSONB` | Vollständige JSON-Serialisierung des Events (Jackson) |
| `occurred_on` | `TIMESTAMPTZ` | Zeitpunkt des fachlichen Auftretens |
| `created_at` | `TIMESTAMPTZ` | Einfügezeitpunkt in die Outbox |
| `processed_at` | `TIMESTAMPTZ` | Zeitpunkt des erfolgreichen Versands an Message Broker |
| `status` | `VARCHAR(32)` | Status: `PENDING`, `PROCESSED`, `FAILED` |
| `retry_count` | `INT` | Anzahl bisheriger Zustellversuche |
| `last_error` | `TEXT` | Letzte Fehlermeldung bei Fehlversuchen |

---

## 5. Dispatcher & Ausführung (`JdbcEventDispatcher`)

Die Komponente `JdbcEventDispatcher` aus `vernac-runtime` implementiert die Trennung zwischen Outbox- und In-Memory-Events:

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
                // Direkte Veröffentlichung im Spring ApplicationContext
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

## 6. Event Listener (`listener`)

In Vernac deklarierte Listener reagieren auf publizierte Events:

```vernac
listener SendOrderConfirmation on OrderPaid use (
    EmailPort emailService
) {
    emailService.sendConfirmation(event.customer(), event.orderId(), event.amount());
}
```

### Generierter Java-Code:
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

### Wichtige Eigenschaften:
- **`@TransactionalEventListener(phase = AFTER_COMMIT)`:** Der Listener wird erst ausgeführt, wenn die Transaktion, die das Event ausgelöst hat, erfolgreich in der Datenbank committet wurde.
- **Fehlerisolation:** Wirft der Listener eine Exception, gefährdet dies nicht den bereits festgeschriebenen Zustand des Aggregats in der Datenbank.
- **Dependency Injection:** Alle im `use (...)`-Block deklarierten Services oder Ports werden automatisch per Konstruktor injiziert.

---

## 7. Zusammenfassende Referenztabelle

| Konzept | Vernac Syntax | Generiertes Artefakt / Verhalten |
| :--- | :--- | :--- |
| **Domain Event** | `event Name(...) [outbox];` | Immutable Klasse mit `eventId`, `occurredOn`, `EVENT_TYPE` |
| **Event Registrierung** | `registerEvent(...)` | Registriert Event im `AggregateRoot` |
| **Outbox Speicherung** | `save(aggregate)` | Schreibt atomar `INSERT INTO vernac_outbox (...)` |
| **Outbox Index** | Partial Index | `idx_vernac_outbox_pending` für performantes Polling |
| **Event Listener** | `listener L on Event use (...)` | Spring `@Component` mit `@TransactionalEventListener(phase = AFTER_COMMIT)` |
| **In-Memory Event** | `event Name(...)` (ohne outbox) | Publiziert direkt über Spring `ApplicationEventPublisher` |
