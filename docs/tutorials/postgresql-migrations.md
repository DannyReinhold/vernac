# Generate and review PostgreSQL migration candidates

**You alone are responsible for reviewing, testing and approving every migration
before applying it.** Vernac helps prepare candidates. Neither generation nor a
successful automated check guarantees correctness, safety or suitability for your
production data and deployment.

This tutorial covers the new schema tools. The new JDBC repository implementation
and entity persistence are separate increments. Do not use the old repository
SQL generator against these new schemas.

## Start with the example

Install the current compiler and Maven plugin from the repository root:

```text
mvn -pl vernac-maven-plugin -am clean install
```

Open `examples/persistence-schema`. It has a repository-backed aggregate with values,
an enum and a list. From that directory:

```text
mvn vernac:schema
```

Inspect `target/vernac-schema/schema.json` and `schema.sql`. This command modifies
only build output, not source history or a database. The SQL is an **initial-schema
candidate**, not an incremental migration for an existing database.

## Create the initial candidate

```text
mvn vernac:migration -Dvernac.migrationDescription=initial
```

Vernac writes a new UTC timestamp version in these source-controlled directories:

```text
src/main/resources/db/migration/V<timestamp>__initial.sql
src/main/vernac-schema/history/V<timestamp>__initial.json
```

Commit both after review and verification. A normal compile does not rewrite them.
An unchanged model produces no additional candidate. `vernac.migrationVersion` can
supply an explicit 17-digit UTC timestamp; it must be later than existing timestamp
versions. The project should consistently use this Flyway version scheme.

## Generate the next candidate

Add `Title? note` to Tour and run:

```text
mvn vernac:migration -Dvernac.migrationDescription=add_note
```

The candidate adds a column instead of recreating the table. Review it before use.
Existing migration files are never overwritten. SQL candidates can be edited during
review; the disposable database check verifies the resulting schema. Do not edit
published migration history: add a forward correction instead.

If you remove the field, generation stops and names the potentially destructive
operation. After reviewing that risk, you may explicitly continue generation:

```text
mvn vernac:migration -Dvernac.migrationDescription=remove_note -Dvernac.force=true
```

Force is only a generation safeguard override. It neither applies nor approves SQL.
It cannot infer required values, conversions or renames.

## Required values and conversions

A new NOT NULL column on an existing table requires an explicit SQL backfill.
Known integral widening and float-to-double widening are automatic. Other type/codec
changes require an explicit USING expression and force approval. Configure
expressions in a reviewed JSON file, keyed by `namespace/table/column`, for example:

```json
{
  "backfills": {
    "org.example.delivery/Tour/title": "'Reviewed initial title'"
  },
  "conversions": {}
}
```

Pass it with `-Dvernac.transformations=src/main/vernac-schema/transformations.json`.
Conversion expressions refer to quoted existing column names, e.g. `"count"::bigint`
(with quotes escaped inside JSON). Unused keys are rejected to catch misspellings.
Do not guess a domain value simply to make a migration pass. Expressions are trusted
migration-author code, not end-user input. Complex transformations may require
editing the candidate SQL and testing it with representative data.

## Explicit enum codes

Pass `-Dvernac.enumCodes=src/main/vernac-schema/enum-codes.json` with a complete
mapping for each overridden enum, for example:

```json
{
  "org.example.delivery.TourStatus": {
    "SCHEDULED": "PLANNED",
    "COMPLETED": "COMPLETED"
  }
}
```

This preserves the old storage code while renaming PLANNED to SCHEDULED. Codes are
retained in subsequent model snapshots. Unknown enums, incomplete mappings and
duplicate codes are rejected. This is infrastructure configuration, not domain behavior.

## Replay and compare on a disposable PostgreSQL server

Set `VERNAC_CHECK_URL`, `VERNAC_CHECK_USER` and `VERNAC_CHECK_PASSWORD` outside source
control. The URL is an administrative JDBC URL to a **test server**, e.g.
`jdbc:postgresql://localhost:5432/postgres`. Its role needs CREATEDB permission.
Never use a production server for this workflow. Then run:

```text
mvn vernac:schema-check
```

The goal creates two randomly named `vernac_check_...` databases. It runs all Flyway
migrations in one and the expected initial DDL in the other, compares managed table
catalogs and drops only those newly created databases in a finally block. It does
not migrate, clean or repair the database named in the administrative URL. Process
termination or a server failure can leave test databases behind; remove only those
known to belong to the interrupted run.

Foreign application tables are ignored. Previously owned tables remain in the
ownership history, so forgetting to drop a removed Vernac table is detected.
Owned table columns, defaults, constraints, indexes and user triggers are checked.
This is a structural test on empty databases, not proof that historical data can be
converted or that production locks and timings are acceptable.

Bind `schema-check` to Maven `verify` in your CI profile. CI is the enforceable gate;
a local pre-push hook is only a convenience. Credentials belong in environment or
Maven settings, never in the POM or committed files.

## Parallel development

Snapshots form a linear fingerprint chain. Two branches based on the same predecessor
are detected even if Git merges their separate files without a textual conflict.
Rebase and regenerate **unpublished** candidates on top of the integrated history.
Do not choose one snapshot blindly, silently reorder published migrations or rewrite
shared deployment history. Separate workspaces need no shared developer database.

Read the [mapping contract](../contracts/postgresql-persistence.md) and
[implementation notes](../development/postgresql-schema-tooling.md).

## Inspect entity containment

The separate [entity graph example](../../examples/persistence-entities/README.md)
adds shared entity references, subentities, direct optional references, lists and sets.
Use it to inspect table ownership and composite foreign keys without modifying the
value-only example. For generated JDBC operations, use the [runnable database tutorial](database-persistence.md).
