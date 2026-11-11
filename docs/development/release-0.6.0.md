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

Extend J01/J03/J04/J06 with the predefined test-provider scenarios and verify the
corresponding actionable user/developer outcomes. Capture correlated operational
events, assert no sensitive values appear in logs and prove production cannot silently
substitute a simulated success. J08 must work without real provider API keys.

All journeys are currently **OPEN / no evidence**.

## Dedicated pre-release completeness audit

Schedule a separate review PR after integration, before calling the release ready.
The review may use the same contributor but must be a new, explicit assessment of
the final implementation, not merely a repetition of prior progress summaries.

- [ ] Reconcile every plan/release promise, D01–D12 decision and A01–A18 row against
      actual source, tests, public APIs, generated output and consuming application.
- [ ] Inspect stubs, TODO/FIXME/UnsupportedOperationException paths, empty adapters,
      commented/disabled/skipped tests and happy-path-only simulators. Classify relevant
      findings; zero TODOs globally is not required and a text scan is not sufficient.
- [ ] Inspect every required module and selected provider, including work in other repos.
      Resolve incompatible defaults, licensing/distribution or migration gaps.
- [ ] Execute J01–J08 and required module/compiler/runtime/PostgreSQL/tooling suites;
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

GO requires all agreed requirements VERIFIED, J01–J08 evidenced, D01–D12 resolved,
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
