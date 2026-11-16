# Versioned upgrade baselines

The binding [upgrade policy](../../docs/development/upgrade-policy.md) starts at 0.6.0.
`registry.json` enumerates every required baseline; none is silently skipped.

`0.6.0/` contains a candidate captured from the actual SaaS application:

- `database.sql`: all schemas, data, constraints, RLS policies and Flyway histories;
  portable plain SQL with no source database owner or role grants.
- `expectations.json`: multiple identities and personal/organization accounts, reader
  membership, Unicode documents, Free/paid limits, exhausted durable export quota, pending payment, scheduled downgrade,
  partial refund, original invoice/correction bytes and original browser sessions.
- `test-runtime.json`: synthetic encryption/admission keys and deterministic time.
  These public fixture keys MUST NEVER be used by a deployed application.
- `manifest.json`: candidate/frozen state, source SHA, PostgreSQL version, file hashes,
  immutable historical migration inventory and exact Flyway rows.

No production data, credentials or external provider connection is involved. Restore is
restricted to a newly created random test database; the application uses a fresh
NOSUPERUSER/NOBYPASSRLS role, while the separate migration connection owns the schema.
Keep test-only classes outside the executable production JAR.

## Run

Use the producer/consumer build and PostgreSQL environment described in
[the application workflow](../../.github/workflows/saas-application.yml). Install matching
PostgreSQL 17 client tools (`psql`, `pg_dump`), Java 25, Maven 3.9+, Node 22 and Chromium.
Build the producers and standalone consumer and run `npm ci` in its browser directory.
Then, with `VERNAC_PG_TEST_URL`, `VERNAC_PG_TEST_USER` and
`VERNAC_PG_TEST_PASSWORD` targeting a disposable PostgreSQL server:

```shell
python3 tools/verify-upgrades.py
```

Each U01/U02 test restores a separate pristine baseline. U01 checks the retained facts,
sessions, documents, reader rights, cross-tenant denial and byte-identical PDFs/XML,
then repeats startup and validates Flyway history. U02 continues the pending payment
and remaining refund through the current application without duplicate financial effects.
Reports: `examples/saas-document-workspace/target/upgrade-reports/TEST-UpgradeBaseline.xml`.
Missing cases, failures and skipped tests are rejected.

## Capture / freeze

Capture uses public application flows and the local providers. It never overwrites an
existing directory. To create a new candidate for review:

```shell
python3 tools/verify-upgrades.py --capture /tmp/vernac-0.6.0-candidate
```

Review and copy its four files to `0.6.0/`, then run restore tests. Capturing from a newer
application cannot establish an older version baseline. The initial candidate is not a
published-version upgrade claim. Before 0.6.0 publication, capture from the exact approved
build, retain artifact digests in release evidence, review the contents, mark the manifest
FROZEN and execute with `VERNAC_REQUIRE_FROZEN_BASELINES=true`. The final source/artifact
review remains mandatory; a manually changed state label is not release evidence.

For a later version, keep this fixture unchanged. Extend the harness's accepted version
metadata and registry and add a newly captured fixture plus explicit expectations for
new capabilities. Required data transformations belong to versioned migrations/steps;
intermediate application starts are not an implicit migration mechanism.
