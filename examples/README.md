# Learn Vernac by running examples

Use JDK 25. Generated application code targets Java 21. The snapshot examples use
`0.2.0-SNAPSHOT`; install the matching Vernac artifacts before running a standalone
example. Commands below run from the repository root. In IntelliJ's Maven command
window, omit the leading `mvn`; in PowerShell keep it and quote complete `-D...`
arguments if they contain dots.

## Choose your starting point

| Example | Learn | What runs | Database needed? |
| --- | --- | --- | --- |
| [Basic project template](../templates/vernac-basic/README.md) | ID, validated value, enum, aggregate behavior | Generated Java and TaskTest | No |
| [Namespaces and values](namespace-values/README.md) | Multiple files, imports, optional values, inline/external behavior, entity modification | Java tests; part of the reactor | No |
| [Schema basics](persistence-schema/README.md) | Flattening and a simple entity containment chain | SQL schema and migration candidate generation | No, until you apply/verify SQL |
| [Shared entity schema](persistence-entities/README.md) | Multiple references to the same entity, lists versus sets | SQL schema and migration candidate generation | No, until you apply/verify SQL |
| [Persistence walkthrough](persistence-demo/README.md) | Aggregate graphs, queries, optimistic locking, events, ports and ACL | Spring Boot runners and tests | PostgreSQL for persistence/events; not for ports/domain tests |

Read them in this order. The schema examples intentionally isolate SQL layout;
they are not duplicate runnable applications. The persistence demo combines the
concepts, with assertions and log output rather than a UI. A separate complete
application showcase is planned for 0.2.0.

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
```

The last command runs domain-service and HTTP tests; the PostgreSQL tests explicitly
skip without opt-in. A successful build with skips is **not** proof of database
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
