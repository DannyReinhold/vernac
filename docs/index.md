# Vernac Documentation

**A Domain-Specific Language for Domain-Driven Design in Java 21, Spring Boot, and PostgreSQL**

Welcome to the official documentation for **Vernac**!

Vernac is a concise, declarative Domain-Specific Language (DSL) and compiler designed to streamline Domain-Driven Design
(DDD) and Hexagonal / Clean Architecture in Java applications. You model your domain identifiers, value objects,
entities, aggregates, repositories, ports, and use cases in `.vernac` files, while the Vernac compiler generates
idiomatic, robust, and production-ready Java 21 code.

---

## 🚀 Key Highlights

- **Pure, Type-Safe Domain Model**: Strongly typed IDs, immutable Value Objects, First-Class Collections, and Aggregates
  with automatic invariant validation.
- **Automated Relational Persistence**: Generates Spring JDBC repositories, PostgreSQL DDL schemas, transparent column
  flattening for embedded Value Objects, child entity synchronization (1:N), and optimistic locking.
- **Transactional Outbox Pattern**: Built-in EDA support that atomically persists domain events in `vernac_outbox`
  within the aggregate's transaction, paired with Spring `@TransactionalEventListener`.
- **Hexagonal Outbound Ports**: Generates port interfaces, external DTO schemas, and Spring `RestClient` HTTP adapters
  with automatic request/response mapping and Anti-Corruption Layer (ACL) separation.
- **Application Layer Orchestration**: Spring `@Service` and `@Transactional` Use Cases with declarative dependency
  injection, parameter validation, and type-inferred tuple returns.
- **Convention over Configuration**: Deterministic name and type inference for fields, collections, repositories,
  dispatch modes, and use-case operations.
- **Java 21 & Spring Boot Native**: Generated code integrates seamlessly with Spring Boot 3+ applications and the
  standard Java compiler.

---

## ⚡ Quick DSL Showcase

```vernac
package com.example.energy;

id StorageId;

value WattHours(int) validates {
    require(value >= 0, "WattHours cannot be negative");
}

outbox event StorageCharged(StorageId storageId, WattHours newTotal);

aggregate EnergyStorage[StorageId](
    WattHours capacity,
    mut WattHours storedEnergy
) validates {
    require(storedEnergy.value() <= capacity.value(), "Stored energy cannot exceed capacity");
} {
    public void charge(WattHours additionalEnergy) {
        int newTotal = this.storedEnergy.value() + additionalEnergy.value();
        storedEnergy(WattHours.of(newTotal));
        registerEvent(StorageCharged.create(this.id, this.storedEnergy));
    }
}

repository for EnergyStorage;
```

---

## 📚 Documentation Guides

Explore the detailed architecture and reference manuals for every area of Vernac:

| Guide                                                                       | Description                                    | Key Topics                                                                                               |
|:----------------------------------------------------------------------------|:-----------------------------------------------|:---------------------------------------------------------------------------------------------------------|
| 📖 **[Language Reference](./language-reference.md)**                        | Complete DSL syntax, keywords, and conventions | `id`, `value`, `entity`, `aggregate`, `collection`, `mut`, inference rules, DDD validation               |
| 🏛️ **[Use Cases & Application Layer](./usecase-application-layer.md)**      | Application services and orchestration         | `usecase`, `service`, `@Transactional`, dependency injection (`use`), `load`, `save`, tuple returns      |
| 🗄️ **[Database & Repository Mapping](./repository-db-mapping.md)**          | PostgreSQL persistence and Spring JDBC         | Table DDL, column flattening, 1:N child entities, optimistic locking, custom repository beans            |
| 🌐 **[Outbound Ports & REST Adapters](./outbound-port-mapping.md)**         | Hexagonal architecture and external APIs       | `port`, Spring `RestClient` generation, schema DTOs, Anti-Corruption Layer, error status handling        |
| ⚡ **[Event-Driven Architecture & Outbox](./event-driven-architecture.md)** | Domain events and Transactional Outbox         | `event`, `vernac_outbox` DDL, `JdbcEventDispatcher`, `@TransactionalEventListener(phase = AFTER_COMMIT)` |

---

## 🛠️ Getting Started

### 1. Add the Maven Plugin

Configure the `vernac-maven-plugin` and `vernac-runtime` in your `pom.xml`:

```xml

<dependencies>
    <dependency>
        <groupId>org.vernac</groupId>
        <artifactId>vernac-runtime</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </dependency>
</dependencies>

<build>
<plugins>
    <plugin>
        <groupId>org.vernac</groupId>
        <artifactId>vernac-maven-plugin</artifactId>
        <version>0.1.0-SNAPSHOT</version>
        <executions>
            <execution>
                <goals>
                    <goal>compile</goal>
                </goals>
            </execution>
        </executions>
    </plugin>
</plugins>
</build>
```

### 2. Create Your Domain Model

Place your `.vernac` model files under `src/main/vernac/` (e.g., `src/main/vernac/order-domain.vernac`).

### 3. Build & Generate Sources

Run the standard Maven lifecycle:

```bash
mvn clean compile
```

The Vernac compiler processes your `.vernac` definitions during the `generate-sources` phase and places generated Java
source files in:

```
target/generated-sources/vernac/
```

These sources are automatically added to your project's compile classpath.

---

## 💡 IDE Support & Tooling

Vernac includes a dedicated Language Server Protocol (LSP) implementation in `vernac-lsp`:

- Real-time syntax and semantic validation.
- Diagnostics with exact line and column numbers.
- Ready for integration with any LSP-compatible editor (VS Code, IntelliJ IDEA, Neovim, etc.).

---

## 🔍 Example Projects

Check out the example applications in the repository for full working setups:

- **`vernac-example`**: Complete e-commerce ordering system with REST outbound ports, domain events, entities, and
  repositories.
- **`vernac-example-home-energy`**: Home energy management system demonstrating aggregate invariant enforcement,
  mutation tracking, domain services, and use case orchestration.

---

## 🔗 Links & Resources

- **GitHub Repository**: [GitHub - Danny-Reinhold/vernac](https://github.com/DannyReinhold/vernac)
- **License**: [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
