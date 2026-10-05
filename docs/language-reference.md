# Vernac Language Reference (v0.1.x)

This document describes the **current** Vernac DSL as implemented in this repository.
If in doubt, the grammar is the source of truth: `vernac-compiler/src/main/antlr4/org/vernac/compiler/parser/Vernac.g4`.

Vernac files typically live in `src/main/vernac/*.vernac`.

## 1. File structure

```vernac
package com.example.domain;
import com.example.other.*;

// top-level declarations...
```

- `package ...;` is optional.
- `import ...;` is optional (imports can use `.*`).

## 2. Identifiers (`id`)

Typed IDs help avoid parameter mix-ups.

```vernac
id OrderId;
```

Expected outcome:

- a value type with a `value()` accessor (and an `of(...)` factory for existing ids and a create () factore to create a
  new id.)

## 3. Value objects (`value`)

### 3.1 Single-field values

```vernac
value WattHours(int) validates {
    require(value >= 0, "WattHours cannot be negative");
}
```

- The constructor is not exposed directly.
- Use `Type.of(...)` to create instances.
- `validates { require(...) }` becomes runtime checks throwing `DomainValidationException`.

### 3.2 mutli field values

```vernac
value Weight(BigDecimal amount, String unit);
```

Basically the same rules apply as for single value objects. But multi value objects are handles differently by
repositories (see there).

### 3.3 nestes value objects

A value object can other value objects as components:

```vernac
value Currency(String);
value Money(BigDecimal amount, Currency currency);
```

### 3.4 Enums

```vernac
value OrderStatus = NEW | PAID | SHIPPED;
```

Optional database value:

```vernac
value CountryCode = DE("DEU") | US("USA");
```

## 4. Events (`event`)

```vernac
memory event SomethingHappened(OrderId id);
outbox event StorageCharged(StorageId storageId, BatterySoc newSoc);
```

- `memory` events are dispatched in-process.
- `outbox` events are dispatched through the runtime `EventDispatcher` abstraction (see `vernac-runtime`).

## 5. Aggregates and entities (`aggregate`, `entity`)

### 5.1 Basics

```vernac
entity AirConditioner[AcId](mut AcMode mode) {
    public void switchMode(AcMode newMode) {
        mode(newMode);
    }
}

aggregate EnergyStorage[StorageId](
    WattHours capacity,
    mut BatterySoc soc,
    mut WattHours storedEnergy
) validates {
    require(storedEnergy.value() <= capacity.value(), "Stored energy cannot exceed capacity");
} {
    public void charge(WattHours additionalEnergy) {
        // raw Java is allowed inside method bodies
        storedEnergy(WattHours.of(123));
    }
}
```

Notes:

- Fields marked `mut` get generated setter-like methods (e.g. `soc(...)`) that update timestamps and re-run validations.
- Aggregate roots implement `AggregateRoot<IdType>` and have `createdAt`, `updatedAt`, and `version`.
- Aggregates can register events via `registerEvent(...)` from inside raw Java method bodies.

## 6. Repositories (`repository`)

```vernac
repository for EnergyStorage {
}
```

Generated artifacts (current behavior):

- a repository interface in the domain package
- a Spring JDBC implementation (`Jdbc...Repository`) with:
    - `byId(...)`, `save(...)`, `delete(...)`
    - optimistic locking via `version`
    - event dispatching via `EventDispatcher`

## 7. Ports (`port`)

Ports are outbound interfaces; adapters are generated per method.

### 7.1 Schema

```vernac
port SolarForecastProvider {
    schema ForecastResponse {
        String location;
        int expectedYieldWh;
    }
}
```

Schemas generate simple DTO classes suitable for JSON serialization.

### 7.2 REST adapter

```vernac
port SolarForecastProvider {
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
```

Current behavior:

- generates a Spring `RestClient` adapter class implementing the port
- supports `on <status> return <expr>` and `on <status> throw <Type>` rules
- supports simple field mapping rules in `mapping { ... }`

## 8. Use cases (`usecase`)

```vernac
usecase OptimizeEnergyFlow(StorageId storageId) validates {
    require(storageId != null, "StorageId required");
} {
    use EnergyStorageRepository;
    use SolarForecastProvider solarProvider;

    load EnergyStorage by storageId;
    // raw Java statements are allowed
    save energyStorage;

    return (energyStorage.id(), energyStorage.soc());
}
```

Generated artifacts (current behavior):

- a Spring `@Service` class with an `execute(...)` method
- dependencies declared with `use` become constructor-injected fields
- `return (...)` creates a `Result` record

## 9. Domain services (`service`)

```vernac
service PriceCalculator(OrderId id) : Money {
    // implementation currently uses raw Java statements
    return Money.of(0);
}
```

## 10. Event listeners (`listener`)

```vernac
listener StorageCharged {
    use EnergyStorageRepository;
    // raw Java statements
}
```

## 11. Raw Java

Vernac allows embedding raw Java in specific places (e.g. method bodies, statements inside `usecase` blocks).
This is a pragmatic escape hatch, especially in v0.1.x.
