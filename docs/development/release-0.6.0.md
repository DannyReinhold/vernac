---
title: 0.6.0 completeness and release acceptance
---
# 0.6.0 completeness and release acceptance

Status: **NOT READY — planning only**. Baseline: 2026-11-11.
Owner of final go/no-go: maintainer. Release implementation/evidence owner: each PR
contributor, with a dedicated review pass before release preparation.

The [SaaS plan](saas-0.6.0-plan.md) defines the outcome. No row below is complete
because this planning document exists. Required extension artifacts are part of the
acceptance boundary even if they have their own versions or repositories.

## Mandatory tenant isolation scope

[Declarative tenant isolation](tenant-isolation-0.6.0.md) is required for 0.6.0.
D13 and A19/A20 are OPEN. The [technical tenant proposal](tenant-technical-contract.md)
now specifies the candidate implementation and TENANT-01–08 feasibility requirements. No policy-only account filter or manually repeated query
predicate satisfies these requirements. Apply isolation to J01–J09 and the release
manifest, including extension modules and non-database output paths.

## Required extension and webhook safety follow-up

[Tenant-safe webhooks and handwritten repositories](tenant-webhooks-and-repository-extensions.md)
are mandatory 0.6.0 acceptance boundaries. Generated persistence/event isolation alone
is insufficient. A22/A23 remain OPEN until supported extension APIs, provider routing,
documentation/examples and negative integration tests are implemented and verified.

## Authorization contract proposal

[Usecase authorization](usecase-authorization-contract.md) proposes the D02/D03
semantics and AUTH-01–08 test requirements. It is not implementation evidence.
A03/A04/A15/A18 remain OPEN; the Spring transaction/security spike is still pending.

## First executable tenancy spike

The [test-only feasibility record](tenant-isolation-spike.md) supplies narrow evidence
for transaction binding/RLS/guard ordering, not production implementation. A03/A04,
A19/A20 and the new A21 SaaS Wizard/example requirement remain OPEN. Subsequent PRs
must link actual generated/runtime integration and full E2E evidence before verification.

## Additive production runtime foundation

[Tenant context/provenance APIs](tenant-runtime-foundation.md) now have production
runtime code and tests; the PostgreSQL spike consumes them. This is partial evidence,
not completion of storage/schema/generated/module/SaaS paths. A19/A20/A21 stay OPEN.

## Completion policy

Use these states: `OPEN`, `IN PROGRESS`, `IMPLEMENTED / UNVERIFIED`, `VERIFIED`,
`BLOCKED`. A merged PR is not automatically VERIFIED. Missing evidence means OPEN
or UNVERIFIED, never an implicit pass. Track negative cases and operational recovery,
not just a demo happy path. Skipped tests do not count as successful execution.

Every requirement needs:

1. Accepted contract/decision, implementation PRs and exact source commits.
2. Named automated tests, passing run/report links and environment/provider mode.
3. User documentation/example paths, plus relevant installed-plugin/UI evidence.
4. Known limitations, failure/recovery behavior, reviewer and verification date.

A record may link to a separate evidence file when the table becomes too large.
Reference the requirement ID from PR descriptions/tests where practical. Do not put
credentials, personal data or provider payloads in evidence.

## Acceptance ledger

Evidence is currently **none** for all rows. All statuses are OPEN.

| ID | Release requirement | Minimum verification | Status / evidence |
| --- | --- | --- | --- |
| A01 | Identity self-registration, verification, login/logout/recovery | Happy path; expired/reused tokens; duplicates/concurrent registration; disabled user; secure browser flows | OPEN / none |
| A02 | Personal/organization accounts and memberships | Invitations/expiry/replay, roles, account switching, invalid membership and cross-account access | OPEN / none |
| A03 | Typed rights and usecase all/any requirements | Compiler diagnostics, nested conditional calls, absent context/provider, object ownership, system identities | OPEN / none |
| A04 | Endpoint/usecase security integration | REST and MVC, explicit public endpoints, webhook verification, CSRF, context cleanup, no proxy/invocation bypass in supported paths | OPEN / none |
| A05 | Dynamic per-operation and current-capacity limits | Repository-derived facts, concurrent last slot, release on delete/deactivation, rollback and scoped isolation | OPEN / none |
| A06 | Quotas and reservation APIs | Known/dynamic amounts, nested ownership, zero/unlimited, period edges, concurrent consumption, functional return/void/failure, no hidden callback retry | OPEN / none |
| A07 | External accounting and recovery | Independent durable accounting where required, timeout/unknown outcome, crash/restart, expiry races, duplicate logical requests | OPEN / none |
| A08 | Plans, prices and subscriptions | Free/paid, entitlement assignment, lifecycle, renewal/change/cancel, over-limit state, existing-customer plan changes | OPEN / none |
| A09 | Payment adapter(s) and local provider | Agreed real provider sandbox, checkout, pending/success/refusal/cancel, verified duplicate/out-of-order webhook, refund/reconciliation | OPEN / none |
| A10 | Customer invoicing | Agreed seller/tax scope, calculation/rounding, concurrent unique numbering, immutable issue, PDF/agreed formats, customer access, correction and payment linkage | OPEN / none |
| A11 | System email | Verification/recovery/invite/billing templates, durable dispatch, retries/failures, duplicate handling, local inbox and agreed real-provider check | OPEN / none |
| A12 | Reusable UI and customization | Registration through cancellation/invoice download, useful denials/upgrade guidance, no UI-only authorization, account isolation | OPEN / none |
| A13 | Modules and migrations | Separate published-like consumers, exports/activation/provider profiles, fresh install and upgrade with existing subscriptions/invoices, ownership/history checks | OPEN / none |
| A14 | Deployable complete sample | Clean-machine local start without production secrets; deployment configuration, persistent restart, health/logging and recovery walkthrough | OPEN / none |
| A15 | Language/tooling/documentation completeness | Parser/analyzer/generator consistency, live Java APIs, module metadata, LSP/IDE completion/navigation/diagnostics, compiled reference/AI examples, tutorials/FAQ | OPEN / none |
| A16 | Release distribution integrity | Exact-commit dry run, required test execution, actual packaged ZIP verification, supported-IDE smoke, Maven/extension fresh consumption and Marketplace approval | OPEN / none |
| A17 | Credential-free predefined test providers | Fresh application startup with fixed success and selectable failure sequences, local inbox, same processing paths, no unexpected provider traffic, explicit test/production bindings | OPEN / none |
| A18 | Business observability and actionable failures | Stable safe reasons across API/UI/workers; rights/limits/payment/renewal failures and recovery; useful event correlation; seeded sensitive values absent from normal/error logs | OPEN / none |
| A19 | Automatic shared-database tenant isolation | Declarative classification; byId/find/CRUD/graphs/pages/count/cursors; cross-tenant references and object-save rejection; missing context and pooling/concurrency tests under the actual database role | OPEN / none |
| A20 | Tenant lifecycle and whole-SaaS propagation | Personal/shared membership, provisioning/global data, module metadata/DDL/backfill, event/payment/mail workers, files/caches and fresh/upgrade E2E isolation | OPEN / none |
| A21 | Separate SaaS sample and New Project Wizard option | Existing non-SaaS examples/templates still build and run unchanged; explicit SaaS selection generates a complete independent app with module activation/UI/local DB and test providers, no API keys; verify packaged plugin | OPEN / none |
| A22 | Supported handwritten repository extensions | Pre-query runtime binding/RLS; canonical graph hydration/write/version/timestamp semantics; return-shape/tenant/provenance guards; scalar/count paths; compiled guide/examples and negative restricted-role tests | OPEN / none |
| A23 | Verified webhook tenant routing | Provider proof and scoped resource registry; no claimed-tenant/global fallback; early/unknown/conflicting route; duplicate/out-of-order/concurrent/restart processing; safe logging; real/test-provider journeys | OPEN / none |

## Required integrated journeys

Each journey needs an automated integration/E2E test through the real generated
application boundaries. Mocks of every module or logs without assertions are not
end-to-end evidence. Use disposable PostgreSQL, deterministic clocks and controllable
local providers; add sandbox evidence for the agreed real adapters separately.

- **J01 First customer:** clean deployment → register → verify email → login → personal
  account/free plan → create product data → permission/limit denial explained.
- **J02 Organization:** invite second member → accept → switch role/account → exercise
  allowed/denied operations → prove another account's data/counts remain inaccessible.
- **J03 Paid conversion:** checkout → verified callback → subscription/benefits → paid
  operation → correctly issued customer invoice → system email and authorized download.
- **J04 Payment failure/recovery:** pending/refused payment, duplicate/out-of-order
  callbacks, retry, renewal failure, agreed grace/suspension and later reconciliation.
- **J05 Subscription changes:** upgrade/downgrade/cancel under the agreed effective-date,
  price and existing-customer policy; correct entitlements/limits and invoice effects.
- **J06 Restrictions under load:** concurrent last-slot allocation, dynamic counts,
  time-period rollover, nested usecases and local rollback versus external consumption.
- **J07 Restart/upgrade:** preserve customers/subscriptions/issued invoices, replay
  application and module migrations, recover durable email/payment/reservation work.
- **J08 Developer adoption:** new independent app → module dependencies/activation →
  provider configuration → usable UI/deployment; no edits to generated files or
  undocumented local source dependencies.
- **J09 Extension safety:** implement a custom query through the supported Java extension
  path → hydrate complete tenant graphs → reject foreign/detached/NEW/invalid results →
  prove stale writes and scalar/count isolation with restricted parallel connections.

Extend J01/J03/J04/J06 with the predefined test-provider scenarios and verify the
corresponding actionable user/developer outcomes. Capture correlated operational
events, assert no sensitive values appear in logs and prove production cannot silently
substitute a simulated success. J08 must work without real provider API keys.

All journeys are currently **OPEN / no evidence**.

## Dedicated pre-release completeness audit

Schedule a separate review PR after integration, before calling the release ready.
The review may use the same contributor but must be a new, explicit assessment of
the final implementation, not merely a repetition of prior progress summaries.

- [ ] Reconcile every plan/release promise, D01–D13 decision and A01–A23 row against
      actual source, tests, public APIs, generated output and consuming application.
- [ ] Inspect stubs, TODO/FIXME/UnsupportedOperationException paths, empty adapters,
      commented/disabled/skipped tests and happy-path-only simulators. Classify relevant
      findings; zero TODOs globally is not required and a text scan is not sufficient.
- [ ] Inspect every required module and selected provider, including work in other repos.
      Resolve incompatible defaults, licensing/distribution or migration gaps.
- [ ] Execute J01–J09 and required module/compiler/runtime/PostgreSQL/tooling suites;
      investigate flaky results, missing reports and unexpected skips rather than retrying
      until green. Record the final passing run and reason for each legitimate exclusion.
- [ ] Audit security/context/transaction boundaries, cross-account isolation, public
      registration/webhooks, administration, secrets/logging and durable recovery.
- [ ] Validate invoice/tax behavior against the agreed supported scope; record outstanding
      financial/legal review. Do not infer correctness from successful PDF generation.
- [ ] Build standalone examples and follow tutorials from clean environments. Inspect UI
      and user-facing failure messages, not only HTTP status codes or backend logs.
- [ ] Audit documentation, FAQs, AI reference, plugin What's New and release notes for
      unsupported promises and drift. Clearly document actual supported boundaries.
- [ ] Produce an explicit findings report: complete areas, missing/partial areas, risks,
      supporting evidence and a GO / NO-GO recommendation. Open blockers as tracked work.

## Enforced gate to implement before release

**This planning PR does not implement a CI gate.** Until the following automation is
implemented and proven, this release remains NOT READY. Extend the existing release
tooling rather than relying on a maintainer remembering to inspect this Markdown.

- [ ] Maintain a machine-readable acceptance manifest with required IDs, suite names,
      artifact/module inventory and evidence references; reject missing/unknown/unverified
      requirements and unresolved blocking decisions. Keep this ledger aligned with it.
- [ ] Audit test reports from the final source build: missing, failed or skipped required
      suites must fail, including standalone app/module E2E jobs outside the root reactor.
- [ ] Collect required extension-repository attestations with immutable commits/artifact
      digests and matching accepted versions; test the actual assembled application.
- [ ] Bind the audit and manifest to the release source SHA and dependency/artifact digests.
      Source/dependency changes invalidate evidence and require relevant verification again.
- [ ] Verify actual packaged plugin classes/resources, descriptor/toolchain versions and
      compatibility report; a source-only API check cannot approve the uploaded ZIP.
- [ ] Require explicit maintainer approval of the final completeness report and candidate
      before publication. No automatic merge or release follows from a green build alone.
- [ ] Test the gate itself: removing a required capability/report, skipping a test,
      introducing a stale source SHA or marking evidence unknown must block publication.

## Final go/no-go

GO requires all agreed requirements VERIFIED, J01–J09 evidenced, D01–D13 resolved,
no release-blocking findings, implemented passing automated gate and recorded
maintainer approval. Optional future work must be identified explicitly, not used
as a label for unfinished agreed functionality. A scope reduction requires an
explicit maintainer decision, updated promises and a documented rationale.

Then follow [release operations](../maintainers/releasing.md). Confirm the actual
merged source SHA, candidate provenance and exact ZIP before publishing; changing
only a version does not include an unmerged fix. Preserve reviewed artifacts and
avoid redeployment of immutable Maven coordinates. Publication and Marketplace
approval are separate from implementation completion.

Fresh consumption of the published Maven/extension artifacts and installation of
the approved Marketplace plugin are the final distribution checks. Failures remain
open release incidents, not a completed release with an unrecorded caveat.

## Partial JDBC tenant infrastructure evidence

The [production JDBC coordinator](tenant-jdbc-coordinator.md) supplies explicit
connection binding and role/RLS flag checks. Generated repository invocation, exact
policy/schema validation and migrations are still OPEN. This evidence does not
verify A19/A20/A21 or complete automatic tenant isolation.


## Required tenant migration transition evidence

Before A19/A20 can become VERIFIED, the Maven/Flyway path must prove the
[tenant classification transition contract](tenant-technical-contract.md#tenant-classification-changes-in-mavenflyway-candidates):
adding tenant requires explicit historical-data assignment; removing tenant is
blocked by default as an isolation collapse even without physical data loss.
Acknowledged candidates must contain specific CLI warnings and SQL comments naming
the affected graph and exposure risk, in addition to the standard responsibility
header. Force cannot supply missing backfill or collision-resolution decisions.
Both directions require snapshot/diff and real PostgreSQL replay/schema-check proofs.
This remains OPEN; the current JDBC coordinator does not implement migration tooling.

## Partial tenant schema infrastructure evidence

The [tenant schema foundation](tenant-schema-foundation.md) adds model format 2
classification, fresh-table candidate protection and policy drift catalog comparison.
Existing-table classification conversions are blocked even with force until reviewed
data assignment/removal support exists. This is a guard, not completed conversion
tooling. Source modifier/owned graph propagation, generated repositories, module/event
integration and full conversion acceptance remain OPEN; A19/A20/A21 are unchanged.

## Explicit classification rule and SaaS starter prerequisite

The [classification contract](../contracts/explicit-tenancy.md) records the agreed
source-controlled tenancy explicit mode in application/module definitions and
global aggregate spelling. Compiler/editor validation is a partial foundation.
The separate SaaS Wizard choice must enable the rule only when its complete tenant
starter works. Existing simple templates stay unchanged. Tenant syntax, owned
mapping, runtime wiring and migration transitions remain OPEN; A19/A20/A21 are not
VERIFIED by this rule.

## Local generated tenant persistence evidence

The [local integration](tenant-persistence-integration.md) connects tenant aggregate
classification to complete local owned schema graphs and generated JDBC CRUD/search/
count/cursor paths. Verification includes actual generated Java, restricted PostgreSQL
roles, shared entities/nested collections, global coexistence, concurrent requests and
optimistic conflicts. This is partial A19 evidence, not A19/A20/A21 completion.
Durable tenant event routing is now implemented. Historical classification
conversions, identity/authorization and the SaaS starter remain OPEN or fail closed.

## Tenant module metadata evidence

module-contracts-11 preserves exported aggregate scope and entity ownership/restoration
capability. Read-only import views retain scope; scope changes are incompatible. Tests
compile a producer JAR and persist its entities inside a local tenant graph without
regenerating dependency classes. This is partial A20 evidence. Persistent tenant module activation now verifies
startup/RLS/DML requirements through the runtime connection. Separate migration/runtime
connections are supported. Durable event routing and A19/A20/A21 remain OPEN.

## Separate module migration DataSources

Persistent modules now use application Flyway credentials for every isolated module
history and schema validation, while repositories retain runtime credentials. Boot
regressions verify restricted runtime access, successful restart, and rejection of
a different migration database before schema writes. Tenant module publication and
startup/RLS acceptance now have dedicated proofs. No SaaS release gate is closed by
these persistence increments.

## Persistent tenant module activation evidence

Generated application activation and real module JARs are verified with separate roles.
The coordinator preserves format-2 boundaries and version-1 fingerprints, rejects
unsafe/missing runtime RLS/DML privileges before component initialization, and keeps
module/application REQUIRED writes in one tenant transaction. Proofs cover restart,
parallel requests, rollback across all owners, additive external-VO upgrades/backfills
for both tenants, later migration failure and retry. This is partial A19/A20 evidence;
A19/A20/A21 remain OPEN pending the rest of the SaaS execution/identity/event lifecycle.

## Durable tenant event production evidence

The [routing contract](tenant-event-routing-contract.md) is now implemented for current
generated storage and the production worker. Format-3 payload graphs use non-null
scope-qualified relationships and exact-origin loading. Tenant operations validate the
restricted runtime role, exact event RLS policy and shared transaction-local binding.
The queue captures nullable tenantId (NULL is GLOBAL); claims/failures stay unscoped,
processing restores the claimed origin and ACK is origin/token/lease-fenced.

Seven PostgreSQL production-worker tests consume generated module classes/resources
from a real JAR through Boot and restricted Hikari pools. Proofs cover shared REQUIRED
writes and publication rollback, restart/per-recipient retry, concurrent tenants and
physical connections, stale-lease rollback/final-attempt recovery, forged tenant/GLOBAL
routing, weakened policy rejection and pool/context cleanup. Older adapters are rejected
before publication or BLOCKED before tenant payload loading. Recursive tenant payloads
are also verified through the generated storage fixture. Failure records contain safe
classifications instead of arbitrary messages or private values.

Pending GLOBAL jobs survive the reviewed queue migration; unmigrated queues fail before
claims/payload writes, and routing-metadata removal is blocked even with force. Existing
payload upgrades retain explicit GLOBAL backfills and prior source history. Global
module activation/worker regressions and example schema-checks continue to apply.
The earlier seven-case handwritten experiment remains separate feasibility evidence;
it is not counted as production acceptance by itself.

See [tenant event operations](../tutorials/tenant-events.md) for required artifact rebuilds,
role grants, migration review and stopping old worker versions before tenant traffic.
External effects are still at-least-once. Authentication, immutable actor/permission
policy, quotas, provisioning and the complete SaaS UI/Wizard/payment/mail lifecycle are
not supplied by routing. A19/A20/A21 and the final integrated release audit stay OPEN.

## Explicit ingress and extension gaps

Webhooks must resolve trusted resource ownership before tenant business access, without
requiring an already selected tenant or accepting callback claims as authorization.
Handwritten repository extensions need pre-query binding, canonical hydration/writes
and return guards, not just tenant predicates or post-hoc metadata. Current repository
syntax exposes only declarative find methods; the general extension SPI is not yet
implemented. These are required A22/A23 work, alongside A04/A09/A15/A18/A19/A20, and
must be reconciled in the final completeness review before release.


## Authorization entry feasibility evidence

The [test-only Spring authorization experiment](authorization-entry-spike.md) verifies
body-guard ordering, conditional nested checks, REQUIRED rollback, scoped MANDATORY
policy reads, anonymous rejection, all/any failure semantics and candidate HTTP/MVC
classification. Nine new proofs join the twenty-one existing experiment tests.
The initial experiment did not implement production permission syntax/runtime;
subsequent sections record the runtime and local DSL. Startup audits, identity,
module metadata or generated adapter integration. A03/A04/A19/A20 and all full SaaS
acceptance remain OPEN. Next implement the production actor/guard/Spring boundary,
then compiler/module/editor and adapter integration with generated end-to-end tests.


## Production actor/authorization runtime foundation

[Java/Spring guard APIs](../contracts/usecase-authorization-runtime.md) now provide
verified actor mapping, nominal permission/resource inputs, ordered pure requirement
evaluation, immutable nested invocation frames, transaction prerequisites and explicit
single-provider startup binding. HTTP/MVC authorization failures are terminal before
generic policies/redisplay. Existing authorization PostgreSQL probes now exercise the
production APIs rather than a parallel handwritten guard. This is partial evidence; local syntax/generated enforcement is recorded below.
It does not complete module/editor security support, identity or the
SaaS reachable-usecase audit. A03/A04/A19/A20 and full release acceptance remain OPEN.
Local compiler/generator and module metadata integration are now implemented. Next: editor/activation
audit and complete identity/membership/entitlement flows.


## Local permission/requires language evidence

The [0.6.0 local authorization contract](../contracts/usecase-authorization.md) now
defines permission declarations and generated usecase guards. Compiler diagnostics
reject unknown members/types, illegal or optional resources, invalid/duplicate groups
and domain misuse. Generated Spring/PostgreSQL proofs cover null/deny/validates/body
ordering, external parity, public/authenticated startup and identity behavior, tenant
resource isolation and conditional nested REQUIRED rollback. Basic local keyword/type
navigation and unsaved diagnostics are included. Module publication now retains these
contracts with explicit export closure and security-aware dependency snapshots.
Advanced completion, module activation audits, actual
identity/role/entitlement policies and complete SaaS E2E remain required. A03/A04/A19/A20
and full release acceptance stay OPEN.


## Module authorization metadata evidence

Module-contracts-11 retains exported permissions and ordered requirements, including
nominal resource bindings. Functional consumers snapshot public/internal access headers;
changed existing requirements require explicit review/rebuild. Additive keys remain
compatible. Type-only imports grant nothing and do not activate runtime components.
Read-only module views and imported permission vocabulary/navigation are covered.
The executable JAR/Spring/PostgreSQL proof packages a producer, generates a consumer's
activation and checks actual actor/tenant/REQUIRED boundaries; CI requires every case
without skips. The module tutorial is compiled through publication and activation.

This metadata upgrade does not rewrite persistence histories. Installed-IDE acceptance,
reachable-usecase audits and identity/membership/role/entitlement policies
remain required. A03/A04/A15/A19/A20/A21/A22/A23 and full release acceptance stay OPEN.


## Authorization editor evidence

Key and on-input completion works in nested requirements, with simple/qualified
permission names, incomplete references and unsaved declarations. Compiler-backed
visibility rejects ambiguous/unimported/wrong-kind keys; only required ID/VO/enum
inputs are resource candidates. Native references and LSP distinguish permission
types, members and resource declarations. Imported vocabulary/member navigation
works from read-only metadata without producer sources or Java generation.

Automated compiler/LSP tests cover these cases. Final packaged-plugin/manual
acceptance, reachable-usecase exposure audits, identity/membership/role policies and
the full SaaS end-to-end review remain OPEN. PR review/merge is not that acceptance.


## Explicit-access startup evidence

Applications can enable `authorization explicit;` independently of aggregate tenancy.
Generated inventory inspection rejects omitted clauses on registered local and activated
module usecases, including private/lazy normal definitions, before their construction.
It does not evaluate policy or authorize branches. Inactive type dependencies remain
inactive; an opaque custom factory product has a late materialization backstop.
Generated Spring execution and runtime/compiler/LSP tests cover these boundaries.

This completes a bounded assembly-audit slice, not an identity system or whole-program
security proof. Complete SaaS Wizard opt-in, identity/membership/role/entitlement setup,
web security integration and final installed-plugin/manual/end-to-end acceptance remain
OPEN. A03/A04/A15/A19/A20/A21/A22/A23 and full release acceptance remain OPEN.

A migration-history-only producer regression verifies that publication retains private
access requirements and vocabulary after the last storage component is removed. The
synchronous event registry fixture supplies only a clean startup-baseline connection;
actual persistence SQL is rejected there and remains covered by PostgreSQL suites.


## Identity/membership design checkpoint

The [proposed module contract](identity-membership-0.6.0.md) distinguishes login identity,
account, tenant, membership and role grants. It covers verification-before-provisioning,
Spring session integration, trusted selection with invocation-time rechecks, owner-safe
concurrency, invitation constraints, role/plan separation and safe outcomes. This is a
review proposal, not delivered registration or identity code. D01/D05/D14 and related
mail integration require decisions before implementation/publication. No extension
repository, license, artifact or new runtime API is introduced by this proposal.
All identity/whole-SaaS acceptance rows and the final manual review remain OPEN.


## First optional SaaS artifacts

D01 now selects the existing repository, Apache-2.0, separate org.vernac artifacts
and shared 0.6.0 version. saas/identity exports nominal IDs/status/lookup vocabulary;
saas/identity-spring-security supplies the explicit principal and live-status resolver.
The core/compiler do not depend on these modules. JAR consumption, guarded runtime
composition, concurrent principals and safe failure/serialization boundaries have
automated coverage. Release inventory and nested test-report discovery include both
artifacts while preserving pre-0.6 plugin recovery. No publication occurred.

This first boundary does not supply login or registration. The later optional JDBC
slice below adds a persistent directory; credential policy, verification/provisioning,
session infrastructure, memberships/roles, SaaS starter and UI remain unfinished. A01/A02, D14 and all complete identity/SaaS
acceptance remain OPEN. Final manual acceptance is not replaced by these tests.

D02 follow-up: a live identity becoming ineligible inside an active invocation currently
hits the guard's actor pin (AUTHORIZATION_CONFIGURATION_ERROR). A dedicated test proves
no nested body/context leak; final user-facing revocation/session handling remains part
of the unclosed identity integration, not evidence of a complete authentication UX.


## Optional persistent identity directory

saas/identity-jdbc adds the selected StoredIdentityDirectory provider, private global
identity state/repository and module-owned migration history. Actual packaged-module
consumption and PostgreSQL/Boot proofs cover status lookup, rollback participation,
restart, optimistic locking, restricted runtime credentials and sanitized resolver
failure/recovery. Release/CI inventories include the third optional SaaS artifact.

This closes only the persistent status-lookup foundation. D14 credential/login policy,
registration/verification/recovery, sessions and memberships/roles remain OPEN, as do
the complete SaaS acceptance and final manual gates. No account provisioning or
payment/mail workflow is delivered by this slice.


## D14 credential-policy proposal

A [concrete policy baseline](../contracts/saas-identity-credentials.md) is now available
for review before credential implementation. It specifies email-first registration
(password selected at verification), email/login-key rules, Spring Argon2id settings,
password/blocked-secret policy, purpose-bound challenges, distributed admission limits,
Spring JDBC sessions and security-epoch revocation semantics. It also records why
retryable verification mail requires a protected delivery envelope rather than putting
plaintext links into generic events. D10 protection/key ownership and D05 provisioning
remain separate required dependencies.

This is documentation/decision preparation only. No credential storage, public endpoint,
password encoder, delivery encryption or session integration is implemented by this PR.
Merging records acceptance of the D14 policy; all corresponding implementation proofs,
A01/A02, complete SaaS coverage and final manual acceptance remain OPEN.


D14 abuse-storage follow-up: the same policy PR now requires deployment-wide hard caps
for pending registrations, challenge/mail allocations, protected payload bytes, retained
terminal metadata and limiter-key cardinality. Atomic reservations, independent spent
attempt budgets, bounded cleanup with activation-race protection, safe capacity outcomes
and bounded local-inbox/logging behavior are explicit acceptance prerequisites. A rotating
source/address attack must not create unbounded business or auxiliary records. This remains
a contract change; no admission/cleanup implementation or attack-resilience proof is claimed.


## Credential primitives after D14 acceptance

PR #33 was accepted/merged, including bounded registration storage and cleanup policy.
D14 is now an accepted policy baseline; implementation acceptance remains separate. The
existing optional identity Spring Security artifact adds ASCII LoginAddress parsing,
NFC/scalar/password bounds, explicit integrity-checked offline corpus loading and the
real Spring Argon2id encoder with bounded stored-hash verification. BouncyCastle 1.86 is
explicitly pinned in that optional artifact; core and the pure identity library do not
depend on these additions. The [implemented contract](../contracts/saas-credential-primitives.md)
records exact Java boundaries and failures. CI/release inventory requires the new proofs.

No production corpus, public credential endpoint, registration/provisioning, challenge or
admission/cleanup persistence, protected delivery, rehash storage or session integration
is claimed. A01/A02 and all complete-SaaS/manual release gates remain OPEN.


## IntelliJ renderer compatibility regression

Restored the published 0.5.2 fix in the 0.6.0 line: the view renderer uses its public
isApplicable(Type) override without ClassRenderer.setIsApplicableChecker. A compiled
legacy fixture demonstrates rejection, and the actual packaged ZIP is scanned for
the known rejected symbol. A focused pull-request workflow and required release
suite prevent silent loss of this fix. The source change does not publish a plugin
or claim a complete Plugin Verifier result. Full candidate/IDE verification and
installed-IDE smoke acceptance remain required before release. SaaS work remains
at the credential-primitives checkpoint; no identity scope is marked complete here.


## Bundled common-password corpus

The optional identity security artifact now contains the pinned SecLists-derived
15–128-code-point corpus (10,908 entries), exact manifest/provenance, MIT license and
attribution. Explicit bundled() loading uses the existing strict loader with no network
or permissive fallback. A deterministic maintainer tool reproduces the pin; routine
builds/tests use tracked resources offline. LF attributes preserve resource checksums
on Windows. Real bundle/encoder tests and actual-JAR license/payload checks are required
by CI/release gates. This supersedes the earlier credential-primitives slice's missing
production-corpus note; no complete registration or identity release gate is closed.

The source is historical, finite common-password data, not complete/current HIBP breach
coverage or a guarantee for unlisted passwords. Next: private credential/challenge and
admission persistence, capacity/cleanup proofs, then mail/provisioning/session workflows.
A01/A02 and full-SaaS/final manual acceptance remain OPEN.


## Private persistent identity storage budgets

A private Vernac IdentityCapacity model/repository and generated second module migration
now back six fixed resource counters and their limits. The package-private JDBC helper
requires the bound writer transaction, serializes metadata mutations with a PostgreSQL
transaction lock, prechecks complete reservation/release bundles and uses normal generated
version/timestamp persistence. Explicit operational changes preserve occupancy when limits
are lowered; initialization never silently resets usage/configuration. A composition scope
lets later lifecycle operations take the budget lock before record locks.

Real PostgreSQL/Boot tests cover two-context last-slot races, shared identity-write rollback,
restart, original-schema upgrade, transaction/source checks, limit/overflow handling, stale
versions and sanitized storage failure/recovery. Existing identity tests share extracted
fixture code; private-type import checks reject the imports themselves.

This is storage-budget infrastructure, not completed credentials/challenges, public admission,
per-record release idempotency, cleanup or mail/session flows. Capacity alone cannot prove
which record was deleted: guarded transitions must be coupled in the same transaction in
the next slices. Independent abuse attempts must not be refunded by business rollback.
A01/A02, full anti-DoS registration coverage and whole-SaaS/manual acceptance remain OPEN.


## Private one-time identity challenge persistence

The [challenge lifecycle](../contracts/saas-identity-challenges.md) adds private Vernac
records and a third generated module migration. Secure random canonical tokens store
only SHA-256 verifiers. Purpose/status/expiry checks, resend replacement and one-winner
consumption share the bound writer transaction and capacity lock. Actual deletion
releases one charged slot; caught callback failures poison the transaction. Explicit
bounded expiration cleanup is available, with no automatic job or public endpoint.
Credentials/admission, complete cleanup, protected mail, provisioning and sessions/UI
remain next. A01/A02 and complete SaaS/final manual acceptance remain OPEN.

Validation for this slice: 48 identity/SaaS cases pass without failures, errors or skips,
including real PostgreSQL restricted-role consumers and two independent Boot contexts.
The full migration replay matches the expected owned schema. All 16 release-tool tests
and documentation-link checks pass. These are local proofs; GitHub checks and the final
installed-IDE/full-SaaS release gates are separate. Next: private credential persistence
and admission/complete cleanup, then protected delivery and provisioning/session flows.

## Private global identity admission

The [global ingress foundation](../contracts/saas-identity-ingress.md) now implements
the D14 deployment-wide fixed-window cap (initial default 300 combined public identity
requests per UTC minute). A singleton private Vernac aggregate and fourth generated
module migration share atomic admission across instances. Explicit initialization
charges one limiter-key slot exactly once; restart/configuration checks preserve usage.
The helper owns a committed Spring transaction before lookup/hashing/business work;
later business rollback cannot refund admission. Ambient business transactions are
rejected to avoid accidental coupling or self-deadlock. No public filter or keyed
admission is installed, and no new cryptography/DSL/framework is introduced.

A01/A02 and complete abuse/public-flow/full-SaaS/manual acceptance remain OPEN. Remaining
admission work includes purpose-separated keyed source/identifier/locator gates, key
ownership/retention, trusted source resolution, bounded password work and ingress wiring.
The existing challenge implementation is retained.

### Credential-storage prerequisite found during this slice

The current generated ordinary value/aggregate toString implementations render modeled
fields. Persisted password encodings must not be placed in those representations under
D14. A private module alone is not redaction, and a reversible numeric/base64 wrapper is
not a remedy. Directly storing a hash in a generated String value would violate the
accepted policy. Before delivering credential persistence, establish a reviewed safe
representation/storage boundary with tests that seed and scan hashes; do not silently
weaken D14 or hand-edit generated Java. No new language syntax or competing crypto is
selected by this finding. The independently implementable agreed coarse admission gate
was delivered first, without expanding credential behavior or rebuilding challenges.

Local validation: 53 identity/SaaS cases across 15 required suites pass without failures,
errors or skips under JDK 25. Tests include failed bootstrap reservation rollback and
corrupt stored-state failure. Four-migration PostgreSQL replay matches the expected
owned schema. All 16 Python tooling tests and documentation navigation/link checks pass.
GitHub CI and final installed-IDE/whole-SaaS acceptance are separate from these local proofs.


## Explicit value representations and private verifier redaction

The existing enum capability to supply public String toString() behavior now also
works for ordinary values. It needs no new keyword, field annotation, crypto or
serializer framework. The compiler emits one custom delegate instead of its default
field renderer, enforces public/non-optional String/zero arguments, preserves other
generated APIs and rejects getter collisions/duplicate methods. Editor APIs share
the production generator; LSP diagnostics follow unsaved edits.

IdentityChallengeVerifier uses a constant redacted label, including when nested in a
generated challenge representation. Getters/comparison and all four existing schema
migrations remain unchanged. This resolves the representation prerequisite identified
in the preceding ingress slice; credential persistence itself is still next. Redaction
is only a display rule and does not hide fields from getters, serializers or debugging.
No public credentials, registration/recovery or session flow is claimed. A01/A02 and
whole-SaaS/final manual gates remain OPEN.

Local evidence under JDK 25: 530 compiler tests, all 86 LSP tests, all 54 identity/SaaS
cases including restricted-role PostgreSQL consumers, and 33 targeted Maven module,
documentation-example and real JVM/JDI behavior/validation proofs pass without
failures/errors/skips. All 16 Python tooling tests and documentation link checks pass.
The complete IntelliJ build/installed-IDE acceptance and full release reactor remain
separate gates; no release publication is performed by this slice.


## Private credential persistence and security epoch

The [private credential lifecycle](../contracts/saas-identity-credentials-jdbc.md) now
persists bounded approved Spring password encodings in redacted private Vernac values.
Existing security-library format validation is reused; no crypto or password matcher
is implemented in JDBC. The optional JDBC artifact now has the existing optional security
artifact as a runtime dependency. Public module exports/automatic configuration stay unchanged.

Guarded pending enrollment, active snapshot/confirmation, version-checked replacement and
rehash share the canonical writer/budget-before-record lock order. Credential changes
advance an identity epoch; rehashes preserve it. Disable/first closure also advance epoch,
and exhaustion fails without wrapping. Snapshot versions protect the verification handoff;
future browser revocation must use epoch rather than logging out users on every rehash.
Caught writer failures poison the transaction, including failure after an epoch write.

The fifth generated migration adds the credential table and backfills existing identity
metadata to initial epoch 0 using the tracked migration-specific transformation. This
is the starting counter generation, not a fabricated history or an authentication grant.
Existing IDs/statuses and all four earlier migrations are preserved.

This slice is not complete authentication or epoch-checked sessions. The existing
IdentityDirectory/IdentityActorResolver still supplies its earlier status foundation;
trusted authentication stamps and D02 checks remain required. Login-key reservation,
keyed admission/complete cleanup, protected D10 delivery, D05 provisioning, public
registration/recovery/login and the example/UI remain OPEN. No A01/A02/full-SaaS/final
manual acceptance is closed.

Local validation under JDK 25: all 63 identity/SaaS cases across 19 required suites pass
without failures/errors/skips. These include two-context enrollment/change/disable races,
Spring matching, caught credential-write/provisioning rollback, corrupt formats, epoch
exhaustion, original-schema epoch-0 upgrade and module privacy. All five migrations replay
to the expected owned schema. All 16 Python tooling tests and documentation links pass.
The prerequisite compiler/plugin/runtime artifacts were rebuilt for the new local cache;
full compiler/IDE/release-reactor acceptance was not rerun for this optional SaaS slice.


## Bounded local credential work

The [credential work owner](../contracts/saas-credential-work.md) adds the local
resource portion of D14: initial 4 real workers/16 queued jobs/250 ms start admission
wait. It uses the existing Spring password operations and a fixed bounded JDK executor,
not new crypto or a general-purpose task framework. It implements Spring PasswordEncoder
directly, with no automatic rehash policy. Expired/cancelled queued work is
removed, real running work retains capacity after caller interruption, and shutdown
cancels queued work without promising forced crypto termination. Request contexts are
not inherited; transaction/resource scopes and nested submissions are rejected.

The optional security artifact now has spring-tx at runtime for that guard. No schemas,
public endpoints, automatic beans/filters or default application security chains are
installed. The raw primitive is still unbounded; the standard assembly must explicitly
share one owner across all password/dummy routes and wire independent admission first.
A real PostgreSQL consumer verifies snapshot/matching/confirmation boundaries and
rejects work inside a writer. No complete A01/A02/public abuse/browser flow is claimed.

### Rough scope orientation after this slice (2026-11-13)

These are broad engineering estimates for orientation, not accepted release gates,
measured velocity or a schedule promise. Test/PR counts are not completion percentages.

| Scope | Rough implementation progress | What remains next |
| --- | --- | --- |
| Identity/authentication | About 40–50% | Source/address/locator admission and bounded keys, login-key ownership/pending lifecycle, protected delivery/provisioning, Spring browser flows |
| Complete 0.6.0 scope | About 25–35% | Accounts/roles, dynamic accounting/quotas, subscriptions/payments/invoicing/mail, complete UI/sample/Wizard and integrated release acceptance |

Tenant/module/authorization foundations account for much of the implemented work.
Identity library internals are advanced, while its public end-to-end user journeys
are still missing. Next PR target: keyed admission for source/login-key/locator inputs
with bounded persistent allocation and retention, preserving independent attempt commits.

Local JDK 25 evidence: all 73 identity/SaaS cases across 20 required suites pass with
zero failures/errors/skips. Nine bounded-work cases cover limits, deadlines, real slot
retention/cancellation, safe failures, inherited context, transaction/nested guards and
actual Spring PasswordEncoder compatibility. The real PostgreSQL handoff/guard case
passes alongside existing lifecycle/upgrade proofs. All 16 Python tooling tests and
1,122 local documentation links pass; XML/YAML and diff checks are clean. All five
migration histories remain unchanged. Full core/IDE/release-reactor acceptance was not
rerun for this optional-library slice; GitHub CI remains separate from local evidence.


## Keyed identity admission and bounded cursor cleanup

The [keyed admission foundation](../contracts/saas-identity-admission.md) now implements
the initial shared source/login-key/locator windows, composing the existing independently
committed coarse gate. JCA HMAC-SHA-256 provides purpose-separated pseudonyms with explicit
stable installation key input; there is no custom crypto or implicit key generation.
Configuration/key drift fails closed rather than resetting a new counter namespace.
A technical UUID index locator is always checked against its full digest and kind.

The sixth generated migration adds private configuration/counter models. Two fixed key
slots (coarse/configuration) and actual dynamic rows share the persistent capacity ledger.
Two potential dynamic slots are reserved before key-existence branches, unused reservations
are released atomically, and active keys are never evicted to admit new traffic. Source
allowance is spent even on subject denial. Later business rollback cannot refund counters.

Primary-key seek cleanup persists a shared cursor, scans at most 500 rows per batch and
releases only successful expired-row deletes. Admission and cleanup share monotonic observed
time and lock order; concurrent renewal survives cleanup, and cleanup never double-releases.
Full periodic sweep scheduling/throughput, trusted-source/HTTP wiring and complete lifecycle
cleanup remain mandatory; no A01/A02/full-SaaS/final manual gate is closed.

Rough implementation orientation after this slice: Identity/authentication about 45–55%,
complete 0.6.0 about 25–35%. These are mental scope estimates, not release acceptance or
a schedule. Next: login-key/pending registration ownership, then protected delivery, D05
provisioning and the real Spring/browser flows. Accounts/roles, accounting/quotas, commercial
SaaS modules and the complete UI/sample/Wizard still make up much of the remaining release.

Local JDK 25 evidence: all 83 identity/SaaS cases across 23 required suites pass without
failures/errors/skips. They include standard JCA key behavior, overflow/high-water rules,
restricted-role PostgreSQL policy/capacity/privacy tests and two-context initialization,
last-attempt, renewal/cleanup and double-cleanup races. All six migrations replay to the
expected owned schema; the earlier five migrations remain unchanged. All 16 Python tooling
tests and 1,136 documentation links pass; workflow XML/YAML/diff checks are clean. Full
core/installed-IDE/release-reactor acceptance was not rerun for this optional SaaS slice;
GitHub CI and final integrated release acceptance remain separate from these local proofs.
