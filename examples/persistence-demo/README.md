# Vernac persistence walkthrough

A standalone Spring Boot application, intentionally **not** a child of the Vernac
reactor. It demonstrates complete aggregate persistence, events and outbound adapters. Java usecase methods own transactions; generated repositories require them.

## Run

Prerequisites: Maven, JDK 25, Docker Desktop with Linux
containers, and local port 55433 available. The application targets Java 21.
The development database credentials in this example are disposable local defaults.

From the Vernac repository root, install the current snapshot:

```text
mvn -pl vernac-maven-plugin,vernac-jdbc-spring-boot-starter,vernac-runtime-http -am clean install
mvn -f examples/persistence-demo/pom.xml spring-boot:run
```

The Boot Maven plugin sets the working directory to this example. Boot starts
`compose.yaml`, waits for PostgreSQL, and Flyway applies the committed migrations
before the runners start. There is no `schema.sql`, DROP-on-start or
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
and rollback behavior. Ordinary `mvn test` skips database tests explicitly.

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

## Event walkthrough

After the persistence runner, `EventDemoRunner` executes a second walkthrough using
`src/main/vernac/org/example/delivery/events.vernac`:

1. Publish a recorded DemoNotice through the generated transactional PublishDemoNotice
   usecase. The synchronous listener runs before publisher commit.
2. Inspect the two still-PENDING delivery jobs inside that same transaction.
3. Commit, then wait outside the transaction for the automatically scheduled worker.
4. DeliverDemoNotice succeeds on its first attempt. RetryDemoNotice deliberately fails
   once, then succeeds on its second attempt. The other listener is not repeated.
5. Publish another event and roll back its transaction. Its synchronous observation
   occurred, but neither the event row nor its delivery jobs remain.

The successful event and both SUCCEEDED jobs remain for inspection:

```sql
SELECT * FROM "org.example.delivery"."@event:DemoNotice";
SELECT "eventId", "listenerId", status, attempts, "lastError"
FROM "vernac"."@delivery"
WHERE "eventType" = 'org.example.delivery.DemoNotice'
ORDER BY "createdAt", id;
```

Expect `Event walkthrough passed` in the log. The runner waits up to 30 seconds and
fails visibly for FAILED/BLOCKED jobs or timeout. Keep automatic event delivery enabled.
The example shortens polling and initial retry backoff in application.yaml; runtime
production defaults are unchanged.

EventDemoProbe is demonstration instrumentation: its attempt counter lives in memory
and deliberately fails once per event per application process. It is not a production
idempotency mechanism. Restarting during a retry may cause another deliberate failure.
The log output is an external side effect and cannot be rolled back.

The additional event migration and matching schema-history snapshot are committed.
Existing demo databases at the original schema can advance normally; no volume reset
is required. Databases still containing uncommitted migration experiments must first
be reconciled with the committed example, as in the migration tutorial. Never alter
an already applied migration. Review all migration candidates before application;
you remain responsible for their suitability and effects.

Run the same walkthrough as an opt-in database test, from this example directory:

```text
mvn test -Dvernac.test.database=true -Dtest=EventDemoTest
```

This uses the separate test database on port 55434. It exercises actual generated
listeners, Boot auto-configuration, Flyway and the automatic worker, rather than
invoking the worker directly. The persistent application database uses port 55433.

## Ports and ACL walkthrough

A third application runner, PortDemoRunner (`@Order(2)`), follows the persistence
and event runners. It executes generated outbound REST code against a deterministic
local carrier fixture. No external account, API key or fixed HTTP port is required.
No schema change or migration is introduced by this walkthrough.

The model is [carrier.vernac](src/main/vernac/org/example/carrier/carrier.vernac).
Its domain port accepts Shipment values and returns ShippingQuote values; HTTP,
wire codes and adapter-local DTOs stay in the adapter. A POST unwraps Destination
and ParcelWeight into JSON. The response maps a nested Cost DTO into ShippingPrice,
then into a ShippingQuote; the ordinary domain factories validate the result.

The walkthrough verifies:

1. Successful POST and GET, nested response mapping and tolerated extra JSON fields.
2. Explicit optional 404 behavior and a DELETE returning 204.
3. Correct encoding of a reference containing Unicode, slash, space, question mark
   and plus; the base URI's `/api/` prefix is preserved.
4. Recognized 422/403 responses mapped to declared ShippingRejection enum constants.
5. Explicit local authentication responsibility for 401, unresolved responsibility
   for an unfamiliar 403, and a remote 503 failure with Retry-After metadata.
6. Invalid JSON, missing required response fields and a negative domain price
   rejected as INVALID_RESPONSE with causes retained.
7. Malformed error JSON and absent optional error codes falling back to technical
   failures, preserving the HTTP status and decoding diagnostics where applicable.
8. Distinct per-call invocation IDs, no automatic request-payload capture and exactly
   14 HTTP requests (no hidden retries).

Each successful check writes `Verified ports/ACL: ...`. Expected failures are logged
with exception type, code/category, status, operation and invocation ID. The final
message is `Ports/ACL walkthrough passed: 16 checks, 14 HTTP requests`. Any mismatch
throws an exception and fails the runner. `demo.run=false` disables all three runners.

The scenario names (for example `unsupported` and `broken-json`) are explicit test
fixtures, not production routing logic. LocalCarrierServer binds only to 127.0.0.1
on an automatically allocated port. Each run owns and closes both server and client,
including on failure. They do not keep the application alive after the walkthrough.

For this local fixture, PortDemoWalkthrough constructs the generated CarrierHttp
adapter and calls it through ShippingCarrier. Namespace `org.example.carrier` sits
outside the application's `org.example.delivery` component scan deliberately: the
fixture owns the endpoint lifecycle. A real application would discover/import the
adapter bean, supply its HttpAdapterClient through Spring and set
`delivery.carrier.base-url` in application properties,
as shown in the HTTP tutorial. Neither a database transaction nor a database rollback
is implied by this outbound communication.

Run the same walkthrough as a normal test **without Docker or PostgreSQL**, from the
repository root after installing the current snapshot:

```text
mvn -f examples/persistence-demo/pom.xml clean test -Dtest=PortDemoTest
```

Expected: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`. The test executes the
walkthrough twice to detect leaked scenario state and also invokes the application
runner. For the entire application, including persistence and events, use the usual
start command (Docker/PostgreSQL are still required for those two walkthroughs):

```text
mvn -f examples/persistence-demo/pom.xml clean spring-boot:run
```

See [the guided ports tutorial](../../docs/tutorials/ports-walkthrough.md) for a
reading order and the [declarative error tutorial](../../docs/tutorials/declarative-errors.md)
for the error-rule semantics.

## Domain service and usecases

Read `tour-planning.vernac`: TourPlanning moves a Stop between two Tours, while
MoveStopToTour loads and saves both aggregates in its generated transaction.
TourPlanningTest checks the service without Spring or PostgreSQL, including rejection
of a move to the same Tour before any state changes. This deliberately small policy
is not a complete logistics planner.

`record-tour-notes.vernac` and `record-tour-notes-externally.vernac` contrast inline
and external usecase implementations; `describe-tour.vernac` demonstrates a result.
The handwritten DeliveryDemoUseCases is runner orchestration showing Java interop,
not a requirement to implement all usecases by hand.
