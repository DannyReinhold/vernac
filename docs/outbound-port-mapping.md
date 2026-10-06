# Outbound Port Mapping Rules in Vernac

This document describes the architecture, conventions, semantics, and code generation rules for **Outbound Ports**, **Schemas (DTOs)**, and **Adapter Mappings** in the Vernac DSL (derived from `Vernac.g4`, `AstBuilderVisitor`, `SemanticAnalyzer`, and `PortGenerator`).

---

## 1. Architecture & Concept

Vernac follows the principles of **Hexagonal Architecture (Ports & Adapters)** and **Domain-Driven Design (DDD)**:

- **Port (Domain Interface):** A port defines an outbound interface of the domain (e.g., querying a weather service, validation via external registries, invoice generation). By default, it resides in the domain package (`<basePackage>.domain`).
- **Schema (Infrastructure DTO):** A schema defines the external data structure (e.g., a JSON response payload). It resides in the infrastructure package (`<basePackage>.infrastructure.outbound.<portname>`) and is generated for JSON deserialization (Jackson).
- **Adapter (Infrastructure Implementation):** Implements the port interface (e.g., as a Spring `@Component` using Spring's `RestClient`).
- **Mapping (Anti-Corruption Layer / ACL):** The `mapping { ... }` clause acts as an Anti-Corruption Layer: it translates external infrastructure DTOs into pure, valid domain models (Value Objects, Entities, Aggregates) without the domain depending on external DTOs.

```
+-------------------------------------------------------------------------+
| DOMAIN LAYER                                                            |
|                                                                         |
|  +------------------------+             +----------------------------+  |
|  |     Port Interface     |             | Domain Model               |  |
|  |  (e.g., WeatherProvider)|            | (Value Object / Entity)    |  |
|  +------------------------+             +----------------------------+  |
|               ^                                       ^                 |
+---------------|---------------------------------------|-----------------+
| INFRASTRUCTURE LAYER                                  | (Mapping / ACL) |
|               |                                       |                 |
|  +------------------------+             +----------------------------+  |
|  |     REST Adapter       | ----------> | Schema DTO                 |  |
|  | (Spring RestClient)    |   Payload   | (e.g., WeatherDto)         |  |
|  +------------------------+             +----------------------------+  |
+-------------------------------------------------------------------------+
```

---

## 2. Syntax & Structure of an Outbound Port

A port declares a name, optional local schemas, and one or more port methods with associated adapter and mapping configuration:

```vernac
port WeatherProvider {

    // 1. Local schema (Infrastructure DTO for JSON)
    schema WeatherApiResponse {
        String description;
        BigDecimal tempCelsius;
        BigDecimal humidity;
    }

    // 2. Domain method
    Optional<Temperature> fetchCurrentTemperature(CityName city) {
        
        // 3. Adapter configuration
        adapter rest {
            GET "/api/v1/weather";
            baseUrlProperty: "services.weather.base-url";
            on 404 return Optional.empty();
            on 5xx throw ServiceUnavailableException;
        }

        // 4. Mapping block (Anti-Corruption Layer)
        mapping {
            response.tempCelsius -> Temperature.celsius;
        }
    }
}
```

---

## 3. Schemas (Infrastructure DTOs)

Schemas define the data structures of external interfaces:

### 3.1 DSL Declaration
```vernac
schema ExternalProjectDto {
    String extId;
    String extTitle;
    int budgetAmount;
}
```

### 3.2 Generated Java Code (`PortGenerator`)
For each schema, a POJO class is generated in the infrastructure package:
- **Default Constructor:** Public no-arg constructor for reflection and Jackson deserialization.
- **All-Args Constructor:** Public constructor accepting all fields.
- **Private Fields:** Non-final attributes (e.g., `private String extId;`).
- **Getters:** Record-style getters without `get` prefix (e.g., `public String extId()`).
- **Setters:** JavaBean setters with `set` prefix (e.g., `public void setExtId(String extId)`).

---

## 4. REST Adapter Configuration & Parameter Handling

When an `adapter rest { ... }` block is declared, the compiler generates a complete Spring component:
`public class Rest<PortName><MethodName>Adapter implements <PortName>`.

### 4.1 Base URL Resolution & Dependency Injection
- The adapter is registered as a Spring `@Component`.
- It injects `RestClient.Builder` and a `@Value`-annotated base URL:
  - **Default Property Convention:** `vernac.outbound.<kebab-case-port-name>.base-url`
    *(Example: `vernac.outbound.weather-provider.base-url` with fallback `http://localhost:8080`)*
  - **Explicit Property Key:** Configurable via `baseUrlProperty: "custom.key"` or `base-url-property: "custom.key"` in the `adapter rest` configuration.

### 4.2 URL & Automatic Query Parameter Extraction
- **Endpoint Resolution:** The path is taken from the configuration (e.g., `GET "/api/v1/weather";`) or defaults to `/api/<snake_case_method_name>`.
- **Query Parameters:** All method parameters of the port method are automatically appended as URL query parameters:
  ```java
  // Port method: fetchWeather(CityName city, CountryCode country)
  // Generated URI: "/api/v1/weather?city={city}&country={country}"
  ```
- **Unwrapping:** For typed IDs (`id`) and single-field Value Objects, `.value()` is automatically invoked when passing parameters into the URL template (e.g., `city.value()`).

### 4.3 HTTP Status & Error Handling
1. **Status Rules (`on <status> ...`):**
   - `on 404 return Optional.empty();`: Intercepts 404 status codes and suppresses the response body so that mapping cleanly produces `Optional.empty()`.
   - `on 5xx throw CustomException;`: Intercepts server error status codes and throws the declared exception (which must be declared in the method's `throws` clause).
2. **Catch-All Exception Handler:**
   - Any remaining HTTP error statuses are caught via `.onStatus(HttpStatusCode::isError, ...)` and wrapped into a controlled `RuntimeException("External API call failed with status: " + res.getStatusCode())`. This prevents internal Spring framework exceptions from leaking into the domain layer.

---

## 5. Detailed Mapping Rules

The `mapping { ... }` block governs the transformation of schema fields into domain instances.

### 5.1 Syntax & Paths
```vernac
mapping {
    <sourcePath> -> <targetPath>;
    <targetPath> <- <sourcePath>;
}
```
- By default, `->` is used (Response $\rightarrow$ Domain).
- `sourcePath`: Path on the source side (e.g., `response.tempCelsius` or `body.extTitle`).
- `targetPath`: Path on the target side (e.g., `Temperature.celsius` or `Project.id`).

### 5.2 Semantic Validation (`SemanticAnalyzer`)
Before code generation, the compiler performs strict semantic checks:
1. **Schema Field Check:** Does the referenced field exist in the declared schema?
2. **Domain Type Check:** Does the target type (Value Object, Entity, Aggregate, or Event) exist in the project?
3. **Domain Field Check:** Does the specified field exist on the domain type (or is it the ID field for Entities/Aggregates)?
4. **Error Signature Check:** If an error rule specifies `return empty`, the return type of the method must be `Optional<...>`.

---

## 6. Mapping Code Generation (`PortGenerator`)

Based on the target domain types and return types, the generator distinguishes four main cases:

### 6.1 Case 1: Mapping to Value Objects (Instantiation via `.of(...)`)

A Value Object has no independent identity (`id`).

- **Detection:** No statement in the mapping block targets `.id` or `id`.
- **Argument Assembly:** For each statement in the mapping block, the corresponding schema getter is called (`body.<sourceField>()`) in the declared order.
- **Generated Code:**
  - **For regular return type (`TargetType`):**
    ```java
    return body != null ? TargetType.of(body.tempCelsius()) : null;
    ```
  - **For `Optional<TargetType>`:**
    ```java
    return body != null ? Optional.of(TargetType.of(body.tempCelsius())) : Optional.empty();
    ```

#### Example:
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
*Generated return in adapter:*
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

## 6.2 Case 2: Mapping to Entities and Aggregates (Instantiation via `.fromExternal(...)`)

Entities and Aggregates possess a unique identity (`id`) and lifecycle metadata.

- **Detection:** At least one mapping statement targets an ID field (`targetPath.endsWith(".id") || targetPath.equals("id")`), e.g., `response.extId -> Project.id`.
- **Argument Assembly:**
  1. **1st Argument (ID):** The source field mapped to ID is extracted as the first argument (`body.<idSourceField>()`).
  2. **Subsequent Arguments (Payload Fields):** All remaining statements are passed as subsequent arguments in their declaration order.
- **Generated Code:**
  - **For regular return type (`EntityType`):**
    ```java
    return body != null ? EntityType.fromExternal(body.extId(), body.extTitle()) : null;
    ```
  - **For `Optional<EntityType>`:**
    ```java
    return body != null ? Optional.of(EntityType.fromExternal(body.extId(), body.extTitle())) : Optional.empty();
    ```

#### Example:
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
*Generated return in adapter:*
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

## 6.3 Case 3: Fallback without Explicit Mapping Block

When a port declares a `schema`, but the method does not contain a `mapping { ... }` block:
- The entire schema object `body` is passed directly to the `of(...)` factory of the domain type:
  ```java
  return body != null ? Optional.of(DomainType.of(body)) : Optional.empty();
  ```

---

## 6.4 Case 4: Port Methods without Schema

When no schema is declared in the port (e.g., for pure ping or trigger methods):
- The adapter executes the call without `.body(...)` deserialization and returns `Optional.empty()` for `Optional` returns, or `null` otherwise.

---

## 7. Custom Adapters (`adapter custom`)

For non-REST integrations (e.g., SOAP, gRPC, direct SDKs, PDF generation), `adapter custom` is used:

```vernac
port InvoiceGenerator {
    PdfDocument generate(InvoiceData data) {
        adapter custom InvoiceGeneratorDelegate;
    }
}
```

- **Generated Interface:** `InvoiceGeneratorDelegate` in the infrastructure package with the exact port method signature:
  ```java
  package com.example.infrastructure.outbound.invoicegenerator;

  public interface InvoiceGeneratorDelegate {
      PdfDocument generate(InvoiceData data);
  }
  ```
- **Naming Convention:** If no name is provided (`adapter custom;`), the compiler defaults to `<PortName><MethodName>Delegate`.
- **Implementation:** The developer implements the delegate interface in a custom Spring component.

---

## 8. Summary Reference Table of Mapping Rules

| Feature / Case | Detection / Criterion | Generated Pattern / Instantiation |
| :--- | :--- | :--- |
| **Value Object Return** | No statement maps to `.id` | `Target.of(body.field1(), ...)` |
| **Entity / Aggregate Return** | Statement maps to `*.id` | `Target.fromExternal(body.idField(), body.payload1(), ...)` |
| **Optional Return Type** | `Optional<T>` as method return type | `body != null ? Optional.of(...) : Optional.empty()` |
| **Direct Return Type** | `T` as method return type | `body != null ? ... : null` |
| **No Mapping Block** | Schema present, but no `mapping` block | `Target.of(body)` |
| **No Schema Present** | Port without `schema` declaration | `return Optional.empty();` or `return null;` |
| **Query Parameters** | Method parameters on port method | `?param={param}` with `.value()` unwrapping |
| **Status 404 Handling** | `on 404 return Optional.empty();` | `.onStatus(HttpStatusCode.valueOf(404)::equals, (req, res) -> {})` |
| **Catch-All Error** | Automatic in every REST adapter | `.onStatus(HttpStatusCode::isError, ...)` $\rightarrow$ `RuntimeException` |
| **Custom Delegate** | `adapter custom [DelegateName];` | Generates Java interface `DelegateName` in the adapter package |
