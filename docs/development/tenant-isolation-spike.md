---
title: Tenant isolation — first executable feasibility record
---
# Tenant isolation — first executable feasibility record

Status: **first test-only spike; production implementation remains OPEN**.
Baseline: 2026-11-11, after the merge of the authorization/technical tenancy design.
The standalone [experiment](../../experiments/tenant-isolation-spike/README.md) does not
modify the root reactor, generated code, current examples or current Wizard templates.

## Purpose and executed environment

Validate transaction-bound tenant context, real PostgreSQL protection and the proposed
in-method usecase guard before changing compiler/runtime/module persistence paths.
The fixture uses handwritten candidate classes, not generated `.vernac` models.

Local verification: PostgreSQL **17.11**, Zulu JDK **25.0.4.1** compiling with `--release 21`,
Maven **3.9.11**, Spring dependencies resolved from the existing **Spring Boot 3.4.2 BOM**.
The database is disposable and has separate schema-owner and restricted runtime login
roles. It is not a mocked SQL/JDBC or in-memory database test. The CI workflow repeats
this with PostgreSQL 17.11 and JDK 25; its actual run status is recorded separately on
the PR, not assumed from local results.

Local result: **12 tests, 0 failures, 0 errors, 0 skipped**. Documentation links and
12 existing tooling tests also pass. The workflow's report gate was exercised against
the real report and deliberately modified copies: missing/skipped proofs were rejected.
No root reactor/IntelliJ build is claimed for this isolated test-only change.

## Proof inventory

| Named test | Observed scope of proof |
| --- | --- |
| missingAuthenticationMembershipAndTransactionFailClosed | Anonymous token is rejected; arbitrary tenant selection denied; missing/wrong transaction and manual entry fail closed |
| byIdFindOrCountAndRawSqlAreTenantScoped | Predicates plus raw-SQL RLS isolation; other-tenant/missing IDs give identical not-found |
| unmarkedGlobalDataNeedsNoTenantAndDoesNotBindOne | Global reference table stays usable without tenant; raw tenant reads without context see no rows |
| runtimeRoleAndForcedOwnerProtectionAreReal | Runtime non-owner role is usable; schema owner/superuser/BYPASSRLS rejected; FORCE RLS affects owner's ordinary SELECT |
| rowSecurityBlocksCrossTenantWritesAndForeignKeysBlockCrossTenantLinks | Raw cross-tenant writes blocked/hidden; tenant-qualified FK rejects wrong-tenant child; child SELECT isolated |
| requiredAndRequiresNewBindDifferentConnectionsAndRestoreOuterContext | Two PostgreSQL backend connections, suspend/resume, inner rollback cleanup and no tenant switch |
| caughtInnerRequiredFailureMakesOuterTransactionRollbackOnly | Caught failure from a real proxied inner call still prevents outer commit |
| innerPermissionIsCheckedOnlyWhenReachedAndBeforeItsBody | Real Spring proxies, MANDATORY policy read, conditional invocation and denied inner body; outer version update rolls back |
| ownershipAndRollbackPreventRetaggingWhileStaleVersionsStillConflict | Candidate root provenance, rejected cross-tenant save/delete/detached object; rollback does not clear ownership |
| sameThreadAndPooledConnectionsDoNotLeakAcrossRepeatedRequestsOrRollback | Alternating tenants across one Hikari pool, success and rollback, cleaned selection/resource/session |
| twoThreadsHaveIndependentSecuritySelectionsAndPhysicalTransactions | Simultaneously active tenants retain separate Spring security/tenant/connection state |
| staleSessionTenantAndMidTransactionSettingTamperingAreRejected | Existing session-level contamination and a changed transaction-local setting do not become accepted authority |

These tests are part of TENANT-01–04 and authorization transaction/invocation feasibility,
not completion of all TENANT-01–08 or AUTH-01–08 requirements. The CI assertion gate
requires every named test and rejects skipped/missing/failed reports. Missing local
PostgreSQL variables fail setup rather than silently skipping the suite.

## Findings to carry into implementation

1. A transaction synchronization with explicit suspend/resume is necessary: a simple
   ThreadLocal tenant value cannot represent REQUIRED versus REQUIRES_NEW connection state.
2. Bind/check the actual DataSource transaction, not merely a global 'transaction active'
   flag; a transaction for another DataSource is not sufficient.
3. The guard can run after REQUIRED begins, so a policy's MANDATORY repository access
   works without moving policy SQL into an HTTP filter or opening a competing transaction.
4. An explicit predicate and RLS protect different failure paths. FORCE RLS and non-owner
   runtime role configuration are essential; testing only as schema owner/superuser is
   not valid production-role evidence.
5. An object needs provenance distinct from version zero. A manually reconstructed root
   must not be mistaken for a brand-new root; rollback must not allow retagging elsewhere.
6. Global/unmarked data can remain unaffected while tenant tables fail closed without
   context. This does not make global business operations publicly authorized.

The spike does not falsify the selected architecture. It is not a production-ready
implementation to copy blindly: candidate binding validation checks this fixture's root
table, and the provenance helper covers roots only. Full storage plans and all owned
entities/tables need their own integration and validation.

## Not yet proved or implemented

Production grammar/generation and live IDE projections; immutable trusted actor context;
all/any and module-exported policies; complete graph provenance/factory handling;
owned-list/set/orphan cleanup; cursor binding/count-window guarantees; metadata
compatibility/migration backfills/RLS drift/schema-check; event coordinator/payload
routing; HTTP/MVC/CSRF behavior; tenant provisioning and the rest of SaaS remain OPEN.
No result here justifies changing A19/A20 or authorization feature rows to VERIFIED.
No provider/payment/mail implementation, mandatory release gate or SaaS Wizard is added.

A later module/event spike must prove production claim/processing/failure phases and
routing envelopes rather than equating a REQUIRES_NEW transaction test with a finished
worker. External effect reconciliation is a separate obligation.

## Example and Wizard separation

The complete SaaS app will be a separate example, not an alteration of the simple
Spring Boot/basic examples. New Vernac Project will gain an explicit **SaaS application**
choice alongside existing choices. Its generated starter must use real reusable modules,
local PostgreSQL, UI and predefined credential-free test providers. This is tracked as
A21 in the [release ledger](release-0.6.0.md), not implemented by this experiment.

## Next implementation boundary

After review/CI of this spike, introduce tested general tenant context/provenance and
shared storage-plan/schema changes in small PRs; then integrate production repositories,
queries, modules/events and compiler/tooling. Retain unscoped regression tests throughout.
Keep the [technical contract](tenant-technical-contract.md) synchronized with executable
findings, and attach production evidence before marking any whole-SaaS capability complete.
