# Datenbank- und Repository-Mapping in Vernac

Dieses Dokument beschreibt die Architektur, Konventionen, DDL-Generierung, Typ-Mappings, Aggregatgrenzen und Erweiterungsmechanismen für **Repositories** und das **relationale Datenbank-Mapping (JDBC / PostgreSQL)** in der Vernac DSL (abgeleitet aus `RepositoryGenerator`, `PostgresSchemaUtils`, `AggregateGenerator`, `EntityGenerator` und `SemanticAnalyzer`).

---

## 1. Architektur & Konzept

Vernac implementiert relationale Persistenz nach den Prinzipien des **Domain-Driven Designs (DDD)** und der **Hexagonalen Architektur**:

- **Repository-Interface (Domänen-Layer):** Deklariert die fachlichen Zugriffsmethoden (`byId`, `save`, `delete`, `find...`, `custom...`) und liegt standardmäßig im Domänen-Package (`<basePackage>.domain`). Die Domäne hängt von keinerlei Persistenz-Framework ab.
- **JDBC-Implementierung (Infrastruktur-Layer):** Der Compiler generiert automatisch ein `Jdbc<Aggregate>Repository` als Spring `@Repository`-Komponente unter Verwendung von Spring `NamedParameterJdbcTemplate` im Adapter-Package (`<basePackage>.infrastructure.adapter.db` bzw. `<basePackage>.adapter.db`).
- **Verzicht auf Anti-Corruption Layer (ACL):** Im Gegensatz zu externen Outbound-Ports (z. B. REST-APIs fremder Drittsysteme) wird beim internen Datenbank-Mapping bewusst auf eine DTO-/Anti-Corruption-Schicht verzichtet. Die relationale Datenbank wird als integrierter Bestandteil der eigenen Anwendung betrachtet; Tabellenspalten werden direkt auf die Attribute der Domänenmodelle (`AggregateRoot`, `Entity`, `ValueObject`) abgebildet.
- **Transaktionsbindung & Outbox-Dispatching:** Jedes JDBC-Repository erzwingt eine aktive Transaktion (`@Transactional(propagation = Propagation.MANDATORY)`). Nach jedem erfolgreichen `save()` werden die im Aggregat registrierten Domain Events atomar innerhalb derselben Transaktion an den `EventDispatcher` übergeben (Transactional Outbox Pattern).

```
+-------------------------------------------------------------------------+
| DOMAIN LAYER                                                            |
|                                                                         |
|  +------------------------+             +----------------------------+  |
|  |  Repository Interface  | <---------- |       Aggregate Root       |  |
|  |   (z.B. OrderRepository)             |  (z.B. Order mit Lines)    |  |
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

## 2. DDL- und Tabellen-Konventionen

Vernac erzeugt für jedes Aggregat und jede Entity ein standardisiertes PostgreSQL-Tabellenschema.

### 2.1 Tabellen- und Spaltennamen
- **Tabellenname:** Strikte Konvertierung des Klassennamens in `snake_case` (z. B. `Order` $\rightarrow$ `order`, `OrderLine` $\rightarrow$ `order_line`, `EnergyStorage` $\rightarrow$ `energy_storage`).
- **Primärschlüssel-Spalte:** Immer `id UUID PRIMARY KEY`.
- **Fremdschlüssel-Spalte für Child-Entities:** `<aggregate_singular_snake_case>_id` (z. B. `order_id`, `project_id`, `energy_storage_id`).
- **Fachliche Spalten:** Feldnamen in `snake_case` (z. B. `customer` $\rightarrow$ `customer`, `itemSku` $\rightarrow$ `item_sku`).

### 2.2 Metadaten-Spalten (nur Aggregate Root)
Jedes Aggregat verfügt automatisch über drei administrative Lebenszyklus-Spalten:
- `created_at TIMESTAMPTZ NOT NULL`: Erstellungszeitpunkt des Aggregats.
- `updated_at TIMESTAMPTZ NOT NULL`: Zeitpunkt der letzten Aktualisierung.
- `version BIGINT NOT NULL DEFAULT 0`: Optimistischer Sperrzähler (Optimistic Locking).

### 2.3 Konstanten in generierten Domänenklassen
In den generierten Klassen stehen die Tabellen- und Schema-Definitionen als öffentliche Konstanten zur Verfügung:
```java
public static final String TABLE_NAME = "order";
public static final String SCHEMA_DDL = "CREATE TABLE IF NOT EXISTS order (...)";
```

### 2.4 Beispiel: DDL für Aggregat und Child-Entity
```sql
-- Aggregat-Tabelle (orders)
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

-- Child-Entity Tabelle (order_lines)
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

## 3. Typ-Mapping (PostgreSQL $\leftrightarrow$ Java)

Das Mapping zwischen Vernac-/Java-Typen und PostgreSQL-Spaltentypen erfolgt nach folgenden festen Regeln (`PostgresSchemaUtils`):

| Vernac / Java Typ | PostgreSQL Datentyp | JDBC RowMapper Lese-Typ (`getObject`) | Spalten-Constraint |
| :--- | :--- | :--- | :--- |
| `int`, `Integer` | `INTEGER` | `java.lang.Integer.class` | `NOT NULL` (wenn nicht optional `?`) |
| `long`, `Long` | `BIGINT` | `java.lang.Long.class` | `NOT NULL` (wenn nicht optional `?`) |
| `short`, `Short` | `SMALLINT` | `java.lang.Short.class` | `NOT NULL` (wenn nicht optional `?`) |
| `double`, `Double` | `DOUBLE PRECISION` | `java.lang.Double.class` | `NOT NULL` (wenn nicht optional `?`) |
| `float`, `Float` | `REAL` | `java.lang.Float.class` | `NOT NULL` (wenn nicht optional `?`) |
| `boolean`, `Boolean` | `BOOLEAN` | `java.lang.Boolean.class` | `NOT NULL` (wenn nicht optional `?`) |
| `BigDecimal` | `NUMERIC(19, 4)` | `java.math.BigDecimal.class` | `NOT NULL` (wenn nicht optional `?`) |
| `UUID`, `Id`-Typen | `UUID` | `java.util.UUID.class` | `NOT NULL` (wenn nicht optional `?`) |
| `Instant`, `LocalDateTime`, `ZonedDateTime` | `TIMESTAMPTZ` | `rs.getTimestamp(...).toInstant()` | `NOT NULL` (wenn nicht optional `?`) |
| `LocalDate` | `DATE` | `java.time.LocalDate.class` | `NOT NULL` (wenn nicht optional `?`) |
| `String` | `VARCHAR(255)` | `java.lang.String.class` | `NOT NULL` (wenn nicht optional `?`) |
| **Enum Value Object** | `VARCHAR(32)` | `java.lang.String.class` | `NOT NULL` (wenn nicht optional `?`) |
| **Sonstige Typen** | `TEXT` | `TypeResolver.resolve(...)` | `NOT NULL` (wenn nicht optional `?`) |

### Nullability & Wrapper-Garantie
- **Pflichtfelder (`T`):** Werden mit `NOT NULL` generiert.
- **Optionale Felder (`T?`):** Erlauben `NULL` in der Datenbank.
- **Boxed Types:** Im generierten RowMapper werden für alle Primitiven stets Object-Wrapper (z. B. `Integer.class`, `Boolean.class`) verwendet, um Null-Pointer-Exceptions bei optionalen Werten oder Datenbank-Nulls zu vermeiden.

---

## 4. Flattening-Regeln für Value Objects (Embedded Mapping)

Value Objects besitzen keine eigene Datenbank-Tabelle, sondern werden inline in die Tabelle des umschließenden Aggregats bzw. der Entity geflacht (rekursives Embedded-Muster).

### 4.1 Single-Field Value Objects (Transparenter Spaltenname)
Besitzt ein Value Object exakt ein Attribut (z. B. `value ItemSku(String value)` oder `value WattHours(int value)`), wird der Spaltenname **ohne zusätzlichen Namenszusatz** direkt vom Feldnamen des Aggregats abgeleitet:

```vernac
value ItemSku(String value);

entity OrderLine[OrderLineId](
    ItemSku sku,
    int quantity
);
```
- **Spaltenname in `order_line`:** `sku VARCHAR(255) NOT NULL` (nicht `sku_value`!).
- **Parametername:** `:sku`
- **Extraktion:** `item.sku().value()`
- **Rekonstruktion:** `ItemSku.of(rs.getObject("sku", String.class))`

### 4.2 Multi-Field Value Objects (Präfix-Spalten)
Besitzt ein Value Object mehrere Attribute, werden die Spaltennamen durch Kombination aus `<feldname>_<attributname>` in `snake_case` gebildet:

```vernac
value Currency(String isoCode);
value Money(BigDecimal amount, Currency currency);

aggregate Order[OrderId](
    CustomerId customer,
    Money total
);
```
- **Geflachte Spalten in `order`:**
  - `total_amount NUMERIC(19, 4) NOT NULL`
  - `total_currency VARCHAR(255) NOT NULL` (rekursiv über `Currency.isoCode`)
- **Parameter:** `:totalAmount`, `:totalCurrency`
- **Extraktion:** `aggregate.total().amount()`, `aggregate.total().currency().isoCode()`
- **Rekonstruktion:** `Money.of(rs.getObject("total_amount", BigDecimal.class), Currency.of(rs.getObject("total_currency", String.class)))`

### 4.3 Enum Value Objects
Enum Value Objects werden als String-Spalte persistiert:
```vernac
value OrderStatus enum (
    NEW = "NEW",
    PAID = "PAID",
    CANCELLED = "CANCELLED"
);

aggregate Order[OrderId](
    mut OrderStatus status
);
```
- **Spalte:** `status VARCHAR(32) NOT NULL`
- **Speichern:** `aggregate.status().dbValue()`
- **Laden:** `OrderStatus.fromDbValue(rs.getObject("status", String.class))` (bzw. bei optionalen Enums `Optional.ofNullable(...).map(OrderStatus::fromDbValue)`).

---

## 5. Aggregatgrenzen & Kind-Entities (1:N Relationen)

Vernac setzt das zentrale DDD-Prinzip um: **Ein Aggregat ist die unteilbare Einheit von Datenänderung und Konsistenz.**

### 5.1 Strikte Kapselung von Aggregaten
- **Keine direkten Fremd-Aggregate:** Ein Aggregat darf niemals ein anderes Aggregat direkt als Feld enthalten. Der Compiler erzwingt über den `SemanticAnalyzer` die Referenzierung über den ID-Typ (z. B. `CustomerId customer` statt `Customer customer`).
- **Kein Fremd-Aggregat-Laden:** Repositories führen niemals Joins über Aggregatgrenzen hinweg aus.

### 5.2 Laden von Kind-Entities (`fetch...`)
Enthält ein Aggregat eine First-Class Collection von Kind-Entities (z. B. `OrderLines lines`), lädt das Repository diese beim Aufruf von `byId()` oder `find...` vollständig mit:

```java
// 1. Aggregat-Root Zeile laden
Order id = ...;
// 2. Kind-Entities über Parent-Foreign-Key nachladen
OrderLines lines = fetchLines(id);
// 3. Aggregat rekonstituieren
return Order.reconstitute(id, customer, total, status, lines, createdAt, updatedAt, version);
```

Die private Methode `fetchLines` führt die Abfrage über den Fremdschlüssel aus:
```sql
SELECT * FROM order_line WHERE order_id = :parentId
```

### 5.3 Speichern & Synchronisieren von Kind-Entities (`sync...` / 3-Wege-Diff)
Beim Speichern des Aggregats (`save()`) führt das generierte Repository eine **vollständige 3-Wege-Diff-Synchronisation** auf den Kind-Entities aus:

1. **Bestehende IDs ermitteln:**
   ```sql
   SELECT id FROM order_line WHERE order_id = :parentId
   ```
2. **Differenz berechnen:**
   - **`toDelete`:** In der DB vorhanden, aber nicht mehr im Aggregat $\rightarrow$ `DELETE FROM order_line WHERE id IN (:ids)`
   - **`toInsert`:** Neu im Aggregat, noch nicht in der DB $\rightarrow$ `INSERT INTO order_line (...) VALUES (...)` via `jdbcTemplate.batchUpdate`
   - **`toUpdate`:** Sowohl in der DB als auch im Aggregat vorhanden $\rightarrow$ `UPDATE order_line SET ... WHERE id = :id AND order_id = :parentId` via `jdbcTemplate.batchUpdate`

Dadurch werden Änderungen an Kind-Entities (Hinzufügen, Bearbeiten, Löschen) atomar und performant synchronisiert.

### 5.4 Optimistisches Sperren (Optimistic Locking)
- Bei Neuanlage (`aggregate.version() == 0L`):
  - Führt ein `INSERT` aus und setzt `version = 1L`.
- Bei Aktualisierung (`aggregate.version() > 0L`):
  - Führt ein `UPDATE` mit Bedingung `WHERE id = :id AND version = :currentVersion` aus.
  - Erhöht die Version im Datensatz (`nextVersion = currentVersion + 1L`).
  - Wenn `rows == 0`, wirft das Repository sofort eine `org.springframework.dao.OptimisticLockingFailureException`.
- Bei Löschung (`delete()`):
  - Führt ein `DELETE` mit Bedingung `WHERE id = :id AND version = :version` aus.
  - Wenn `rows == 0`, wird ebenfalls eine `OptimisticLockingFailureException` ausgelöst.

---

## 6. Repository-Methoden & Abfragen

In der Vernac DSL definierte Repositories generieren standardisierte Methoden signaturen:

```vernac
repository OrderRepository for Order {
    find List<Order> findByStatus(String status);
    find Optional<Order> findByOrderNumber(String orderNumber);
    custom List<Order> findTopOrders(BigDecimal minTotal);
}
```

### 6.1 Standard-Methoden

#### 1. `byId(IdType id)`
- **Signatur:** `Order byId(OrderId id);`
- **Kein `Optional`:** Die Methode liefert direkt die Aggregat-Instanz zurück.
- **Exception bei Nicht-Auffinden:** Existiert kein Datensatz zur angegebenen ID, wird eine `org.vernac.runtime.AggregateNotFoundException` geworfen:
  ```java
  List<Order> results = this.jdbcTemplate.query(sql, Map.of("id", id.value()), (rs, rowNum) -> mapRow(rs));
  if (results.isEmpty()) {
      throw new AggregateNotFoundException(Order.class, id.value());
  }
  return results.getFirst();
  ```
  *DDD-Begründung:* Das Laden nach ID in einem Domänen-UseCase setzt im Regelfall die Existenz des Aggregats voraus. Ein Fehlen ist ein Ausnahmefall (NotFound), der nicht durch defensive Null-Checks im Fachcode verschleiert werden soll.

#### 2. `save(Aggregate aggregate)`
- **Signatur:** `Order save(Order aggregate);`
- Führt abhängig von `version()` ein `insert()` oder `update()` aus, synchronisiert Kind-Entities, dispatcht Outbox-Events und liefert das Aggregat mit der neuen Versionsnummer zurück.

#### 3. `delete(Aggregate aggregate)`
- **Signatur:** `void delete(Order aggregate);`
- Löscht das Aggregat unter Berücksichtigung des Versionstokens (`OptimisticLockingFailureException` bei Konflikt).

### 6.2 Automatisch generierte `find...`-Methoden
Mit dem Schlüsselwort `find` deklarierte Methoden werden vom Compiler automatisch in parametrisierte SQL-Abfragen übersetzt:

- **Deklaration in DSL:**
  ```vernac
  find List<Order> findByStatus(String status);
  find Optional<Order> findByCustomerAndStatus(CustomerId customer, String status);
  ```
- **Generierte SQL-Where-Klausel:**
  - Parameter werden in `snake_case`-Spaltennamen umgewandelt und mit `AND` verknüpft:
    `SELECT * FROM order WHERE status = :status` bzw.
    `SELECT * FROM order WHERE customer = :customer AND status = :status`
- **Rückgabe-Mapping:**
  - Ist der Rückgabetyp `Optional<Order>`:
    `return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());`
  - Ist der Rückgabetyp `List<Order>`:
    `return this.jdbcTemplate.query(sql, params, (rs, rowNum) -> mapRow(rs));`

---

## 7. Custom Repository-Methoden & Eigene Spring Beans (`custom ...`)

Für komplexe Abfragen, Reports, dynamische Filter oder Datenbank-spezifische Features (z. B. Window Functions, Volltextsuche, Stored Procedures) unterstützt Vernac das **Fragment-Interface-Muster**.

### 7.1 Funktionsweise & Code-Generierung

Wird eine `custom`-Methode im Repository deklariert:
```vernac
repository OrderRepository for Order {
    find List<Order> findByStatus(String status);
    custom List<Order> findTopOrders(BigDecimal threshold);
}
```

generiert der Compiler drei Komponenten:

1. **Fragment-Interface (`OrderRepositoryCustom` im Domänen-Package):**
   ```java
   package com.example.domain;

   import java.math.BigDecimal;
   import java.util.List;

   public interface OrderRepositoryCustom {
       List<Order> findTopOrders(BigDecimal threshold);
   }
   ```
2. **Haupt-Interface (`OrderRepository`):**
   Erweitert automatisch das Fragment-Interface:
   ```java
   public interface OrderRepository extends OrderRepositoryCustom {
       Order byId(OrderId id);
       Order save(Order aggregate);
       void delete(Order aggregate);
       List<Order> findByStatus(String status);
   }
   ```
3. **Delegation in `JdbcOrderRepository`:**
   Das generierte JDBC-Repository injiziert das Custom-Interface und delegiert den Aufruf:
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

### 7.2 Valides Implementierungsbeispiel einer Custom Bean

Um die `custom`-Methoden bereitzustellen, erstellt der Entwickler im Infrastruktur-Package eine Spring `@Component` oder `@Repository` Bean, die das Interface `<RepoName>Custom` implementiert:

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
                    Currency.getInstance(rs.getString("total_currency"))
            );
            String status = rs.getString("status");
            var createdAt = rs.getTimestamp("created_at").toInstant();
            var updatedAt = rs.getTimestamp("updated_at").toInstant();
            long version = rs.getLong("version");

            // Kind-Entities für gefundene Zeilen nachladen
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
                    Currency.getInstance(rs.getString("unit_price_currency"))
            );
            return OrderLine.create(lineId, sku, unitPrice, qty);
        });
        return OrderLines.of(list);
    }
}
```

Spring Boot erkennt `CustomOrderRepositoryImpl` automatisch über Component Scanning und injiziert die Bean in das generierte `JdbcOrderRepository`.

---

## 8. Zusammenfassende Referenztabelle

| Thema / Eigenschaft | Konvention / Regel | Beschreibung / Generiertes Verhalten |
| :--- | :--- | :--- |
| **Tabellenname** | `toSnakeCase(TypeName)` | `Order` $\rightarrow$ `order`, `OrderLine` $\rightarrow$ `order_line` |
| **Primärschlüssel** | `id UUID PRIMARY KEY` | Standard-ID Spalte auf jedem Aggregat und jeder Entity |
| **Fremdschlüssel** | `<aggregate_singular>_id` | Fremdschlüssel-Spalte in Child-Entity-Tabellen (z. B. `order_id`) |
| **Metadaten** | `created_at`, `updated_at`, `version` | Automatische Audit- und Optimistic-Locking-Spalten auf Aggregat-Roots |
| **Value Object (Single)** | Feldname ohne Suffix | `ItemSku sku` $\rightarrow$ Spalte `sku VARCHAR(255)` |
| **Value Object (Multi)** | `<feld>_<attribut>` | `Money total` $\rightarrow$ Spalten `total_amount`, `total_currency` |
| **Enum Value Object** | `VARCHAR(32)` | Speichert `.dbValue()`, liest via `.fromDbValue(...)` |
| **1:N Kind-Entities** | Eigene Tabelle mit FK | Automatisches Nachladen (`fetch...`) & 3-Wege-Diff (`sync...`) |
| **byId(id)** | `Agg byId(AggId id)` | Gibt Aggregat direkt zurück; wirft `AggregateNotFoundException` bei 404 |
| **save(agg)** | `Agg save(Agg agg)` | Unterscheidet INSERT (`version == 0`) / UPDATE, synchronisiert Child-Entities, dispatcht Outbox Events |
| **find... Methoden** | `find <Ret> <name>(<params>)` | Erzeugt SQL mit parametrisierter `WHERE`-Klausel (`AND`-Verknüpfung) |
| **custom... Methoden** | `custom <Ret> <name>(<params>)` | Generiert `<Repo>Custom` Interface; Delegation an eigene Spring Bean |
| **Optimistic Lock** | `version BIGINT` | Wirft `OptimisticLockingFailureException` bei concurrent conflicts |
| **Transaktionen** | `@Transactional(MANDATORY)` | Repositories laufen immer innerhalb einer bestehenden Transaktion |
