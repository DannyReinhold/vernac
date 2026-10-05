# Vernac

Vernac is a small DSL + compiler that generates Java code for typical Domain-Driven Design building blocks.

You write a `.vernac` file, the Maven plugin compiles it during `generate-sources`, and your project ends up with plain
Java sources under `target/generated-sources/vernac`.

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
    mut WattHours storedEnergy
) {
    public void charge(WattHours additionalEnergy) {
        int newTotal = this.storedEnergy.value() + additionalEnergy.value();
        storedEnergy(WattHours.of(newTotal));
        registerEvent(StorageCharged.create(this.id, this.storedEnergy));
    }
}

repository for EnergyStorage {
}
```

Vernac turns this into regular Java classes (excerpt from the real generated code in `vernac-example-home-energy`):

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

See:

- `docs/language-reference.md` (DSL reference + examples)
- `docs/outbound-port-mapping.md` (Mapping rules and conventions for outbound ports)

### Quickstart (local build)

Vernac is not published to Maven Central yet; for now, build and install it locally:

```bash
mvn -q -DskipTests install
```

Then, in your own project:

1. Add the dependency `org.vernac:vernac-runtime:${vernac.version}`
2. Add the plugin `org.vernac:vernac-maven-plugin:${vernac.version}` with goal `compile`
3. Put `.vernac` files under `src/main/vernac`
4. Run `mvn compile`

Generated sources land in `target/generated-sources/vernac` and are added as a compile source root.

### Run the showcase

The module `vernac-example-home-energy` is a small Spring Boot demo with PostgreSQL (see `compose.yaml`).

```bash
cd vernac-example-home-energy
docker compose up -d
mvn spring-boot:run
```

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
