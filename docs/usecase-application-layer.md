# Use Cases und Application Layer in Vernac

Dieses Dokument beschreibt die Architektur, Konventionen, Compiler-Regeln und Code-Generierungsmechanismen für **Use Cases** (`usecase`) und **Domain Services** (`service`) in Vernac (abgeleitet aus `UseCaseGenerator`, `DomainServiceGenerator`, `SemanticAnalyzer` und `AstBuilderVisitor`).

---

## 1. Architektur & Rolle im Domain-Driven Design (DDD)

In der hexagonalen Architektur von Vernac bildet die Use-Case-Schicht den **Application Layer (Anwendungsschicht)**:

- **Aufgabe von Use Cases (`usecase`):** Orchestrierung fachlicher Abläufe. Sie laden Aggregate über Repositories, rufen Domänenmethoden auf, persistieren Zustandsänderungen (`save`), delegieren an Outbound-Ports und geben fachliche Resultate (DTOs, Tupel oder Primitiven) zurück.
- **Aufgabe von Domain Services (`service`):** Aggregat-übergreifende Berechnungen oder reine Domänenlogik, die keinem einzelnen Aggregat zugeordnet werden kann, ohne eigene Persistenz- oder Infrastrukturabhängigkeiten.
- **Transaktionsklammer:** Jeder generierte Use Case ist eine Spring `@Service`-Komponente und wird mit `@Transactional` annotiert. Dadurch laufen alle Lade-, Speicher- und Event-Dispatching-Operationen atomar innerhalb einer Datenbanktransaktion.

```
+-------------------------------------------------------------------------+
| APPLICATION LAYER (Orchestrierung)                                      |
|                                                                         |
|  +-------------------------------------------------------------------+  |
|  |  @Service @Transactional UseCase (z.B. PlaceOrder)                |  |
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

## 2. Deklaration & Syntax von Use Cases

Ein Use Case in Vernac besitzt folgende optionale und verpflichtende Abschnitte:

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

## 3. Parameter- und Typregeln (DDD-Restriktionen)

Der `SemanticAnalyzer` erzwingt strikte semantische Regeln für Use-Case-Parameter:

1. **Keine direkten Aggregate als Parameter:**
   - **Verboten:** `usecase CancelOrder(Order order)`
   - **Erlaubt:** `usecase CancelOrder(OrderId orderId, Reason reason)`
   - *Begründung:* Aggregate dürfen nicht unkontrolliert von außen in die Anwendungsschicht gereicht werden. Der Use Case muss die Konsistenzgrenze wahren und das Aggregat selbst über das Repository laden.
2. **Erlaubte Parametertypen:**
   - IDs (z. B. `OrderId`, `CustomerId`, `UUID`)
   - Value Objects (z. B. `Money`, `Quantity`, `EmailAddress`)
   - First-Class Collections (z. B. `OrderLines`, `ProductIds`)
   - Primitive Typen und Standard-JDK-Typen (`int`, `String`, `Instant`, `BigDecimal` etc.)
3. **Eindeutigkeit der Parameternamen:** Doppelte Parameternamen führen zu einem Compiler-Fehler.

---

## 4. Dependencies (`use (...)`) & Injection

Über den `use`-Block deklariert der Use Case seine benötigten Abhängigkeiten (Repositories, Outbound Ports, Domain Services etc.).

### 4.1 Namenskonventionen & Default-Instanznamen
Wird kein expliziter Instanzname angegeben, leitet Vernac den Variablennamen per **camelCase** vom Typnamen ab:

```vernac
use (
    OrderRepository,                 // -> private final OrderRepository orderRepository;
    PaymentPort,                     // -> private final PaymentPort paymentPort;
    CustomerRepository customerRepo  // -> private final CustomerRepository customerRepo;
)
```

### 4.2 Konstruktor-Generierung
Der Compiler generiert automatisch den vollständigen Konstruktor mit `Objects.requireNonNull`-Guards für Spring Constructor Injection:

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

### 4.3 Eindeutigkeitsregeln
- Jeder Dependency-Typ darf im `use`-Block nur einmal vorkommen.
- Instanzvariablennamen müssen eindeutig sein.

---

## 5. Vorbedingungen & Validierung (`validates`)

Use Cases können Eingabedaten über den `validates`-Block absichern:

```vernac
validates {
    require(customerId != null, "Customer ID must not be null");
    require(!lines.isEmpty(), "Order lines must not be empty");
}
```

- **Generierung:** Wird als erste Anweisung in die `execute`-Methode generiert.
- **Fehlerbehandlung:** Schlägt eine Bedingung fehl, wird eine `org.vernac.runtime.DomainValidationException` ausgelöst:
  ```java
  if (!(customerId != null)) {
      throw new DomainValidationException("Customer ID must not be null");
  }
  ```

---

## 6. Statements & Orchestrierung im Use Case

Der Body eines Use Cases unterstützt sowohl DSL-eigene Schlüsselwörter als auch eingebetteten Java-Code.

### 6.1 `load`-Statement
Lädt ein Aggregat über sein zuständiges Repository anhand der ID:

```vernac
// Volle Syntax
Order order = load Order from orderRepository with orderId;

// Konventionelle Syntax (Repository wird automatisch inferiert)
Order order = load Order with orderId;
```

**Generierter Java-Code:**
```java
Order order = this.orderRepository.byId(orderId);
```

**Inferenzregeln des Compilers:**
- Ist `from <repoVar>` nicht angegeben, sucht der `SemanticAnalyzer` im `use`-Block nach dem passenden Repository für den Aggregattyp `Order`.
- Gibt es genau ein passendes Repository, wird dieses automatisch gewählt.
- Gibt es mehrere oder kein passendes Repository im `use`-Block, meldet der Compiler einen Fehler (`Ambiguous repositories` bzw. `No repository in 'use' manages aggregate...`).
- *Hinweis:* `byId()` liefert kein `Optional`, sondern wirft bei Nicht-Auffinden direkt eine `AggregateNotFoundException`.

### 6.2 `save`-Statement
Speichert ein Aggregat über sein Repository:

```vernac
// Volle Syntax
save order to orderRepository;

// Konventionelle Syntax (Repository-Name wird aus Instanzname abgeleitet)
save order;
```

**Generierter Java-Code:**
```java
this.orderRepository.save(order);
```

### 6.3 Freier Java-Code
Alle weiteren fachlichen Aufrufe (Methoden auf Aggregaten, Port-Aufrufe, Kontrollstrukturen) können direkt als Java-Code geschrieben werden:
```vernac
order.completeOrder();
paymentPort.process(order.id(), order.total());
```

---

## 7. Rückgabewerte & Tupel-Returns (`return`)

Use Cases unterstützen drei Arten von Rückgabewerten:

### 7.1 Kein Rückgabewert (Void)
Wird kein `return` deklariert, hat die generierte `execute(...)`-Methode den Rückgabetyp `void`.

### 7.2 Einzelner Rückgabewert
```vernac
usecase GetOrderStatus(OrderId orderId) use (OrderRepository) {
    Order order = load Order with orderId;
    return order.status();
}
```
**Generierter Java-Code:**
```java
public String execute(OrderId orderId) {
    Order order = this.orderRepository.byId(orderId);
    return order.status();
}
```

### 7.3 Tuple-Return (`Result`-Record)
Möchte ein Use Case mehrere Werte zurückgeben, unterstützt Vernac Tupel-Returns mit automatischer Record-Generierung:

```vernac
usecase PlaceOrder(CustomerId customerId, OrderLines lines) use (OrderRepository) {
    Order order = Order.create(OrderId.random(), customerId, lines);
    save order;
    return (order.id() as orderId, order.total() as totalAmount);
}
```

**Generierter Java-Code:**
1. **Eingebetteter `Result`-Record:**
   ```java
   public static record Result(OrderId orderId, Money totalAmount) {
   }
   ```
2. **Methodensignatur & Rückgabe:**
   ```java
   public Result execute(CustomerId customerId, OrderLines lines) {
       Order order = Order.create(OrderId.random(), customerId, lines);
       this.orderRepository.save(order);
       return new Result(order.id(), order.total());
   }
   ```

**Namens- und Typinferenz bei Tupeln:**
- **Feldname:** Wird über `as <name>` festgelegt. Fehlt `as`, leitet der Compiler den Namen aus dem letzten Pfadsegment ab (`order.id()` $\rightarrow$ `id`).
- **Typ:** Wird über den `ExpressionTypeInferrer` anhand bekannter Aggregat-Getter, Parameter oder Literal-Typen ermittelt.
- **DDD-Regel:** Das direkte Zurückgeben eines ganzen Aggregat-Roots im Tupel ist verboten (`Returning aggregate root is forbidden`), um ein unkontrolliertes Entweichen des Domänenzustands aus der Transaktionsgrenze zu verhindern.

---

## 8. Domain Services (`service`)

Für zustandslose, berechnende Domänenlogik bietet Vernac `service`:

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

### Unterschiede zwischen `usecase` und `service`:
| Merkmal | `usecase` (Application Layer) | `service` (Domain Layer) |
| :--- | :--- | :--- |
| **Package** | `<basePackage>.usecase` | `<basePackage>.domain` |
| **Transaktionen** | `@Transactional` (erzwingt DB-Transaktion) | Keine `@Transactional`-Annotation |
| **Dependencies** | Repositories, Ports, Services (`use (...)`) | Keine Repositories / DB-Zugriffe |
| **Parameter** | Unveränderlich; keine Aggregat-Roots | Reines Domänenmodell / VOs / Parameter |
| **Zweck** | Orchestrierung, Persistenz, Ports | Komplexe Algorithmen, Berechnungen |

---

## 9. Zusammenfassende Referenztabelle

| Sprachmittel | Syntax / Konvention | Generiertes Verhalten / Regel |
| :--- | :--- | :--- |
| **Klassendefinition** | `usecase Name(Params...)` | Generiert `@Service @Transactional public class Name` |
| **Dependencies** | `use (RepoA, PortB portVar)` | Generiert `private final` Felder + Constructor Injection mit Null-Checks |
| **Default Repo-Name** | `Type` $\rightarrow$ `camelCase(Type)` | `OrderRepository` $\rightarrow$ `orderRepository` |
| **Validierung** | `validates { require(cond, msg); }` | Generiert Guards mit `DomainValidationException` |
| **Laden** | `load Agg [from repo] with id;` | Generiert `this.<repo>.byId(id)` |
| **Speichern** | `save agg [to repo];` | Generiert `this.<repo>.save(agg)` |
| **Tupel-Return** | `return (expr as alias, ...);` | Generiert `public static record Result(...)` und `return new Result(...)` |
| **Aggregate-Schutz** | Parameter / Return Restriktion | Aggregate dürfen weder direkt übergeben noch als Ganzes zurückgegeben werden |
