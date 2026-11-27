# Vernac build and migration tools for AI agents

Use the application's POM to select the matching tool version. Read the section for the build,
schema or migration step you are performing. For a model edit, start at the
[AI task index](ai-language-reference.md).

<details class="on-this-page" markdown="1">
<summary>On this page</summary>

* Contents
{:toc}

</details>

The supported application stack is Java/Spring Boot. Preserve its dependency
injection, transaction, adapter and runtime integration; compiling a domain type or
running a plain Java test does not verify a framework-independent application path.
Do not replace Spring Boot with another framework based on the separation of Maven
artifacts. See the [compiler architecture](development/compiler-architecture.md).

For the Boot 4 baseline used in 0.6.0, follow the [Boot 4 migration requirements](development/spring-boot-4-baseline.md),
including Jackson 3 generated DTO codecs and converters. Do not mix Security 7 with
a Boot 3 application or select a Jackson 2 converter for these codecs.

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

## Connect the 0.7 development CLI over MCP

Configure a local stdio MCP client to launch the installed CLI. The second argument is the
workspace and the optional third argument selects the test profile (`domain`, `mvc` or
`postgres`). For example, use this server entry in a client that accepts `command` and `args`.

```json
{"command":"vernac","args":["mcp","/work/project","domain"]}
```

- Use `workspace_revision`, then `check_saved` or `test_affected_saved` with that revision.
  The first test request establishes the comparison baseline. Send the returned revision next.
- For unsaved edits, use `open_overlay`. Send its `client` and `revision` to
  `check_overlay`, `test_affected_overlay`, or `test_scenario`. Use `close_overlay` when done.
- To run affected tests in the background, use `start_affected_saved` or
  `start_affected_overlay`. Keep the returned `runId` and poll `run_status`. Use
  `cancel_run` to request cancellation. `CANCELLING` means the current test may still be
  finishing. A cancelled run leaves the previous passing test baseline in place unless the
  request explicitly invalidated it with `opaqueChange`.
- Supply an optional unique `requestKey` when starting a background run. A retry with the same
  key and parameters returns the original run ID while it is retained in that server process.
- Saved-source background runs can be looked up by `runId` from a new MCP session for the same
  workspace. If the previous process exited mid-run, the result is `INTERRUPTED`. The most recent
  32 saved reports are retained under `target/vernac-scenario-runs-v1`, which a clean build can
  remove. Private overlays, replay keys, and the passing test baseline start fresh on restart.
- Use `describe_type` to inspect fields and methods. The preview tools for aggregate creation
  and value-object fields return ordinary edit batches. Send those edits, or other source edits,
  to `apply_checked` to publish a validated overlay revision. Overlay edits stay in memory.
  Supply a unique `requestKey` to `apply_checked` or `apply_edits` to retrieve the original
  response on a retry with the same arguments. The server retains the latest 32 keyed edit
  responses and drops an overlay's responses when it closes.
- Read the bundled task index at `vernac://docs/ai-language-reference.md` or the FAQ at
  `vernac://docs/faq/index.md`. `search_docs` returns versioned section matches. Read those URIs
  through the client's resource support.

Read tool data from `structuredContent`. A stale revision returns a tool error containing
`currentRevision`; inspect and reconcile the current source before retrying an edit. A failed
scenario is a case outcome in a successful tool response. For Maven-backed JVM fixtures, prepare
the project and supply its dependency classpath in `VERNAC_PROJECT_CLASSPATH`.

## Build and inspect

```text
mvn generate-sources
mvn test
```

The first command executes generation when the POM binds Vernac to that phase.
The second also compiles generated and handwritten Java and runs configured tests.
Inspect the actual result: skipped database tests are not successful database
verification. See [local testing and skipped cases](development/local-testing.md)
for prerequisites, Windows setup and separate browser suites.
A language-only check cannot validate arbitrary embedded Java.
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
