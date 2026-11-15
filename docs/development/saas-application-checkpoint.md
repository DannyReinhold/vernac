# SaaS application continuation checkpoint

Status: **INCOMPLETE — not a release-readiness attestation**.

The repository-extension prerequisite is implemented in #86 at
`23e4288cef26a5b32aa361d814789df366db0971`. Its dedicated PostgreSQL workflow
[43771077932](https://github.com/DannyReinhold/vernac/actions/runs/43771077932),
broad tenant workflow 43771077961 and debugger workflow 43771077966 passed.
Dedicated checks include six runtime, four compiler and eleven generated
TenantGeneratedPostgresTest cases with no skips.

The application topic includes this prerequisite through merge commit
`63d66d822b1f2d38b3de5fd7897919e4410e47b5`.
The release branch has not been merged or published.

## Interruption

The execution environment became unavailable during application work on
2026-11-15. No local files could be read afterwards. The application implementation
below was **uncommitted local work**, not part of this branch's published tree.
Do not assume it was retained or claim its tests cover a reproducible Git commit.
Recover the existing worktree if possible before reimplementing it.

Last local root:
`/workspace/scratch/7b7c6aafc9e5/vernac`, branch feature/saas-application.

## Begun locally

- IdentityBrowserSecurity.Extensions explicitly composes authenticated global
  billing metadata routes and additional bounded CSRF-protected form paths.
- IdentityAccountSelectionFilter.requireAccount exposes only the current
  request's freshly verified AccountId/TenantId mapping; clears it after the
  request. Never derive an AccountId from an untrusted tenant claim.
- SubscriptionAccounts decorates explicit verified account selection with
  idempotent initial Free provisioning, closing global/product scopes correctly.
- Opt-in saas/billing-spring-mvc: BillingSettings, BillingAccess, BillingSelfService,
  BillingWebSecurity, BillingMvcConfiguration, BillingController and escaped
  script-free BillingPages. Protected query-free invoice PDF/XML endpoints
  resolve fresh ownership through the existing InvoiceLedger overloads.
- GET billing reads do not provision subscriptions: explicit protected POST
  starts Free when no subscription exists.
- Standalone examples/saas-document-workspace, copied from the independent
  identity-auth assembly. Own tenant Document aggregate, nominal summary values,
  generated usecases, ordinary DocumentOperations port and StoredDocuments adapter.
  Port/usecase boundaries use IDs/value objects, never aggregates.
- Product DocumentActions uses the new generated extension source with normal
  canonical hydration and scoped count. Capacity uses actual repository counts;
  operation length uses facts; monthly exports use local quota in the same physical
  transaction. Roles authorize operations separately from purchased allowances.
- Local default catalog: Free 3 documents / 1000 characters / 2 monthly exports;
  Pro EUR 12 monthly, 100 documents / 10000 characters / 100 monthly exports.
- Explicit sample wiring of existing ledgers, shared MailProvider, selectable
  PaymentProvider, PDF renderer and bounded lifecycle worker. Local simulation
  requires both vernac.payments.local.allowed and billing.allow-simulation.
- Product migration generated with the normal compiler:
  V20261116010000000__initial_documents, including forced tenant RLS.
- Begun dedicated saas-application.yml and actual HTTPS/Playwright acceptance
  using a restricted runtime role and separate migration credentials.

Local billing reactor and standalone sample packaging succeeded, and two page
escaping/explicit-start tests passed before interruption. These observations
have no published source SHA and are **not release evidence**.
The final attempted command added restart acceptance and reran Identity tests;
its execution was not observed. Do not assume it ran.

## Required continuation

1. Recover and inspect uncommitted work; preserve prior changes. Compile and run
   Identity/security tests again. Read this checkpoint before assuming application
   code is already present remotely.
2. Finish and execute the actual restricted PostgreSQL + HTTPS browser assembly:
   Free limits, concurrency, paid activation, repeated request identity, foreign
   account/order/invoice denial, immutable PDF/XML and restart continuity.
   No browser journey ran before interruption.
3. Fix lifecycle/cancellation coordination before claiming J05. The initial
   BillingSelfService guard scanned only the first 100 orders for PAID; this is
   not a sufficient global/race-safe financial fence. Use explicit durable backend
   policy, not a UI-only history scan. Keep uncertain charges reviewable.
4. Complete merchant-authorized refunds and safe recovery/operator paths.
   Do not expose unrestricted customer refunds or invent a refund policy.
5. Complete public provider webhook ingress with bounded admission and trusted
   routing. The initial example denies webhook admission and only polls registered
   resources; its placeholder callback URL is not a finished provider integration.
6. Finish a simple persistent local launcher with generated HTTPS/secrets and
   capability-protected local mail preview. Do not expose tokens through a public
   inbox or silently simulate real providers.
7. Improve human labels, lifecycle messages, mail status and operational diagnostics.
   Frozen customer numbers should be application-owned, not arbitrary editable
   customer claims.
8. Add explicit SaaS IntelliJ wizard/template adoption reusing this same consumer
   source; keep existing basic/Spring Boot defaults and non-SaaS examples intact.
   Generate namespace-specific schema fingerprints/migrations with the compiler.
9. Publish exactly one stacked application PR against
   feature/saas-repository-extensions once real source is secured. Then one
   separate release-audit/gate PR. Do not create empty completion placeholders.

The final audit must map A01–A23 and J01–J09 to source/dependency/artifact hashes
and actual unskipped evidence; unknown/stale/missing evidence must block GO.
Real Mollie/Stripe sandbox attestations require actual credentials/evidence and
must never be replaced with simulated success. No automatic merge or release.
