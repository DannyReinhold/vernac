# Tenant isolation feasibility spike

This is a **standalone, test-only experiment**, not a new Vernac application,
production tenancy runtime or SaaS starter. It deliberately does not use the proposed
`tenant` grammar. The candidate repository/guard is handwritten; all code lives under
`src/test`. The root reactor, existing examples and existing Wizard choices are untouched.

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

`TenantBoundary` has no dependency on the production runtime. The fixture's `tenant_id`
column is a test convenience; production mapping will use the agreed technical name.
No public API or production-class placement is established by these test helpers.

## What it does not prove

It does not implement or verify production code generation, exported module metadata,
cursor encryption/binding, complete aggregate graphs/orphan cleanup, full entity
provenance/factory integration, migrations/schema-check, event claim/payload routing,
HTTP/MVC/CSRF behavior, quotas, email, billing or a SaaS Wizard. Those remain separate
OPEN requirements, even if this experiment is green. There is no broad all/any policy
engine, immutable actor-context model or production startup-audit implementation here.

The separate CI workflow runs this project with a disposable service and rejects
missing/failed/skipped required test cases. See the
[results and next-step record](../../docs/development/tenant-isolation-spike.md).
