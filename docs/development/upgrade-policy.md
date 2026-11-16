---
title: Mandatory upgrade and migration policy
---
# Mandatory upgrade and migration policy

This is a binding development and release contract. **0.6.0 is the first supported
upgrade baseline**, by maintainer decision on 16 November 2026. Upgrade support from
0.5.x or earlier is not promised or required for 0.6.0. Fresh installation, populated
restart and the reusable 0.6.0 baseline are required. Later releases must automatically
verify every supported baseline; human inspection does not replace migration assertions.

## Required implementation rules

1. Every necessary schema **and data** transformation MUST be a versioned migration
   or an explicit versioned upgrade step with automated tests. This includes runtime
   metadata, serialized/encrypted formats, files and durable worker state, not just SQL.
2. A supported old installation MUST reach the new version directly. Correctness MUST
   NOT depend on starting an intermediate application version, a scheduled worker
   happening to run, manual SQL, or undocumented operator action. If an intermediate
   executable is technically unavoidable, the upgrade tooling MUST retain and execute
   its immutable artifact automatically and test that complete path. Document the
   exception and obtain an explicit support-policy decision before release.
3. Released migration files and frozen baseline files MUST NOT be rewritten, deleted,
   repaired or regenerated to make a test pass. Add a new migration or a new versioned
   fixture. Preserve Flyway histories/checksums and the existing module history owners.
4. SQL transformations MUST use the supported transaction boundary. Nontransactional
   or external transformations MUST retain durable progress, be safe to resume after
   interruption and tolerate repeated execution without duplicate business effects.
   Define refusal, recovery and backup/restore procedures. A downgrade is not implied.
5. Every change affecting persistent state MUST add targeted success and relevant
   failure/interruption/retry tests and update the cross-version expectations. Prove
   business invariants, constraints, references, ownership and tenant isolation;
   successful startup or an unchanged row count alone is insufficient. Never relabel
   a same-version restart as a historical upgrade test.
6. Preserve immutable financial content and its original ownership/history. Test
   pending payments/refunds/mail and other durable work after upgrade, including
   idempotency. Old ciphertext requires deployment-owned old keys; never silently
   replace keys or discard unreadable work during migration.

## Version snapshots and release lifecycle

[The baseline harness](../../tests/upgrades/README.md) captures the complete PostgreSQL
schema, data and Flyway histories from an isolated application populated through its
public boundaries. Fixtures contain synthetic data and explicitly test-only keys only.
They carry exact source provenance, PostgreSQL version, file hashes, historical
migration hashes and independently authored business expectations.

Before release, the 0.6.0 fixture is a **CANDIDATE** and proves capture/restore/startup
of that candidate, not an upgrade from a published 0.6.0. At final release preparation,
generate/review the fixture from the exact approved source/artifacts, record the source
and artifact digests in the release evidence, run the checks, then mark it **FROZEN**.
If pre-release state changes require a replacement, retain the previous candidate in
Git history and explicitly review the replacement; the capture command never overwrites.
A frozen release baseline is immutable. Release approval must check that it actually
represents the final version, not merely that its manifest says FROZEN.

For each later release, restore every registered supported frozen baseline into a fresh
database and start **only the new application**. Its versioned migrations/upgrade steps
must perform the complete transformation. Assert old business expectations, current
behavior, restricted-role isolation, retained invoice bytes and continued pending work.
Repeat startup to prove safe replay and validate unchanged historical Flyway rows.
New expected behavior must be reviewed explicitly; do not regenerate old expectations
from the new application. Add versioned fixtures for newly introduced edge cases.

The dump tests application/schema compatibility, not PostgreSQL engine major upgrades.
Sequential intermediate-version execution, custom application models, external storage
and every possible operator configuration need their own stated test scope. The SaaS
fixture is representative coverage of the bundled modules, not proof for arbitrary
user applications. Existing compiler/module migration suites remain required.

## Mandatory review and release gate

Every persistence PR MUST answer: What old states exist? Which versioned transformation
handles each? Can a supported installation skip intermediate app starts? What happens
on failure, interruption and retry? Which automated assertions prove the result?

Every release MUST run `python3 tools/verify-upgrades.py` and retain the nonempty,
unskipped `UpgradeBaseline` JUnit report together with the source/artifact evidence.
For final approval use `VERNAC_REQUIRE_FROZEN_BASELINES=true`. Never weaken the registry,
remove a failing baseline or silently shorten the supported version range. Changes to
support policy require an explicit maintainer decision and release notes.
