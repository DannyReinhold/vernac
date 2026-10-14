# Learn Vernac by running examples

Use JDK 25. Generated application code targets Java 21. The snapshot examples use
`0.5.0-SNAPSHOT`; install the matching Vernac artifacts before running a standalone
example. Commands below run from the repository root. In IntelliJ's Maven command
window, use the commands shown below. In PowerShell quote complete `-D...`
arguments if they contain dots.

## Choose your starting point

| Example | Learn | What runs | Database needed? |
| --- | --- | --- | --- |
| [Basic project template](../templates/vernac-basic/README.md) | ID, validated value, enum, aggregate behavior | Generated Java and TaskTest | No |
| [Namespaces and values](namespace-values/README.md) | Multiple files, imports, optional values, inline/external behavior, entity modification | Java tests; part of the reactor | No |
| [Schema basics](persistence-schema/README.md) | Flattening and a simple entity containment chain | SQL schema and migration candidate generation | No, until you apply/verify SQL |
| [Shared entity schema](persistence-entities/README.md) | Multiple references to the same entity, lists versus sets | SQL schema and migration candidate generation | No, until you apply/verify SQL |
| [Dispatch](dispatch/README.md) | Complete delivery application, web boundary, transactional planning and durable carrier work | Interactive browser UI and automated walkthrough | PostgreSQL to run; domain/HTTP tests need none |
| [Versioned modules](persistent-modules/README.md) | Independent producer histories, transitive activation, REQUIRED calls and external-VO upgrades | Boot Runner and disposable-database test | PostgreSQL for runtime; not for generation/build |
| [Persistence walkthrough](persistence-demo/README.md) | Aggregate graphs, queries, optimistic locking, events, ports and ACL | Spring Boot runners and tests | PostgreSQL for persistence/events; not for ports/domain tests |

Read them in this order. The schema examples intentionally isolate SQL layout;
they are not duplicate runnable applications. The persistence demo combines the
concepts, with assertions and log output rather than a UI. Dispatch adds the interactive application experience.

## Build the examples

First install dependencies for the standalone examples:

```text
mvn -pl vernac-maven-plugin,vernac-jdbc-spring-boot-starter,vernac-runtime-http -am clean install
```

Then run the checks that do not need a database, sequentially:

```text
mvn -f templates/vernac-basic/pom.xml clean test
mvn -pl examples/namespace-values -am verify
mvn -f examples/persistence-schema/pom.xml clean vernac:schema
mvn -f examples/persistence-entities/pom.xml clean vernac:schema
mvn -f examples/persistence-demo/pom.xml clean test
mvn -f examples/dispatch/pom.xml clean verify
mvn -f examples/type-modules/pom.xml clean install
mvn -f examples/persistent-modules/pom.xml clean install
```

The persistence-demo command runs domain-service and HTTP tests; its PostgreSQL tests
explicitly skip without opt-in. Dispatch uses a separate `database-tests` profile
whose tests fail when its requested database is missing. A successful build with skips is **not** proof of database
behavior. Follow the demo README to start its separate disposable test database
and enable those tests. Do not run simultaneous builds against the same output tree.

Schema basics generates five tables; the shared entity example generates eight.
Inspect `target/vernac-schema/schema.sql` in each directory. Generating a migration
writes source-controlled SQL and schema history: do that in a disposable working
copy for exercises, or review the changes intentionally before committing them.
Never rewrite migrations already applied to a database.

## How to learn from these examples

Read the Vernac model, then the Java test/runner, then the generated Java. Change
one domain rule and a corresponding test. Rebuild and inspect the result. Keep
handwritten code out of generated directories.

The examples distinguish domain behavior from instrumentation: local HTTP fixtures,
intentional event retries and assertion helpers demonstrate failure handling. They
are not production endpoints or idempotency implementations. The domain service
moves a Stop between two Tours; its usecase owns the transaction and saves both
aggregates. It is a teaching scenario, not a complete dispatch policy.

See the [tutorials](../docs/tutorials/index.md) for guided exercises and the
[contracts](../docs/contracts) for the guaranteed semantics.

## Interactive showcase

[Dispatch](dispatch/README.md) combines a delivery board with generated usecases,
aggregate persistence, durable event delivery and a local carrier integration.
