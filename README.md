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
- an IntelliJ IDEA plugin with a bundled language server (`vernac-intellij`)
- the reusable language server behind the editor integration (`vernac-lsp`)
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

The IntelliJ IDEA plugin provides semantic highlighting, syntax and semantic
diagnostics, hover documentation, code completion and snippets, and Go to
Declaration within Vernac files. See [IntelliJ IDEA plugin](#intellij-idea-plugin)
for installation instructions.

See the documentation portal at [vernac.org](https://vernac.org) (or `docs/index.md`) and the individual guides:

- `docs/index.md` (Documentation Hub & Overview)
- `docs/language-reference.md` (DSL reference + examples)
- `docs/outbound-port-mapping.md` (Mapping rules and conventions for outbound ports)
- `docs/repository-db-mapping.md` (Database mapping, DDL, table/column conventions, and custom repository beans)
- `docs/usecase-application-layer.md` (Application layer, UseCases, Domain Services, and tuple results)
- `docs/event-driven-architecture.md` (Domain Events, Transactional Outbox pattern, and Listeners)

### Quickstart (local build)

Prerequisites:

- JDK 25 to build the full repository, including the IntelliJ plugin
- Maven 3.9+

The compiler, runtime, language server, and generated application code retain
their Java 21 target. Only the IntelliJ plugin targets Java 25. Ensure that
`mvn -version` reports Java 25; when building from IntelliJ, also select JDK 25
in Settings → Build, Execution, Deployment → Build Tools → Maven → Runner → JRE.

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

### IntelliJ IDEA plugin

The `vernac-intellij` plugin integrates Vernac directly into IntelliJ IDEA.
It registers `.vernac` files and starts its bundled language server automatically.
No LSP4IJ installation, manual file-type mapping, or external server path is needed.
The server uses the IDE's Java runtime, independently of your project's JDK.

#### Requirements

- IntelliJ IDEA 2026.2.3 (build `262.10968.63`) or a newer build in the 2026.2 series
- JDK 25 and Maven 3.9+ to build the plugin from source

The current plugin descriptor limits compatibility to the 2026.2 series.
Later IDE release series require a compatibility check and an updated descriptor.

#### Build and install

From the repository root, build the plugin and its dependencies:

```bash
mvn -pl vernac-intellij -am package -DskipTests
```

The installation archive is generated at:

```text
vernac-intellij/target/vernac-intellij-0.1.0-SNAPSHOT-plugin.zip
```

1. In IntelliJ IDEA, open **Settings → Plugins** (or **Plugins** on the welcome screen).
2. Open the gear menu and choose **Install Plugin from Disk…**.
3. Select the ZIP archive above and restart the IDE if prompted.
4. Open a project and a `.vernac` file to activate language support.

If you previously configured Vernac manually in LSP4IJ, disable that server
configuration and remove its manually created `*.vernac` file-type association
so the Vernac plugin can own the extension.

#### Try it

Create `mytest.vernac` in your project:

```vernac
package demo;

id StorageId;

aggregate EnergyStorage[StorageId](int capacity) {
}
```

Use **Go To → Declaration or Usages** from the editor context menu on the
`StorageId` reference in the aggregate to navigate to its declaration.
Code completion is available through **Code → Code Completion → Basic**;
invalid syntax is reported in the editor.

The IDE plugin provides editing support. Java source generation still runs
through the Vernac Maven plugin during your project's Maven build.

### Run the showcase

The `vernac-example-home-energy` module demonstrates Vernac with
Spring Boot and PostgreSQL.

Prerequisites: install the repository artifacts as described above
and ensure Docker is running.

From the repository root:

```bash
mvn -pl vernac-example-home-energy spring-boot:run "-Dspring-boot.run.workingDirectory=.."
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
