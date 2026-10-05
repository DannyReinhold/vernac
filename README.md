# Vernac

Vernac is a domain-specific language for building Java applications with
Domain-Driven Design. Define typed identifiers, value objects, entities, aggregates,
repositories, ports, and use cases concisely, and let the compiler generate
their Java implementations following consistent architectural conventions.

Write your model in `.vernac` files and keep custom business logic in Java,
including Java methods embedded directly in your domain definitions.
The Maven plugin generates Java sources during `generate-sources` and adds
them to your project's compilation.

Vernac currently targets Java 21, with Spring-based application and
infrastructure code and PostgreSQL-backed JDBC repositories.
Generated sources are available under `target/generated-sources/vernac`.

This repository already contains:

- a compiler (`vernac-compiler`)
- a Maven plugin (`vernac-maven-plugin`)
- a small runtime API (`vernac-runtime`)
- an LSP server for `.vernac` files (`vernac-lsp`)
- two example modules (`vernac-example`, `vernac-example-home-energy`)

### A tiny example

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

repository for EnergyStorage {
}
```

Vernac generates the validation and mutation code for `EnergyStorage`
from these declarations. The following excerpt shows the generated
invariant check and the setter for `storedEnergy`:

```java
private void validate() {
    if (!(storedEnergy.value() <= capacity.value())) {
        throw new DomainValidationException("Stored energy cannot exceed capacity");
    }
}

public void storedEnergy(WattHours storedEnergy) {
    Objects.requireNonNull(storedEnergy, "storedEnergy must not be null");
    if (Objects.equals(this.storedEnergy, storedEnergy)) {
        return;
    }
    this.storedEnergy = storedEnergy;
    markAsUpdated();
    validate();
}
```

And (if you define a `repository for ...`) a Spring JDBC repository implementation with optimistic locking and event
dispatching (excerpt):

```java

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcEnergyStorageRepository implements EnergyStorageRepository {
    // ...
    @Override
    public EnergyStorage save(EnergyStorage aggregate) {
        EnergyStorage saved = aggregate.version() == 0L ? insert(aggregate) : update(aggregate);
        this.eventDispatcher.dispatch("EnergyStorage", aggregate.id().value().toString(), aggregate.pullDomainEvents());
        return saved;
    }
}
```

### What works today (v0.1.x)

The DSL currently supports:

- `id` declarations (typed identifiers)
- `value` objects
    - single-field values (e.g. `value WattHours(int) ...`)
    - multi-field and nested values (e.g. `value Currency(string); value Money(BigDecimal amount, Currency currency)`)
    - enums (e.g. `value Status = NEW | SHIPPED;`)
    - `validates { require(...) }` blocks
- `event` declarations (`memory` and `outbox`)
- `entity` and `aggregate` (with `mut` (mutable) fields and invariant re-validation on mutation)
- `repository for <Aggregate>` (Spring JDBC implementation is generated - you can easily add custom methods)
- `port` with adapters
    - `adapter rest { ... }` generates a Spring `RestClient` adapter
    - `mapping { ... }` supports simple request/response mapping
    - custom adapters (where you define the adapter logic)
- `usecase` (generates a Spring `@Service` with a @Transactional annotated `execute(...)` method)
- `service` (domain services) and `listener` (event subscribers) blocks (compiler support exists; see the language
  reference for details)

The LSP (`vernac-lsp`) currently provides diagnostics (syntax + semantic), semantic highlighting, hover/definition, and
completions/snippets.
You can directly go to vernac definitions, use context aware completions, and navigate to vernac definitions. Tested in
Intellij.

See the documentation portal at [vernac.org](https://vernac.org) (or `docs/index.md`) and the individual guides:

- `docs/index.md` (Documentation Hub & Overview)
- `docs/language-reference.md` (DSL reference + examples)
- `docs/outbound-port-mapping.md` (Mapping rules and conventions for outbound ports)
- `docs/repository-db-mapping.md` (Database mapping, DDL, table/column conventions, and custom repository beans)
- `docs/usecase-application-layer.md` (Application layer, UseCases, Domain Services, and tuple results)
- `docs/event-driven-architecture.md` (Domain Events, Transactional Outbox pattern, and Listeners)

### Quickstart (local build)

Prerequisites:

- JDK 21
- Maven 3.9+

Vernac is not published to Maven Central yet. Clone this repository
and install its artifacts locally:

```bash
git clone https://github.com/DannyReinhold/vernac.git
cd vernac
mvn -DskipTests install
```

Create a separate directory for your first Vernac project, with this
`pom.xml`:

```xml

<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>vernac-quickstart</artifactId>
    <version>1.0-SNAPSHOT</version>

    <properties>
        <maven.compiler.release>21</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <vernac.version>0.1.0-SNAPSHOT</vernac.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.vernac</groupId>
            <artifactId>vernac-runtime</artifactId>
            <version>${vernac.version}</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
            </plugin>
            <plugin>
                <groupId>org.vernac</groupId>
                <artifactId>vernac-maven-plugin</artifactId>
                <version>${vernac.version}</version>
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
</project>
```

Create `src/main/vernac/energy.vernac`:

```vernac
package com.example.energy;

value WattHours(int) validates {
    require(value >= 0, "WattHours cannot be negative");
}
```

From your new project's directory, run:

```bash
mvn compile
```

Vernac generates `WattHours.java` in
`target/generated-sources/vernac/com/example/energy/domain/`.
Maven automatically compiles the generated sources along with your Java code.

The generated value object can be used from Java:

```java
import com.example.energy.domain.WattHours;

// Inside a Java method:
var energy = WattHours.of(500);
System.out.

        println(energy.value()); // 500
```

`WattHours.of(-1)` throws a `DomainValidationException`.

### Run the showcase

The `vernac-example-home-energy` module demonstrates Vernac with
Spring Boot and PostgreSQL.

Prerequisites: install the repository artifacts as described above
and ensure Docker is running.

From the repository root:

```bash
mvn -pl vernac-example-home-energy spring-boot:run -Dspring-boot.run.workingDirectory=..
```

The application runs with the repository root as its working directory.
Spring Boot's Docker Compose integration starts PostgreSQL using
`vernac-example-home-energy/compose.yaml`.

On startup, the demo runner:

1. Creates and persists an energy storage aggregate.
2. Invokes the generated `OptimizeEnergyFlow` use case, which loads
   the aggregate, calls a REST adapter, updates the stored energy,
   and saves the result.
3. Reloads the aggregate in a separate transaction to verify
   the committed state.

Check the application logs for the resulting charge level and version.
After the update, the reloaded aggregate should have version `2`.

The demo recreates the `energy_storage` table on each startup.

### Status of the project

Many DDD core concepts are already supported, but the project is still in its early stages.
I may change the language definition and the code generators at any time in the stage.

* The language supports DDD concepts on an architectural level and makes you code much more concise.
* Convention over Configuration is a core concept: Write only what varies from the standard.
* The language is designed to be easy to learn and use, with a focus on readability and maintainability.

Current work concentrates on:

* Redefining the anti corruption layers to become more powerful.
* Add api/endpoints constructs (for now you can simply use Java/Spring Controllers).
* Add more and better examples.

### License

Apache License 2.0. See `LICENSE`.
