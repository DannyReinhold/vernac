# Mapping-Regeln für Outbound Ports in Vernac

Dieses Dokument beschreibt die Architektur, Konventionen, Semantik und Code-Generierungsregeln für **Outbound Ports**, **Schemas (DTOs)** und **Adapter-Mappings** in der Vernac DSL (abgeleitet aus `Vernac.g4`, `AstBuilderVisitor`, `SemanticAnalyzer` und `PortGenerator`).

---

## 1. Architektur & Konzept

Vernac folgt den Prinzipien der **Hexagonalen Architektur (Ports & Adapters)** und des **Domain-Driven Designs (DDD)**:

- **Port (Domänen-Interface):** Ein Port definiert eine ausgehende Schnittstelle der Domäne (z. B. Abfrage eines Wetterdienstes, Validierung über externe Register, Rechnungsgenerierung). Er liegt standardmäßig im Domänen-Package (`<basePackage>.domain`).
- **Schema (Infrastruktur-DTO):** Ein Schema definiert die externe Datenstruktur (z. B. ein JSON-Response-Payload). Es liegt im Infrastruktur-Package (`<basePackage>.infrastructure.outbound.<portname>`) und wird für die JSON-Deserialisierung (Jackson) generiert.
- **Adapter (Infrastruktur-Implementierung):** Implementiert das Port-Interface (z. B. als Spring `@Component` unter Nutzung des Spring `RestClient`).
- **Mapping (Antikorruptionsschicht / ACL):** Die `mapping { ... }`-Klausel fungiert als Anti-Corruption Layer: Sie übersetzt die externen Infrastruktur-DTOs in reine, valide Domänenmodelle (Value Objects, Entities, Aggregates), ohne dass die Domäne von externen DTOs abhängt.

```
+-------------------------------------------------------------------------+
| DOMAIN LAYER                                                            |
|                                                                         |
|  +------------------------+             +----------------------------+  |
|  |     Port Interface     |             | Domain Model               |  |
|  |  (z.B. WeatherProvider)|             | (Value Object / Entity)    |  |
|  +------------------------+             +----------------------------+  |
|               ^                                       ^                 |
+---------------|---------------------------------------|-----------------+
| INFRASTRUCTURE LAYER                                  | (Mapping / ACL) |
|               |                                       |                 |
|  +------------------------+             +----------------------------+  |
|  |     REST Adapter       | ----------> | Schema DTO                 |  |
|  | (Spring RestClient)    |   Payload   | (z.B. WeatherDto)          |  |
|  +------------------------+             +----------------------------+  |
+-------------------------------------------------------------------------+
```

---

## 2. Syntax & Aufbau eines Outbound Ports

Ein Port deklariert einen Namen, optionale lokale Schemas und eine oder mehrere Port-Methoden mit zugehörigem Adapter und Mapping:

```vernac
port WeatherProvider {

    // 1. Lokales Schema (Infrastruktur-DTO für JSON)
    schema WeatherApiResponse {
        String description;
        BigDecimal tempCelsius;
        BigDecimal humidity;
    }

    // 2. Domänen-Methode
    Optional<Temperature> fetchCurrentTemperature(CityName city) {
        
        // 3. Adapter-Konfiguration
        adapter rest {
            GET "/api/v1/weather";
            baseUrlProperty: "services.weather.base-url";
            on 404 return Optional.empty();
            on 5xx throw ServiceUnavailableException;
        }

        // 4. Mapping-Block (Anti-Corruption Layer)
        mapping {
            response.tempCelsius -> Temperature.celsius;
        }
    }
}
```

---

## 3. Schemas (Infrastruktur-DTOs)

Schemas definieren die Datenstruktur externer Schnittstellen:

### 3.1 DSL-Deklaration
```vernac
schema ExternalProjectDto {
    String extId;
    String extTitle;
    int budgetAmount;
}
```

### 3.2 Generierter Java-Code (`PortGenerator`)
Für jedes Schema wird eine POJO-Klasse im Infrastruktur-Package generiert:
- **Default-Konstruktor:** Öffentlicher parameterloser Konstruktor für Reflection/Jackson-Deserialisierung.
- **All-Args-Konstruktor:** Öffentlicher Konstruktor zur Übergabe aller Felder.
- **Private Fields:** Nicht-finale Attribute (z. B. `private String extId;`).
- **Getter:** Record-artige Getter ohne `get`-Präfix (z. B. `public String extId()`).
- **Setter:** Java-Bean-Setter mit `set`-Präfix (z. B. `public void setExtId(String extId)`).

---

## 4. REST-Adapter Konfiguration & Parameter-Handling

Wird ein `adapter rest { ... }` deklariert, generiert der Compiler eine vollständige Spring-Komponente:
`public class Rest<PortName><MethodName>Adapter implements <PortName>`.

### 4.1 Base-URL Auflösung & Dependency Injection
- Der Adapter wird als Spring `@Component` registriert.
- Er injiziert `RestClient.Builder` und eine `@Value`-annotierte Base-URL:
  - **Standard-Property-Konvention:** `vernac.outbound.<kebab-case-port-name>.base-url`
    *(Beispiel: `vernac.outbound.weather-provider.base-url` mit Fallback `http://localhost:8080`)*
  - **Expliziter Property-Key:** Über `baseUrlProperty: "custom.key"` oder `base-url-property: "custom.key"` in der `adapter rest`-Konfiguration anpassbar.

### 4.2 URL & automatische Query-Parameter-Extraktion
- **Endpunkt-Auflösung:** Der Pfad wird aus der Konfiguration (z. B. `GET "/api/v1/weather";`) entnommen oder standardmäßig auf `/api/<snake_case_method_name>` gesetzt.
- **Query-Parameter:** Sämtliche Methodenparameter der Port-Methode werden automatisch als URL-Query-Parameter angehängt:
  ```java
  // Port-Methode: fetchWeather(CityName city, CountryCode country)
  // Generierte URI: "/api/v1/weather?city={city}&country={country}"
  ```
- **Unwrapping:** Bei typisierten IDs (`id`) und Single-Field Value Objects wird beim URL-Aufruf automatisch `.value()` aufgerufen (z. B. `city.value()`).

### 4.3 HTTP-Status & Fehlerbehandlung
1. **Status-Regeln (`on <status> ...`):**
   - `on 404 return Optional.empty();`: Fängt 404-Fehler ab und leert den Response-Body, sodass das Mapping anschließend sauber `Optional.empty()` zurückliefern kann.
   - `on 5xx throw CustomException;`: Fängt Serverfehler ab und wirft die deklarierte Exception (muss in der `throws`-Klausel der Methode enthalten sein).
2. **Catch-All Exception Handler:**
   - Alle restlichen HTTP-Fehlerstatus werden über `.onStatus(HttpStatusCode::isError, ...)` abgefangen und in eine kontrollierte `RuntimeException("External API call failed with status: " + res.getStatusCode())` umgewandelt. Dies verhindert das Durchsickern interner Spring-Framework-Exceptions in die Domäne.

---

## 5. Detaillierte Mapping-Regeln

Der `mapping { ... }`-Block steuert die Überführung der Schema-Felder in die Domänen-Instanzen.

### 5.1 Syntax & Pfade
```vernac
mapping {
    <sourcePath> -> <targetPath>;
    <targetPath> <- <sourcePath>;
}
```
- Standardmäßig wird `->` verwendet (Response $\rightarrow$ Domäne).
- `sourcePath`: Pfad auf der Quellseite (z. B. `response.tempCelsius` oder `body.extTitle`).
- `targetPath`: Pfad auf der Zielseite (z. B. `Temperature.celsius` oder `Project.id`).

### 5.2 Semantische Validierung (`SemanticAnalyzer`)
Vor der Code-Generierung führt der Compiler strenge Prüfungen durch:
1. **Schema-Feldprüfung:** Existiert das referenzierte Feld im deklarierten Schema?
2. **Domänentyp-Prüfung:** Existiert der Zieltyp (Value Object, Entity, Aggregate oder Event) im Projekt?
3. **Domänenfeld-Prüfung:** Existiert das angegebene Feld im Domänentyp (bzw. ist es das ID-Feld bei Entities/Aggregaten)?
4. **Fehlersignatur:** Verwendet eine Fehlerregel `return empty`, muss der Rückgabetyp der Methode zwingend `Optional<...>` sein.

---

## 6. Code-Generierung des Mappings (`PortGenerator`)

Der Generator unterscheidet anhand der Ziel-Domänentypen und Rückgabetypen vier Hauptfälle:

### 6.1 Fall 1: Mapping auf Value Objects (Instanziierung via `.of(...)`)

Ein Value Object besitzt keine eigene Identität (`id`).

- **Erkennung:** Kein Statement im Mapping-Block weist auf ein `.id` oder `id`-Zielfeld hin.
- **Argument-Zusammenstellung:** Für jedes Statement im Mapping-Block wird der entsprechende Schema-Getter aufgerufen (`body.<sourceField>()`) in der deklarierten Reihenfolge.
- **Generierter Code:**
  - **Bei regulärem Rückgabetyp (`TargetType`):**
    ```java
    return body != null ? TargetType.of(body.tempCelsius()) : null;
    ```
  - **Bei `Optional<TargetType>`:**
    ```java
    return body != null ? Optional.of(TargetType.of(body.tempCelsius())) : Optional.empty();
    ```

#### Beispiel:
```vernac
value Temperature(BigDecimal celsius);

port WeatherProvider {
    schema WeatherDto {
        BigDecimal tempCelsius;
    }

    Optional<Temperature> fetchWeather(CityName city) {
        adapter rest {
            GET "/api/weather";
            on 404 return Optional.empty();
        }
        mapping {
            response.tempCelsius -> Temperature.celsius;
        }
    }
}
```
*Generierte Rückgabe im Adapter:*
```java
var body = this.restClient.get()
    .uri("/api/weather?city={city}", city.value())
    .accept(MediaType.APPLICATION_JSON)
    .retrieve()
    .onStatus(HttpStatusCode.valueOf(404)::equals, (req, res) -> {})
    .onStatus(HttpStatusCode::isError, (req, res) -> {
        throw new RuntimeException("External API call failed with status: " + res.getStatusCode());
    })
    .body(WeatherDto.class);

return body != null ? Optional.of(Temperature.of(body.tempCelsius())) : Optional.empty();
```

---

### 6.2 Fall 2: Mapping auf Entities und Aggregate (Instanziierung via `.fromExternal(...)`)

Entities und Aggregate besitzen eine eindeutige Identität (`id`) und Lebenszyklusdaten.

- **Erkennung:** Mindestens ein Mapping-Statement weist auf ein ID-Feld hin (`targetPath.endsWith(".id") || targetPath.equals("id")`), z. B. `response.extId -> Project.id`.
- **Argument-Zusammenstellung:**
  1. **1. Argument (ID):** Das der ID zugeordnete Quellfeld wird als erstes Argument extrahiert (`body.<idSourceField>()`).
  2. **Folgende Argumente (Payload-Felder):** Alle restlichen Statements werden in ihrer Definitionsreihenfolge als nachfolgende Argumente angehängt.
- **Generierter Code:**
  - **Bei regulärem Rückgabetyp (`EntityType`):**
    ```java
    return body != null ? EntityType.fromExternal(body.extId(), body.extTitle()) : null;
    ```
  - **Bei `Optional<EntityType>`:**
    ```java
    return body != null ? Optional.of(EntityType.fromExternal(body.extId(), body.extTitle())) : Optional.empty();
    ```

#### Beispiel:
```vernac
id ProjectId;
value ProjectName(String value);

entity Project [ProjectId] (ProjectName name);

port ExternalProjectService {
    schema ExternalProjectDto {
        String extId;
        String extTitle;
    }

    Project fetchProject(ProjectId id) {
        adapter rest {
            GET "/api/projects";
        }
        mapping {
            response.extId -> Project.id;
            response.extTitle -> Project.name;
        }
    }
}
```
*Generierte Rückgabe im Adapter:*
```java
var body = this.restClient.get()
    .uri("/api/projects?id={id}", id.value())
    .accept(MediaType.APPLICATION_JSON)
    .retrieve()
    .onStatus(HttpStatusCode::isError, (req, res) -> {
        throw new RuntimeException("External API call failed with status: " + res.getStatusCode());
    })
    .body(ExternalProjectDto.class);

return body != null ? Project.fromExternal(body.extId(), body.extTitle()) : null;
```

---

### 6.3 Fall 3: Fallback ohne expliziten Mapping-Block

Wenn ein Port ein `schema` deklariert, aber die Methode keinen `mapping { ... }`-Block enthält:
- Das gesamte Schema-Objekt `body` wird direkt an die `of(...)`-Factory des Domänentyps übergeben:
  ```java
  return body != null ? Optional.of(DomainType.of(body)) : Optional.empty();
  ```

---

### 6.4 Fall 4: Port-Methoden ohne Schema

Wenn kein Schema im Port deklariert ist (z. B. bei reinen Ping-/Trigger-Methoden):
- Der Adapter führt den Call ohne `.body(...)`-Deserialisierung aus und liefert bei `Optional` `Optional.empty()`, andernfalls `null`.

---

## 7. Custom Adapter (`adapter custom`)

Für Nicht-REST-Integrationen (z. B. SOAP, gRPC, Direct SDKs, PDF-Generierung) wird `adapter custom` genutzt:

```vernac
port InvoiceGenerator {
    PdfDocument generate(InvoiceData data) {
        adapter custom InvoiceGeneratorDelegate;
    }
}
```

- **Generiertes Interface:** `InvoiceGeneratorDelegate` im Infrastruktur-Package mit exakt der Port-Methodensignatur:
  ```java
  package com.example.infrastructure.outbound.invoicegenerator;

  public interface InvoiceGeneratorDelegate {
      PdfDocument generate(InvoiceData data);
  }
  ```
- **Namenskonvention:** Wird kein Name angegeben (`adapter custom;`), wird standardmäßig `<PortName><MethodName>Delegate` gewählt.
- **Implementierung:** Der Entwickler implementiert das Delegate-Interface in einer eigenen Spring-Komponente.

---

## 8. Zusammenfassende Referenztabelle der Mapping-Regeln

| Merkmal / Fall | Erkennung / Kriterium | Generiertes Muster / Instanziierung |
| :--- | :--- | :--- |
| **Value Object Return** | Kein Statement mapped auf `.id` | `Target.of(body.field1(), ...)` |
| **Entity / Aggregate Return** | Statement mapped auf `*.id` | `Target.fromExternal(body.idField(), body.payload1(), ...)` |
| **Optional Return Type** | `Optional<T>` als Methoden-Rückgabe | `body != null ? Optional.of(...) : Optional.empty()` |
| **Direkter Return Type** | `T` als Methoden-Rückgabe | `body != null ? ... : null` |
| **Kein Mapping-Block** | Schema vorhanden, aber kein `mapping` | `Target.of(body)` |
| **Kein Schema vorhanden** | Port ohne `schema` Deklaration | `return Optional.empty();` bzw. `return null;` |
| **Query-Parameter** | Methoden-Parameter an Port-Methode | `?param={param}` mit `.value()` Unwrapping |
| **Status 404 Handling** | `on 404 return Optional.empty();` | `.onStatus(HttpStatusCode.valueOf(404)::equals, (req, res) -> {})` |
| **Catch-All Error** | Automatisch in jedem REST-Adapter | `.onStatus(HttpStatusCode::isError, ...)` $\rightarrow$ `RuntimeException` |
| **Custom Delegate** | `adapter custom [DelegateName];` | Erzeugt Java-Interface `DelegateName` im Adapter-Package |
