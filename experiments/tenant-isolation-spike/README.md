# Tenant isolation feasibility spike

This is a **standalone, test-only experiment**, not a new Vernac application,
production tenancy runtime or SaaS starter. It deliberately does not use the proposed
`tenant` grammar. The JDBC repository/guard remains handwritten under
`src/test`, but its selection/transaction pin and object ownership now use the real
`vernac-runtime` foundation. The root reactor, existing examples and existing Wizard choices are untouched.

It tests the [technical proposal](../../docs/development/tenant-technical-contract.md)
against real PostgreSQL 17, Spring JDBC/transactions and Spring Security from the same
Spring Boot **3.4.2 BOM** used by Vernac. Build with a full JDK 21+ (JDK 25 is fine)
and Maven 3.9+. A JRE without javac is not sufficient.

## Run in PowerShell

From the repository root:

```powershell
docker compose -f .\experiments\tenant-isolation-spike\compose.yaml up -d --wait
$env:VERNAC_PG_TEST_URL = 'jdbc:postgresql://localhost:55439/postgres'
$env:VERNAC_PG_TEST_USER = 'vernac'
$env:VERNAC_PG_TEST_PASSWORD = 'local-spike-only'
mvn -B -ntp -pl vernac-runtime-jdbc -am clean install
if ($LASTEXITCODE -ne 0) { throw "Runtime build failed." }
mvn -B -ntp -f .\experiments\tenant-isolation-spike\pom.xml clean verify
```

Use your configured full JDK; check `mvn -version` if the terminal/IDE chooses a
different Java installation. The tests fail if required environment variables are
missing. They are **not skipped**. Do not point them at an application/production DB:
the bootstrap credentials need CREATE DATABASE/ROLE on a disposable PostgreSQL server.
Each test run creates a uniquely named database, non-superuser schema-owner role and
separate non-owner/NOBYPASSRLS runtime role, then drops them. Runtime queries never use
the bootstrap superuser. No production credentials or paid provider accounts are needed.

If that port is already occupied, override the Compose published port and the JDBC URL
consistently. Do not stop or reset another application's database to run this spike.
The temporary local cluster is removed with:

```powershell
docker compose -f .\experiments\tenant-isolation-spike\compose.yaml down
```

## What it exercises

- Authenticated selection and membership checks; Spring's anonymous authenticated
  token is not a user. An arbitrary requested UUID does not establish tenant authority.
- REQUIRED transaction resources and real connection-local `set_config(..., true)`.
- Generated-style predicates plus FORCE RLS and WITH CHECK on actual non-owner roles.
- byId/find/OR/count, raw SELECT/INSERT/UPDATE/DELETE and tenant-qualified child FKs.
- Same-tenant REQUIRES_NEW suspension/restoration using two physical connections,
  rollback-only REQUIRED nesting and repeated pooled-connection/thread reuse.
- Spring-managed outer/inner usecases and a MANDATORY policy read. The inner permission
  is checked only when reached and before its body; denial rolls back outer writes.
- Handwritten NEW/STORED/DETACHED provenance, rollback-retained ownership, cross-tenant
  save/delete rejection and ordinary optimistic-version conflict.
- Concurrent tenants, stale session settings and mid-transaction setting tampering.

`TenantBoundary` uses production TenantContext/TenantId for selection and the Spring
transaction pin; InvoiceStore uses production TenantOwnership. Its JDBC connection/
RLS/repository code remains a candidate, not the production storage adapter. The fixture's `tenant_id`
column is a test convenience; production mapping uses its separately specified technical column names.
No public API or production-class placement is established by these test helpers.

## What it does not prove

The original TenantIsolationPostgresTest alone does not verify
cursor encryption/binding, complete aggregate graphs/orphan cleanup, full entity
provenance/factory integration, migrations/schema-check, production event claim/payload routing,
generated HTTP/MVC/CSRF integration, quotas, email, billing or a SaaS Wizard. Those remain separate
OPEN requirements, even if this experiment is green. Production guard/actor/Spring APIs now live in vernac-runtime. The entrypoint and policy
fixtures below are test-only; no complete SaaS identity or reachable-usecase audit is supplied.

The separate CI workflow runs this project with a disposable service and rejects
missing/failed/skipped required test cases. See the
[results and next-step record](../../docs/development/tenant-isolation-spike.md).

The fixture now delegates physical connection binding to the production
[JdbcTenantCoordinator](../../docs/development/tenant-jdbc-coordinator.md). The
handwritten schema and repository SQL remain experimental. The original suite executes
14 real PostgreSQL proofs, including unsafe child-table and privilege checks.

## Durable event routing experiment

A second, test-only suite implements the [event routing candidate](../../docs/development/tenant-event-routing-contract.md).
It uses a handwritten mixed-origin payload graph and control-plane delivery queue,
not JdbcEventStore/JdbcEventWorker or generated event adapters. Production durable tenant events are now implemented separately; see the
[tenant events tutorial](../../docs/tutorials/tenant-events.md). This experiment is not
the evidence for generated production delivery.

Seven proofs cover the nullable composite-FK gap, origin-qualified children, mixed
RLS/exact-origin loading, atomic publication/fixed recipients, per-recipient retry
after pool recreation, stale-token rollback/final-attempt recovery, parallel workers
and contaminated global sessions. The original two suites contain 21 tests; missing
PostgreSQL configuration fails rather than skips. The CI gate names every required
proof. The original limitations above still apply to production event integration.


## Authorization entry experiment

Nine additional tests exercise the production body guard with Spring REQUIRED/MANDATORY,
typed resource facts, all/any short-circuit and technical failures, nested actor/tenant
retention, executor cleanup and real MockMvc/Spring Security filter-chain boundaries.
The authorization-entry slice adds nine tests. CSRF remains active, and policies
read through the restricted-role tenant boundary. The [production runtime contract](../../docs/contracts/usecase-authorization-runtime.md)
defines the implemented Java API. These entrypoints/policies remain handwritten fixtures;
the generated language/module proofs below now exercise the permission DSL. A complete
reachable-usecase audit is still absent.

See the [evidence and remaining production work](../../docs/development/authorization-entry-spike.md).
Missing database configuration fails the suite; CI also checks the nine required case
names and rejects skips. To focus locally, add `-Dtest=AuthorizationEntryPostgresTest`
to the existing Maven command. Run the full suite for PR verification.


## Generated authorization language proof

GeneratedAuthorizationPostgresTest compiles actual local permission/requires declarations,
including imported keys and unusual Java-valid constant names. Its six proofs invoke
real generated Spring beans: null checks/denials before validates/body, conditional
inner checks with REQUIRED rollback, inline/external parity, public versus authenticated
actors, missing-Guard startup and cross-tenant nominal resources. The complete experiment
now runs 43 tests including the module proof below; CI names the required cases and
rejects missing/skipped proofs.
See the [language tutorial](../../docs/tutorials/usecase-authorization.md).


## Real module-JAR authorization proof

ModuleAuthorizationPostgresTest packages a producer JAR with exported permission/access
contracts, reads module-contracts-12 metadata, compiles a consumer and generates its
explicit activation. It loads producer classes from the actual JAR, preserving the guard
and actor/tenant/REQUIRED context. Six inherited generated boundary cases plus the
cross-module invocation case run against restricted PostgreSQL roles. Private usecase
access headers remain in compatibility metadata without becoming public exports.
The full experiment executes **43 tests**, none skipped. Missing database configuration
fails. Focus with `-Dtest=ModuleAuthorizationPostgresTest`; the normal PR check runs all.
See the [module tutorial](../../docs/tutorials/module-authorization.md). This is not a
complete identity/role/plan implementation or SaaS exposure audit.
