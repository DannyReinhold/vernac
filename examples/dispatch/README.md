# Vernac Dispatch

**A delivery operation you can use, inspect and change.**

Three vans. Four customers. One overloaded plan waiting to happen.
Dispatch turns those small decisions into a complete application: a responsive web
board, domain rules, transactional usecases, PostgreSQL persistence and a durable
carrier integration. The business model lives in `.vernac` files. Java connects it
to the browser and to the local carrier simulator.

This is an independent example, **not a child module** of the Vernac reactor.
It uses **Vernac 0.3.0-SNAPSHOT**, Java 21 output and the current JDK 25 build setup.

## Start

Install the current artifacts from the repository root first:

```shell
mvn -pl vernac-maven-plugin,vernac-jdbc-spring-boot-starter,vernac-runtime-http -am install
```

Then switch to `examples/dispatch`. With Docker running:

```shell
mvn spring-boot:run
```

Spring Boot starts this example's Compose database. Open **http://localhost:8087**.
Three tours are seeded once; subsequent starts preserve your work. All carrier calls
stay on loopback. There are no credentials for a real external service, map tiles,
tracking scripts or CDN dependencies.

In IntelliJ's Maven command window, enter the goals without the `mvn` prefix and
select this example's POM. A PowerShell launcher is also provided:

```powershell
.\run.ps1 -JdkHome "$env:USERPROFILE\.jdks\openjdk-25.0.1"
```

The web server binds to 127.0.0.1. PostgreSQL uses loopback port **55435** and its own
`vernac-dispatch` Compose project/volume. This example has no authentication or
multi-tenant authorization. Keep it local; do not deploy it as a public service.
The checked-in database password is only for this isolated local demonstration.

## Five minutes that show the architecture

1. **Try an impossible transfer.** Move Harbour Coffee (8 crates) into Express,
   which already carries 3 of its 8 crates. The domain rejects the operation. The
   source plan remains intact and neither aggregate is saved partially.
2. **Make a valid transfer.** Move Studio Nord (6 crates) from North to South.
   Its identity survives; the target list appends the stop in an explicit position.
   Both aggregate writes belong to one generated usecase transaction.
3. **Release South.** Its state changes from PLANNED to READY. The public model
   offers business operations; it does not expose arbitrary field setters.
4. **Arm one carrier outage, then dispatch South.** The tour and its recorded event
   commit. Carrier processing happens later and initially fails with HTTP 503.
5. **Watch the activity panel.** The worker retries and records a carrier receipt.
   The delivery attempt counter and final state come from the database.
6. **Restart the application.** Plans and receipts survive. Search uses a generated
   repository query, not a filter over a hard-coded browser dataset.

The carrier is a **simulator**: it calculates a quote and adds an unexpected response
field to demonstrate tolerant DTO decoding. No real transport booking is made.
The outage button affects the next carrier request, which may be an already queued
job. Use a quiet queue when demonstrating a particular tour's retry.

A completed tour cannot be dispatched again. There is no reset-all action in the
application. Create new tours to repeat the story. To deliberately delete this
example's local database and all its data, stop the application and run
`docker compose down -v` from this directory, then restart.

## Read the application, from the inside out

| Source | Responsibility |
| --- | --- |
| [values.vernac](src/main/vernac/showcase/routing/values.vernac) | IDs, validated names, addresses, positive quantities and states |
| [tours.vernac](src/main/vernac/showcase/routing/tours.vernac) | Aggregate boundary, ordered entity collection, capacity and lifecycle rules |
| [planning.vernac](src/main/vernac/showcase/routing/planning.vernac) | Cross-aggregate domain service and transactional usecases |
| [dispatch.vernac](src/main/vernac/showcase/routing/dispatch.vernac) | Recorded fact, deferred listener and journal port |
| [carrier.vernac](src/main/vernac/showcase/carrier/carrier.vernac) | External capability, REST adapter, DTO schemas and mappings |
| [DispatchController.java](src/main/java/showcase/web/DispatchController.java) | HTTP input conversion and a read-only board projection |
| [JournalImplementation.java](src/main/java/showcase/integration/JournalImplementation.java) | External Java behavior with the same generated self contract |
| [static](src/main/resources/static) | Framework-free, locally served browser UI |

`showcase.routing` owns delivery planning. `showcase.carrier` owns the external
carrier capability and its translation boundary. They are namespaces in one
application, not independently deployed bounded contexts. The distinction is
intentional: a directory alone does not establish a DDD bounded context.

The controller passes IDs and values into generated usecases. Mutation transaction
boundaries stay in those usecases. The board is a handwritten read projection with
a read-only transaction because it queries repositories and application-owned data.
No ORM, lazy-loading proxy or controller-side business transition is involved.

## Persistence and events

The first Flyway migration and its versioned schema snapshot are generated by Vernac.
The second migration creates the application-owned `dispatch_receipt` table. Vernac
owns its namespaced tables; this adapter owns the receipt table. Do not edit generated
Java or already-applied migrations to change the model.

A dispatch event stores the delivery facts needed by the listener. A subsequent
model change cannot silently substitute today's tour contents for that event.
The listener fetches a quote and writes a receipt in its worker transaction.
The receipt uses `tour_id` as a unique business key; `ON CONFLICT DO NOTHING` makes
repeated receipt writes harmless for this one-dispatch-per-tour model.

Delivery is **at least once**, not exactly once. A remote booking API would need an
explicit idempotency strategy: a database rollback cannot undo an external request.
Here the simulated quote request makes no booking. The receipt and successful job
acknowledgement share the worker transaction. Event schema evolution remains an
explicit migration/compatibility concern.

The domain performs precondition checks before common rejected mutations. This does
not change Vernac's general contract: arbitrary failed mutations are not restored
in memory. Requests discard failed instances and load fresh state next time.
Optimistic locking detects overlapping writes. A stale browser's command loads the
current aggregate before executing; this UI does not expose a client version token
or implement a full stale-edit review workflow.

## Automated checks and walkthrough

Without PostgreSQL:

```shell
mvn test
```

This compiles the complete model and generated Java, tests planning rules and performs
real loopback HTTP requests through the generated carrier adapter and mappings.

For the full story, start the disposable test database and explicitly select the
integration profile:

```shell
docker compose --profile test up -d postgres-test
mvn verify -Pdatabase-tests
```

The profile uses port **55436** and fails if PostgreSQL is unavailable; it never
silently treats an absent database as a successful integration check. Override
`DISPATCH_TEST_DB_URL`, `DISPATCH_TEST_DB_USER`, and `DISPATCH_TEST_DB_PASSWORD` when
needed. Use a dedicated database with no concurrent demo activity.

To run the narrated walkthrough against the application database and then keep the
web board open:

```shell
mvn spring-boot:run -Dspring-boot.run.arguments=--dispatch.walkthrough=true
```

Or use `run.ps1 -Walkthrough`. Each run creates separately named tours and retains
them so you can inspect the outcome. `run.ps1 -DatabaseTests` starts the test service
and runs the explicit integration profile.

## Change something meaningful

Add an optional delivery instruction value, generate a migration candidate, review
it, and verify it before applying. The [migration tutorial](../../docs/tutorials/postgresql-migrations.md)
explains the mechanism. Start by keeping this example's stored event payload unchanged;
changing nested payload values is event evolution too.

The generated migration is a **candidate**. You are responsible for reviewing,
testing and approving it before use. No automated check guarantees safety for real
production data or deployment conditions.

## Deliberate scope

Dispatch illustrates a coherent architecture, not a production fleet-management
product. Authentication, authorization, route optimization, geographic maps,
cancellation/compensation, real carrier bookings and operations dashboards are not
implemented. The activity panel is a small diagnostic projection, not a new general
query language or a public API for Vernac's event tables.

## Configure the carrier endpoint

`CarrierHttp` declares `URI baseUri from "dispatch.carrier.base-url"` in its
`configuration` block. Spring resolves this at startup; no URI bean is needed.
The local carrier simulator listens on loopback port **8091**. Set
`DISPATCH_CARRIER_SIMULATOR_PORT` to move it; the default base URL follows that port.
`CARRIER_BASE_URL` overrides the target URL explicitly. Keep it on the local
simulator for this walkthrough, including its deliberate outage simulation.
The database integration profile uses simulator port **8092** to avoid the running UI.

For example, set `DISPATCH_CARRIER_SIMULATOR_PORT=8093` before launching the app
if 8091 is occupied. Missing/invalid REST endpoints fail during adapter construction,
with a property-key diagnostic that omits its value. Unit tests still construct
the adapter directly with the random-port fixture URI.

[The configuration contract](../../docs/contracts/adapter-configuration.md) covers
other adapter settings, typed conversion and inline/external behavior access.

## Generated HTTP inbound boundary

`showcase/web/tour-api.vernac` generates the create and release endpoints used by
the web UI. `POST /api/tours` reads an explicit DTO, wraps title/capacity through
validated domain factories, calls CreateTour and maps TourId to a 201 response.
`POST /api/tours/{id}/release` parses the UUID, calls ReleaseTour and returns 204.
The controllers open no transactions: the usecases keep that responsibility.

The other board, integration-status and command endpoints remain handwritten to
show Java interoperability. See the [inbound tutorial](../../docs/tutorials/inbound-http.md)
for the contract, safe error defaults and external error policies.
