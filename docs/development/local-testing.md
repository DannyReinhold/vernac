---
title: Local tests, prerequisites and skipped cases
---
# Local tests and skipped cases

Use JDK 25 and Maven 3.9+. Most skipped tests in an ordinary root build are database
integration tests deliberately guarded by
`@EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL", matches=".+")`.
No URL means no disposable database was explicitly selected: the tests are skipped,
not passed. An unreachable configured database fails the tests instead. Inspect the
`<skipped message="...">` in each module's `target/surefire-reports/TEST-*.xml` or
`target/failsafe-reports/TEST-*.xml` for the exact reason; not every skip has the same cause.

## Run Java database tests on Windows

Start a disposable PostgreSQL 17 server, for example in a separate terminal:

```powershell
docker run --rm --name vernac-tests -p 127.0.0.1:5432:5432 -e POSTGRES_USER=vernac -e POSTGRES_PASSWORD=ephemeral-local-only -e POSTGRES_DB=postgres postgres:17.11
```

Then set these in the **same PowerShell process** that launches Maven:

```powershell
$env:VERNAC_PG_TEST_URL = 'jdbc:postgresql://localhost:5432/postgres'
$env:VERNAC_PG_TEST_USER = 'vernac'
$env:VERNAC_PG_TEST_PASSWORD = 'ephemeral-local-only'
mvn clean verify
```

The test role needs CREATE DATABASE and CREATE ROLE for isolated test databases and
restricted runtime-role checks. These are disposable-server credentials only. Tests
create/drop their own resources; do not target an application database. Set the same
variables in the IDE run configuration if building there; setting terminal variables
does not change the environment of an IDE process already running.

`mvn test` runs Surefire tests; `mvn verify` also runs configured Failsafe `*IT` suites.
Standalone examples are separate Maven projects. The older persistence-demo additionally
uses `-Dvernac.test.database=true`; consult its README for its own configuration.
