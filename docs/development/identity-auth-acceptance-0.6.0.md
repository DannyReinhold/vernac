---
title: Identity/Auth final acceptance for 0.6.0
---
# Identity/Auth acceptance

Review date: 15 November 2026. Scope: the accepted D05 Option 1 plus the existing
D14 identity credential/browser baseline. Implementation and automated technical
acceptance are complete in the cumulative review branches. Maintainer review and
ordinary merging remain pending; no release, merge or deployment was performed.

The whole 0.6.0 SaaS release is **NOT READY**. This record closes the identity/account
implementation complex, not billing, quotas, invoices, payment/webhook routing or
the full SaaS Wizard/installed-plugin release. No real external email was sent.

## Immutable implementation and execution evidence

The exact PR heads below passed their required GitHub checks. The independent real
browser job was added in #78; earlier filter tests identify their servlet-fixture
boundary. Runtime, identity/account and sample sources in the final acceptance PR
are the same as #79 below; #80 additionally reconciles documentation and release gates.

| PR | Exact source commit | Actual required run |
| --- | --- | --- |
| [#69](https://github.com/DannyReinhold/vernac/pull/69) | `c5f36ece4a28fccb2b94b9b06187e3b2405a89b9` | [backend](https://github.com/DannyReinhold/vernac/actions/runs/43720036008/job/132829814395) |
| [#70](https://github.com/DannyReinhold/vernac/pull/70) | `3364f6b35a392275a915f0b2c9230d2fc58d5897` | [backend](https://github.com/DannyReinhold/vernac/actions/runs/43720986881/job/132832708214) |
| [#71](https://github.com/DannyReinhold/vernac/pull/71) | `96fd58dc8a4684de5d9c4b67509c3d35f7c3c874` | [backend](https://github.com/DannyReinhold/vernac/actions/runs/43721611723/job/132834614474) |
| [#72](https://github.com/DannyReinhold/vernac/pull/72) | `0fdcc7175892397590336e52f786151a59fb984a` | [backend](https://github.com/DannyReinhold/vernac/actions/runs/43722333619/job/132836805188) |
| [#73](https://github.com/DannyReinhold/vernac/pull/73) | `9d0730fd53b48ba15221b412b9c0cda8b4ed75b6` | [backend](https://github.com/DannyReinhold/vernac/actions/runs/43723248023/job/132839564212) |
| [#74](https://github.com/DannyReinhold/vernac/pull/74) | `71e8f85b7f7a504e63bc5be31f4b21b58691626d` | [backend](https://github.com/DannyReinhold/vernac/actions/runs/43723872376/job/132841444951) |
| [#75](https://github.com/DannyReinhold/vernac/pull/75) | `3dab8f908c1f58fa8c7fa561e6a0aa777665e4ea` | [backend](https://github.com/DannyReinhold/vernac/actions/runs/43727781177/job/132853376016) |
| [#76](https://github.com/DannyReinhold/vernac/pull/76) | `4e2ac6106dc7145d7b630ec55cdefe0cac2abd23` | [backend](https://github.com/DannyReinhold/vernac/actions/runs/43727805524/job/132853453549) |
| [#77](https://github.com/DannyReinhold/vernac/pull/77) | `3d93a14f54ba60d9c62e10bea41bf4bed99ebdad` | [backend](https://github.com/DannyReinhold/vernac/actions/runs/43728579994/job/132855789618) |
| [#78](https://github.com/DannyReinhold/vernac/pull/78) | `327453795252765c65b16c0e042f733c42232d47` | [backend](https://github.com/DannyReinhold/vernac/actions/runs/43730561233/job/132861833759), [Chromium](https://github.com/DannyReinhold/vernac/actions/runs/43730561233/job/132861834027) |
| [#79](https://github.com/DannyReinhold/vernac/pull/79) | `fdd230abdb6c7a418e6681ed39c5f6eef13a6be0` | [backend](https://github.com/DannyReinhold/vernac/actions/runs/43730584682/job/132861904507), [Chromium](https://github.com/DannyReinhold/vernac/actions/runs/43730584682/job/132861904266) |

The final gate/test source before documentation reconciliation is
`e5a4ee9d316d336605837a674b1c03eac2710757` in [#80](https://github.com/DannyReinhold/vernac/pull/80).
Its [required backend/gate job](https://github.com/DannyReinhold/vernac/actions/runs/43730723090/job/132862314247)
and [independent HTTPS/Chromium job](https://github.com/DannyReinhold/vernac/actions/runs/43730723090/job/132862314036)
also passed. Exact-head results on the PR remain authoritative after later changes.
Java 25 runs the coordinated production/compiler/test build, with the independent
crypto baseline checked on Java 21 and 25. PostgreSQL 17.11 uses distinct migration
and restricted NOSUPERUSER/NOBYPASSRLS runtime principals. Chromium runs the real
packaged Boot HTTPS application and explicit simulated shared mail provider.

## Accepted policy coverage

| Requirement | Executable evidence |
| --- | --- |
| Strict app-owned JSON roles, unknown/duplicate/over-limit rejection, declared vocabulary and canonical assignments | RoleCatalogTest, RoleAssignmentsTest, IdentityModuleContractIT |
| Generated global metadata references, immutable nominal mapping, uniqueness/FKs and replay/drift | GlobalIdReferenceTest, GlobalIdReferencePostgresTest, AccountMetadataPostgresIT |
| Exact role union, current membership/identity, object policy and no owner/Spring-authority bypass | AccountPolicyPostgresIT; actual generated ViewWorkspace invocation in Chromium |
| Serialized last active owner, including competing contexts and identity-wide closure | AccountPolicyPostgresIT; protected HTTP and actual Chromium last-owner denial |
| Atomic verification, credential/activation/personal provisioning, rollback/retry/restart/concurrent one-time acceptance, no auto-login | IdentityJourneysPostgresIT, IdentityBrowserPostgresIT |
| Verified intended invitation recipient, one-time purpose, expiry/revoke/current issuer, no owner promotion or silent existing-member escalation | AccountInvitationsPostgresIT, IdentityInvitationChainTest, IdentityBrowserPostgresIT, Chromium |
| Standard credential authentication and epoch recheck, bounded work, anti-abuse/capacity and protected delivery | IdentityAuthenticationPostgresIT, IdentityJdbcAuthenticationAssemblyPostgresIT; credential/admission/capacity/encryption/mail worker suites |
| Secure CSRF/fixation/logout, absolute/inactivity expiry, current epochs and two-instance/restarted shared sessions | IdentityBrowserSecurityTest, IdentityJdbcSessionsPostgresIT, IdentitySessionEpochPostgresIT, IdentityBrowserPostgresIT |
| Fresh AccountId-to-TenantId selection, no submitted tenant authority, scope cleanup/no fallback, owner-scoped escaped UI | IdentityAccountChainTest, AccountPolicyPostgresIT, IdentityBrowserPostgresIT |
| Real fragment execution/history scrubbing, empty browser storage, explicit POST, real cookies/registration/invitation/password paths | examples/identity-auth/browser/verify.mjs via the independent required identity-browser job |
| Role removal/restoration affects the actual generated guard; revoked member cannot select product scope; last-owner failure retains identity session | Chromium uses the actual shared metadata policy and generated workspace usecase |
| Operator-only aggregate backlog/liveness/fixed stage reasons, unavailable storage instead of fake zeros, worker failure/recovery | IdentityOperationsPostgresIT; explicit IdentityJourneys.operations() SPI |
| Credential-free controlled local inbox, ordinary provider selection, no production fallback, no seeded credential/token/address/session/key values in app logs | MailProviderSelectionTest, LocalMailInboxTest, BrevoMailTransportTest; protected local preview and exact log scan in the HTTPS launcher |
| Release inventory includes actual identity/mail artifacts and rejects missing/skipped final suites | tools/tests/test_release.py and the required backend/gate job |

CI requires the new integrated browser and monitoring suites to exist and execute
without failures, errors or skips. A compile-only pass, a successful local provider
submission, a running scheduler or a merged PR alone is not acceptance.

## Explicit final review and corrections

The final pass inspected the actual global writer, current stamped session/actor checks,
catalog/assignment boundaries, owner locks, invitation eligibility/expiry, atomic
verification callback, browser filter order and explicit module/provider/startup wiring.
It found and corrected a stale two-purpose mail-link test, a password test that executed
its side-effecting readiness probe twice, a borrowed-data-source destruction error in
the web-restart fixture, and sample Spring bean/infrastructure omissions. The HTTP
adapters remain separate from product permission formulas; the generated guard runs in
the sample. Safe authorization outcomes are mapped for its handwritten controller.

One CI dependency resolution failed with Maven Central HTTP 429. That network failure
is not treated as a source regression or a passing proof; final evidence uses the later
exact source runs. Deterministic assertion/startup failures were corrected rather than
accepted after retries.

The initial local environment ran compilation and targeted unit/PostgreSQL proofs.
After it became unavailable, all later changes were pushed and verified through GitHub
CI. There are no uncommitted local deliverables required to reproduce this series.

## Deliberate limits and remaining release work

- The sample's workspace is a real generated permission-guarded read-only operation,
  not a complete tenant product, free-plan assignment or billing/invoice application.
  Existing generated restricted-role tenant persistence proofs remain separate.
- These browser adapters cover synchronous servlet dispatch. Async/deferred work needs
  the existing explicit trusted context protocol. Revocation affects subsequent checks,
  not external effects or already running transactions.
- Global access metadata is a controlled trusted Java/SQL boundary, not a sandbox around
  arbitrary application code or a platform-wide unrestricted user directory.
- The local preview needs the explicit local profile, loopback HTTPS and a separate
  random capability header. It is bounded in memory and absent in the production profile.
  No shared public test inbox, token query parameters or secret log links were added.
- The sample intentionally accepts one active delivery key for disposable acceptance.
  Production multi-key rotation uses the existing explicit keyring API and reviewed
  persisted encryption/admission policy, not an automatic key/counter reset.
- Operational sampling exposes only fixed aggregate counts/stages, no public endpoint.
  The application must authorize its operator surface and choose polling/alerts. The
  [operations contract](../contracts/saas-identity-operations.md) documents restart,
  provider uncertainty, migration, key/catalog changes and shared-session recovery.
- Actual Brevo sending/sandbox credentials, production proxy/TLS deployment and a
  maintainer production migration review were not performed.
- Full release A03–A23/J01–J09, financial/product entitlements, complete SaaS UI/Wizard,
  installed IDE, publication/Marketplace and maintainer GO remain separate gates.

Review and merge [the complete sequence](identity-auth-review-series-0.6.0.md) in
numerical order using ordinary merge commits. Re-run normal merged-branch checks
before eventual release preparation; this record authorizes no automatic merge.
