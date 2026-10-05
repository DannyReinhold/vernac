# Vernac

> **Clean Architecture & Domain-Driven Design without the boilerplate nightmare.**

Vernac is a declarative Domain-Specific Language (DSL) and a source-to-source compiler for Java 21+, Spring Boot 3.4+,
and PostgreSQL.

Instead of writing hundreds of lines of redundant Java code for Value Objects, aggregate metadata, JDBC RowMappers,
outbox tables, and REST adapters by hand, you declare your domain in concise `.vernac` files. Vernac compiles these
directly into standard-compliant, type-safe, and fully auditable Java code before the standard Java compiler runs.

No bytecode enhancement. No reflection voodoo. 100% JavaPoet-generated code.

---

## Why Vernac?

Classic Enterprise Java applications often suffer from two extremes:

1. **Primitive Obsession & Anemic Models:** Entities degrade into pure DTOs with getters and setters. Validation logic
   scatters uncontrollably across service classes.
2. **Architecture Fatigue:** Genuine DDD with strict boundaries requires massive amounts of technical glue: ID classes,
   immutability boilerplate, repository implementations with JSON/flattening logic, event outbox patterns, and DTO
   mapping.

Vernac resolves this conflict:

* **Strict Integrity:** Value Objects are immutable by default. Mutations on aggregates are only possible via explicitly
  declared `mut` fields and reliably trigger invariant validation.
* **No Primitive Obsession:** Strongly typed IDs (`id StorageId;`) prevent parameter mix-ups at compile time.
* **Built-in Outbox:** Events are persisted either in-memory (`memory`) or transactionally via a PostgreSQL outbox
  pattern (`outbox`).
* **No ORM Bloat:** Vernac relies on modern Spring JDBC (`NamedParameterizedJdbcTemplate` / `RowMapper`) with
  deterministic column
  flattening for maximum performance and transparency.

---

## 60 Lines of Vernac vs. 800 Lines of Java

This example from the included showcase module (`vernac-example-home-energy`) defines a complete domain including a REST
integration and a Use Case:

```vernac
package org.vernac.example.home.energy;

// 1. Typed Identifiers & Value Objects with Invariants
id StorageId;

value WattHours (int) validates {
require (value >= 0, "WattHours cannot be negative");
}

value BatterySoc (int percent) validates {
require (percent >= 0 && percent <= 100, "SOC must be between 0 and 100");
}

// 2. Transactional Outbox Events
outbox event StorageCharged (StorageId storageId, BatterySoc newSoc);

// 3. Consistent Aggregates with Automatic Auditing & Versioning
aggregate EnergyStorage[StorageId](WattHours capacity,
mut BatterySoc soc,
mut WattHours storedEnergy
) validates {
require (storedEnergy.value () <= capacity.value (), "Stored energy cannot exceed capacity");
} {
public void charge (WattHours additionalEnergy) {
int newTotal = this.storedEnergy.value () + additionalEnergy.value ();
int cappedTotal = Math.min (newTotal, this.capacity.value ());

        storedEnergy(WattHours.of(cappedTotal));
        int calculatedSoc = (int) ((((double) cappedTotal) / this.capacity.value()) * 100);
        soc(BatterySoc.of(calculatedSoc));

        registerEvent(StorageCharged.create(this.id, this.soc));
    }

}

// 4. Outbound Port with REST Client Adapter & ACL Mapping
port SolarForecastProvider {
schema ForecastResponse {
String location;
int expectedYieldWh;
}

    Optional<WattHours> fetchExpectedYield(StorageId id) {
        adapter rest {
            GET "/api/v1/solar/forecast";
            on 404 return Optional.empty();
        }
        mapping {
            response.expectedYieldWh -> WattHours.value;
        }
    }

}

// 5. Repository with Automatic PostgreSQL Mapping
repository for EnergyStorage {
}

// 6. Use Case Orchestration
usecase OptimizeEnergyFlow (StorageId storageId) validates {
require (storageId != null, "StorageId required");
} {
use EnergyStorageRepository;
use SolarForecastProvider solarProvider;

    load EnergyStorage by storageId;

    var forecast = this.solarProvider.fetchExpectedYield(storageId);
    if (forecast.isPresent() && forecast.get().value() > 0) {
        energyStorage.charge(forecast.get());
    }

    save energyStorage;

    return (energyStorage.id(), energyStorage.soc(), energyStorage.storedEnergy());

}
```

### What the compiler automatically generates from this:

1. **Records & Value Objects:** `WattHours`, `BatterySoc` with static factory methods (`of`), validation, and
   immutability.
2. **Aggregate Root:** `EnergyStorage` with ID, `createdAt`, `updatedAt`, `version`, encapsulation of `storedEnergy` and
   `soc`, as well as an event buffer (`pullDomainEvents`).
3. **Persistence Layer:** `EnergyStorageRepository` (Interface) and `JdbcEnergyStorageRepository` (`@Repository`) with
   ready-to-use Insert/Update SQL, Optimistic Locking, and automatic outbox persistence.
4. **Outbound REST Adapter:** Fully configured Spring `RestClient` adapter for `SolarForecastProvider` that executes
   HTTP calls and converts external JSON responses into domain types.
5. **Use Case Component:** `OptimizeEnergyFlow` as a Spring `@Service`, including a cleanly typed
   `OptimizeEnergyFlow.Result` record.

---

## Quickstart

### 1. Add the Maven Plugin

In your `pom.xml`:

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

### 2. Create the Model

Place your definitions under `src/main/vernac/domain.vernac`.

### 3. Compile

```bash
mvn compile
```

The compiler validates the model semantically and places the Java classes under `target/generated-sources/vernac`.

---

## Try the Showcase

The repository contains a ready-to-run example (`vernac-example-home-energy`) that demonstrates the interaction of
Aggregates, Repositories, Ports, and Use Cases:

```bash
# 1. Start the local PostgreSQL database (e.g., via Docker Compose)
cd vernac-example-home-energy
docker compose up -d

# 2. Run the application (schema.sql initializes the DB automatically)
mvn spring-boot:run
```

Upon startup, the `HomeEnergyDemoRunner` executes a complete vertical slice:

1. Creates a storage with 2,500 Wh in PostgreSQL.
2. Invokes the `OptimizeEnergyFlow` Use Case.
3. Internally queries the solar forecast port, charges the storage within the aggregate, and registers the
   `StorageCharged` event.
4. Saves the new state atomically with a versioning update to the database.

---

## Project Status & Roadmap

Vernac is in active development (**v0.1.0-alpha**). The core compiler, outbox persistence, and the Language Server (LSP)
for IDE support are ready for use.

- [x] **Core Domain Engine:** Aggregates, Entities, Value Objects, Identifiers.
- [x] **Persistence & Outbox:** JDBC code generation, PostgreSQL flattening, Transactional Eventing.
- [x] **Outbound ACL:** Declarative REST Ports with DTO mapping and Custom Adapters.
- [x] **IDE Support:** Vernac Language Server (Syntax Highlighting, Semantic Validation, Snippets).
- [ ] **Inbound Web APIs:** Declarative REST endpoints (`api`, `endpoint`) mapped directly to Use Cases.
- [ ] **AI & Agentic Layer:** Model Context Protocol (MCP) Endpoints for autonomous LLM tools.
- [ ] **Multi-File Builds:** Cross-project symbol table for large domains.

---

## License

Apache License 2.0. See `LICENSE` for details.
