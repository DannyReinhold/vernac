# Vernac build and migration tools for AI agents

Baseline: implemented tooling in the 0.5.0 release line. Use this guide with
the [AI language reference](ai-language-reference.md), the existing POM and the
application's requirements. It is a workflow reference, not additional syntax.

<details class="on-this-page" markdown="1">
<summary>On this page</summary>

* Contents
{:toc}

</details>

## Locate the project and respect its configuration

Run commands from the directory containing the application's POM. A standalone
example under `examples/` has its own POM; it is not necessarily a root reactor
module. Keep the project's compiler, runtime and plugin versions aligned. Do not
replace them with a guessed release or silently upgrade Spring, Java or Flyway.
Use the configured JDK and Maven wrapper if supplied.

Models normally live in `src/main/vernac/<namespace-as-directories>/`. Generated
Java normally lives in `target/generated-sources/vernac/`. Edit Vernac and
handwritten Java; do not repair generated Java by hand. Build output is not the
source of truth for schema history.

## Build and inspect

```text
mvn generate-sources
mvn test
```

The first command executes generation when the POM binds Vernac to that phase.
The second also compiles generated and handwritten Java and runs configured tests.
Inspect the actual result: skipped database tests are not successful database
verification. A language-only check cannot validate arbitrary embedded Java.
An unresolvable embedded-Java class in IntelliJ may require a generation build
and indexing of the generated source root; do not rewrite valid domain code to
accommodate a broken editor inference.

For repository contributors rebuilding local compiler/plugin changes, use the
repository's build instructions and install matching snapshot artifacts before
building a separate example. `verify` alone does not install them locally.

## Inspect the schema without changing a database

```text
mvn vernac:schema
```

Inspect `target/vernac-schema/schema.json` and `schema.sql`. The SQL describes the
complete expected schema; it is not an incremental migration to run against an
existing database. This goal does not execute application SQL.

## Generate the initial or next migration candidate

After changing persisted models, including embedded VOs from dependencies:

```text
mvn vernac:migration -Dvernac.migrationDescription=add_feature_tasks
```

Use a description appropriate to the actual change. The same goal handles initial
and subsequent migrations. It compares the resolved current model with the latest
source-controlled schema snapshot. It does not diff an arbitrary live database.
An unchanged model produces no new candidate.

For applications, review both new files together:

- `src/main/resources/db/migration/V<UTC-timestamp>__<description>.sql`
- `src/main/vernac-schema/history/V<UTC-timestamp>__<description>.json`

Commit the SQL and matching snapshot together after review. Do not hand-edit the
snapshot, remove prior history or overwrite applied/published migrations. For
parallel unpublished histories, rebase and regenerate candidates on the integrated
history instead of merging snapshots blindly.

Module projects with `.vmodule` normally keep SQL under
`src/main/vernac-schema/migration`; module packaging supplies its own migration
bundle. Consumers embedding changed exported VOs need their own application
migration. Activation and module migration ordering are described in the
[module tutorial](tutorials/type-modules.md).

### Required values, destructive changes and errors

A required new column can need an explicit, domain-approved SQL backfill. Do not
invent one to make generation pass. Use the reviewed transformations JSON format
from the [migration tutorial](tutorials/postgresql-migrations.md) and pass it with
`-Dvernac.transformations=src/main/vernac-schema/transformations.json`.

Potentially destructive changes can require `-Dvernac.force=true`. Do not choose
this override without the user's authorization of that concrete change. It only
allows candidate generation; it is not approval, execution or a safety guarantee.
Renames and data conversions require explicit review; the generator cannot infer
the author's intent. Report divergence/backfill errors rather than deleting data,
resetting Flyway or replacing existing migrations.

## Verify candidates on a disposable PostgreSQL server

Obtain a designated test server and credentials from the user/environment:
`VERNAC_CHECK_URL`, `VERNAC_CHECK_USER`, `VERNAC_CHECK_PASSWORD`. The administrative
JDBC URL commonly ends in `/postgres`; the role needs CREATEDB permission. Never
choose a production server or commit credentials.

```text
mvn vernac:schema-check
```

The goal creates two temporary databases, replays migrations in one, creates the
expected schema in the other, compares owned schema objects and removes those
temporary databases. It does not migrate the application database. A successful
check on empty databases does not validate existing production data, deployment
ordering, performance or locking. Also test representative data where relevant.

## Application startup and reporting

After review and approval, configured Spring Boot/Flyway startup applies pending
migrations to the application datasource. Candidate generation and schema-check
do not do this. Do not run startup against an arbitrary environment to test SQL.
Never reset an application database or run Flyway clean/repair as a fallback.

Report changed models, generated candidate paths, checks actually executed,
failures/skips and the review still required. The application owner alone is
responsible for reviewing, testing and approving migrations before application;
automated generation and verification do not transfer that responsibility.

## Shell syntax

Commands above use Maven argument spelling suitable for IntelliJ's Maven command
field (without `mvn`) and POSIX shells. In PowerShell terminals, quote each complete
dotted property argument, for example:

```powershell
mvn vernac:migration "-Dvernac.migrationDescription=add_feature_tasks"
```

Do not put those shell quotes into IntelliJ's Maven command field. Do not change
the goal to `deploy-file` or switch working directories to work around an argument
parsing error; check the active run configuration and shell first.
