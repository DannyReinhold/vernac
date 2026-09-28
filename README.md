## Current Status & Implemented Features

Vernac currently provides a working end-to-end compilation pipeline from DSL source code down to idiomatic Java domain
models using JavaPoet and ANTLR4.

### 1. Implemented DDD Building Blocks

* **Value Objects (`value`)**:
    * Immutable classes with private constructors and static `of(...)` factories.
    * Validation rules via `validates { require(...); }`.
    * Support for First-Class Domain Collections (`collection Tasks { ... }`).
    * Record-style accessors and defensive copying.

* **Domain Events (`event`)**:
    * Guaranteed non-null payload fields, auto-generated `eventId` (`UUID`), and `occurredOn` (`Instant`).
    * Technical factories (`of(...)`) for deserialization and business factories (`create(...)`).
    * `@Dispatch(mode = "MEMORY" | "OUTBOX")` metadata support for message-routing strategies.

* **Aggregate Roots (`aggregate`)**:
    * Explicit identity definition syntax: `aggregate Name[IdType optionalIdName](...)`.
    * Implements `AggregateRoot<ID>` interface with technical metadata (`createdAt`, `updatedAt`, `version`).
    * Identity-only `equals()` and `hashCode()` semantics based solely on the aggregate ID.
    * Internal event collection via `registerEvent(...)` and consumption via `pullDomainEvents()`.
    * Dual-factory lifecycle: `create(...)` for new instances (with validation) and `reconstitute(...)` for
      repository/database hydration (bypassing validation and event dispatch).
    * Immutability by default; mutable attributes marked via `mut` generate private, validating setters for controlled
      state mutation.

* **Entities (`entity`)**:
    * Unified identity syntax: `entity Name[IdType](...)`.
    * Implements `Entity<ID>` with strict ID-based equality.
    * Encapsulated internal mutation via `mut` and custom domain methods.

### 2. Architecture & Modules

* **`vernac-runtime`**:
    * Lightweight marker interfaces (`AggregateRoot<ID>`, `Entity<ID>`, `DomainEvent`).
    * Runtime base exceptions (`DomainValidationException`).
    * Dispatch annotations (`@Dispatch`).
* **`vernac-compiler`**:
    * Complete ANTLR4 grammar (`Vernac.g4`) handling chained expressions and custom method bodies.
    * Strongly-typed AST with sealed interfaces (`AstNode`, `TopLevelDefinition`).
    * Specialized JavaPoet generators: `ValueObjectGenerator`, `EventGenerator`, `AggregateGenerator`,
      `EntityGenerator`.
    * Central `VernacCompiler` pipeline supporting in-memory compilation and filesystem output
      (`VernacCompilationResult`).

### 3. Verification

* Comprehensive test suite covering:
    * Parser and AST visitor semantics.
    * Generator outputs for all DDD artifacts.
    * End-to-end file generation test compiling a multi-model `.vernac` specification into disk-ready Java files.

### 4. Maven

You can add the vernac-maven-plugin to your maven build pipeline to automatically compile `.vernac` files into Java
classes during the build process. This plugin leverages the `vernac-compiler` module to perform the compilation and
generates the necessary Java files in the specified output directory.

Simply add the plugin to your `pom.xml` file:

```
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
