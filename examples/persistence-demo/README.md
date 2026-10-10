# Vernac persistence walkthrough

A standalone Spring Boot application, intentionally **not** a child of the Vernac
reactor. It uses the current reviewed JDBC generator, not the legacy energy example's
mapping. Java usecase methods own transactions; generated repositories require them.

## Run

Prerequisites: Maven, a JDK suitable for building Vernac, Docker Desktop with Linux
containers, and local port 55433 available. The application targets Java 21.
The development database credentials in this example are disposable local defaults.

From the Vernac repository root, install the current snapshot:

```text
mvn -pl vernac-maven-plugin,vernac-jdbc-spring-boot-starter -am clean install
mvn -f examples/persistence-demo/pom.xml spring-boot:run
```

The Boot Maven plugin sets the working directory to this example. Boot starts
`compose.yaml`, waits for PostgreSQL, and Flyway applies the committed initial
migration before the runner starts. There is no `schema.sql`, DROP-on-start or
automatic destructive schema synchronization. Stop/restart retains the named volume.

The runner checks:

1. Creating and saving a Tour with repeated/shared Stops and a shared Parcel.
2. Loading a complete graph with identical Java instances for repeated identities.
3. Declarative equality/inequality searches, title/timestamp ordering, Optional results
   and complete graphs returned by searches.
4. BigDecimal scale preservation, entity updates and list reordering.
5. Rejection of stale optimistic versions and conflicting Java entity instances.
6. Removing individual links without deleting shared state; removing the final links
   deletes unreachable entities and subentities.

It leaves a Tour in the database for the migration exercise. Each run creates its own
Tour; it does not delete earlier demonstration data. Two additional query fixtures
are removed in `finally` blocks. The retained Tour has a unique per-run title.

## Database integration tests

These tests are opt-in and use **a separate disposable database**, never the demo's
persistent volume. From this example directory:

```text
docker compose --profile test up -d --wait postgres-test
mvn test -Dvernac.test.database=true
```

The test database uses port 55434 and tmpfs. Tests verify persisted rows, shared
identity, list order, orphan cleanup, transaction requirements, optimistic conflicts,
and rollback behavior. Ordinary `mvn test` skips these eight database tests explicitly.

## Concurrent requests

With the separate test database running, execute only the concurrency test:

```text
mvn test -Dvernac.test.database=true -Dtest=PersistenceConcurrencyTest
```

The test uses two independent READ COMMITTED transactions and a third connection
observing `pg_blocking_pids`. Both writers load the same root version and change
different children. The first saves but holds its transaction open. Only after
PostgreSQL reports that the competing UPDATE is blocked does the first commit.
The second must then fail with optimistic locking and roll back.

A fresh transaction loads the complete aggregate and verifies the winning child
change, list order, shared identities and unchanged losing child. Direct SQL checks
also rule out leaked entity/link rows. Coordination and lock waits have deadlines.
This test requires a real PostgreSQL server; a single-backend emulator cannot prove
these concurrency semantics. It covers four deliberately coordinated interleavings:
concurrent saves, deletion winning against a save, a save winning against deletion,
and a commit between a reader's root and child queries.
The delete/save cases also check every model table for retained or removed rows. These are
not tests of every isolation level or a general load/stress test.

The READ COMMITTED reader test uses a **test-only** exclusive lock on the Stop table
as a scheduling gate. The reader can read the root, but pauses at the child SELECT.
After the writer commits, the reader must reject the changed root version. A fresh
read then restores the complete new state. Production repositories do not use this
table lock and do not silently retry failed reads.

READ COMMITTED gives each SELECT its own snapshot, not one snapshot for an entire
multi-query aggregate load. See [PostgreSQL transaction isolation](https://www.postgresql.org/docs/17/transaction-iso.html).

## Next migration

Follow [the Flyway tutorial](../../docs/tutorials/flyway-workflow.md).
`migration-demo/model-v2.vernac` is a prepared next model, **outside the compiler's
source root**, adding an optional dispatch reference. No V2 migration has been
pre-applied: you generate and review it as part of the exercise.

The application developer alone is responsible for reviewing, testing and approving
migration candidates before applying them. Generation and automated verification do
not guarantee correctness, safety or suitability for a deployment.

## Existing PostgreSQL / CI

Disable Compose with `SPRING_DOCKER_COMPOSE_ENABLED=false`; configure `DEMO_DB_URL`,
`DEMO_DB_USER`, and `DEMO_DB_PASSWORD`. Never commit real credentials. For tests use
`-Dvernac.test.url=jdbc:postgresql://host:port/disposable_test_database` with the test
credentials supplied through `VERNAC_TEST_USER` and `VERNAC_TEST_PASSWORD`. `schema-check` uses its own VERNAC_CHECK_* variables
and requires permission to create temporary databases, not permission on production.

See [database tutorial](../../docs/tutorials/database-persistence.md) and
[persistence contract](../../docs/contracts/postgresql-persistence.md).

## Declarative search walkthrough

The aggregate explicitly declares `list Tours`. Its repository now contains:

```vernac
find Tours withStatus(TourStatus status) where status = :status order by title;
find Tours otherStatus(TourStatus status) where status != :status order by title;
find Tours createdSince(Instant cutoff) where createdAt >= :cutoff order by createdAt desc;
find Tour? titled(Title title) where title = :title;
```

`DeliveryDemoUseCases` exposes each query through a read-only transaction. The
runner demonstrates them before removing any children, so search results can also
verify repeated list entries and the direct reference share the same Stop instance.

Watch for `Query withStatus(PLANNED)`, `Query otherStatus(PLANNED)`,
`Query createdSince(...)` and `Optional queries` in the log. The A and C tours are
planned; B is completed. Status searches cover the whole database. Only the runner's
assertions and displayed subsets filter to the three IDs created by this run, so
previous demonstration data does not invalidate the checks. The repository itself
performs the status predicate and the ordering in PostgreSQL.

The timestamp query includes the exact cutoff (`>=`). Tied timestamps have no
implicit tie-breaker; the runner checks descending order without demanding a
particular order for ties. The title query illustrates both one match and no match.
Multiple matches still raise `NonUniqueQueryResultException`, as covered by the
repository integration tests; titles do not become unique database columns.

No migration is needed for these search declarations or the root result collection.
The prepared V2 model includes the same methods, so replacing the model for the
Flyway exercise retains the runner's Java API.

See [the query tutorial](../../docs/tutorials/repository-queries.md) for the supported
operators, scalar semantics and current boundaries.
