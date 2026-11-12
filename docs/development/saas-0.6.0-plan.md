---
title: SaaS foundation — 0.6.0 delivery plan
---
# SaaS foundation — 0.6.0 delivery plan

Status: **approved product objective; foundation slices implemented, complete SaaS delivery OPEN**.
Planning baseline: 2026-11-11, `feature/0.6.0`.

This document records the agreed outcome, design direction and unresolved decisions.
It does not introduce supported syntax or claim that the foundation exists today.
The [release acceptance ledger](release-0.6.0.md) is the completion authority for this
work. Existing language contracts remain authoritative until amended by reviewed PRs.

## Outcome and completeness

A developer can create a Vernac application, configure and deploy it, and obtain
working self-registration, account management, automatic tenant isolation, authorization, subscriptions,
payments, customer invoicing and transactional system email. The developer should
implement their product, not reconstruct these common lifecycles from examples.
Reusable UI and application flows are part of the outcome, alongside domain modules.

0.6.0 is the coordinated acceptance milestone for Vernac and any required extension
modules. Their repository, license, Maven coordinates and individual versions remain
open. A core release without the required, consumable extension artifacts does not
meet this objective. Small PRs are an implementation sequence, not reduced scope.

The sample is a small document workspace: customers register, create personal or
organization accounts, invite members, create documents and export them subject to
rights and limits. It offers Free and paid plans, checkout, invoices and account
management. Product documents and the SaaS provider's customer invoices are separate
models. Domain names and exact feature choices can change during contract review.

## Scope addition: automatic tenant isolation

Tenant isolation is a mandatory 0.6.0 foundation for both personal and organizational
SaaS use. A small aggregate classification must drive generated repository reads,
writes, graph loading, queries/pagination and schema protection under a trusted tenant
context. Application authors must not repeat account/organization predicates.
[The tenancy design slice](tenant-isolation-0.6.0.md) records the required guarantees
and open D13 decisions. It supersedes using resource-policy account comparisons as
the standard isolation mechanism; organization/account identity is not tenant identity.

## Required ingress and custom persistence safety

[The webhook/repository follow-up](tenant-webhooks-and-repository-extensions.md) is part
of 0.6.0, not optional post-release polish. Provider callbacks require verified technical
correlation before tenant business access; arbitrary callback tenant claims do not
establish ownership. Handwritten repository methods require a supported scoped facade,
canonical hydration/writes, metadata/provenance result guards and executable documentation.
The implementation must cover negative/restart/concurrency cases under restricted roles.
A22/A23 in the release ledger track completion; these APIs are not implemented yet.

## Architecture and ownership

| Responsibility | Proposed owner; final allocation remains open |
| --- | --- |
| Typed permissions, requirements, generated access APIs and diagnostics | General Vernac language/tooling/runtime |
| Actor/scope abstraction and authorization-provider contract | General integration boundary |
| Spring Security, HTTP/MVC and transaction integration | Suitable starters/adapters |
| Identity, memberships, roles, plans, subscriptions, billing and email workflows | Well-integrated reusable functional modules |
| Payment/mail provider implementations and local simulators | Replaceable adapter modules |
| Product-specific rights, policies, limits, plans and behavior | Consuming application |

Do not decide licensing, create repositories or add provider-specific compiler keywords
implicitly. Use the existing [module contract](../contracts/modules.md): explicit
exports/activation, provider selection, compatibility metadata and migration ownership.
A module is not automatically a bounded context. Do not introduce a universal User
aggregate shared by identity, billing and every product model.

## Agreed authorization direction

- Requirements belong to usecases; nested calls check their own requirements only
  when reached. There is no automatic preflight traversal of the possible call graph.
- An inner invocation keeps the actor and verified execution scope; calling it from
  another usecase does not grant rights. Any scope change/delegation must be explicit.
- Named rights support explicit `all` / `any` composition. Rights are not role names.
- Authentication, membership/role authority and purchased feature entitlement are
  distinct inputs. Payment does not make every member an administrator.
- A caller-supplied account/resource ID requests scope; it is not evidence of access.
  Check object ownership and prevent cross-account disclosure, including query counts.
- The actor can be a person or an expressly configured technical identity. Deferred
  work does not inherit credentials or gain unrestricted access automatically.
- Authorization checks do not implicitly consume a quota. Technical policy failures
  must be distinct from a legitimate denial and must never grant access.
- Public registration/login/recovery flows need explicit treatment. The implemented
  requires syntax preserves omission for ordinary applications; the SaaS assembly must
  enable the explicit-access startup check. Identity workflows are not supplied yet.

The [implemented usecase authorization contract](../contracts/usecase-authorization.md)
and [explicit-access audit](../contracts/explicit-authorization.md) define current syntax.
Role catalogs, identity workflows and restriction sketches below remain proposals; do
not advertise them in executable language/AI examples as implemented features.

## Dynamic restrictions: two independent dimensions

| Meaning | Example | Required behavior |
| --- | --- | --- |
| Access requirement | Export allowed for this member and account | Scoped decision |
| Per-operation limit | Maximum export batch size | Check actual operation data |
| Current capacity | Maximum active contracts or stored documents | Atomic allocation/release with lifecycle |
| Consumption quota | Exports or API attempts in a period | Atomic accounting with explicit period and outcome |

For **every** kind of restriction, facts may come from parameters, loaded data or
later execution. Do not design dynamic access only for quotas. A limit's current
usage often requires repository data. Define named typed fact providers or explicit
operations; do not infer arbitrary SQL or count semantics from a model name.

Counting then inserting independently is unsafe. Two last-slot requests must not
both succeed. Allocation, reconciliation and release on delete/deactivation need a
specified concurrency guarantee, including the effects of all supported write paths.

Preferred API sketches, **not implemented Java/Vernac APIs**:

```java
self.quota(ExportedInvoices).consume(invoices.size());
var result = self.quota(ProviderRequests)
        .consume(1, () -> self.provider().lookup(query));
```

Provide an explicit reservation API as well. Decide reserve/confirm naming, return
and void overloads, exception behavior, durable state, expiry/recovery, amount
adjustments and idempotency before generation. The functional helper must not
silently retry its callback. Rights, limits and capacity need similarly usable APIs;
the quota spelling does not establish those APIs yet.

Local transactional consumption can roll back with local business writes. External
activity may have occurred despite a later rollback. Distinguish attempt-based,
success-based and provider-confirmed accounting; timeouts may leave an unknown
outcome. Never promise exactly-once external execution. A reservation expiry is not
proof that external work did not happen. Determine periods, clock/time zone,
unlimited/zero states, account sharing, downgrade behavior and duplicate-request rules.

Nested usecases declaring the same quota must not accidentally double-charge. Either
both represent separate documented consumption or one operation owns accounting;
there is no implicit deduplication based only on the quota name. `any` initially
combines side-effect-free checks, not alternative budget-consuming branches.

## Spring Boot implementation constraints

Spring Security supplies authentication/context and integration machinery; no custom
cryptography or competing session/password framework. Verify implementation against
the versions actually supported by Vernac, not only current upstream examples.

| Boundary | Responsibility |
| --- | --- |
| Inbound HTTP/MVC | Authentication, explicit public/private access, transport protection |
| Usecase | Business requirements, resource scope and explicit restriction enforcement |
| Domain model | Invariants independent of transport or login mechanism |

Do not duplicate permission formulas in endpoints. Endpoint access can impose an
additional restriction, but allowing an endpoint never bypasses usecase checks.
Public browser forms retain appropriate CSRF protection. Webhooks use provider
verification rather than a user session; return URLs are not proof of payment.

Choose generated guards or method-security integration after a small executable
spike. Prove nested invocations, proxy/self-invocation boundaries, validation ordering,
context cleanup and transaction ordering. A policy using a MANDATORY repository
requires a transaction. Shared capacity writes require atomicity, not just a
`@PreAuthorize` expression. A starter must coexist deliberately with application
SecurityFilterChain configuration rather than silently replacing it.

Map unauthenticated/forbidden/limit failures and technical failures coherently across
REST and MVC. Decide actual statuses and redirects; do not make billing failures
look like authentication failures. UI visibility helpers reuse decisions without
consuming resources and never replace server enforcement. Avoid sensitive input or
credentials in exceptions, audit trails, events and email links.

## Complete reusable lifecycles

| Area | Required outcome |
| --- | --- |
| Identity | Self-registration, verification, login/logout, recovery, expiry and replay handling, disabled accounts |
| Accounts | Personal accounts and organizations, invitations, membership/role management and account switching |
| Authorization | Scoped rights, all/any, object checks, denial explanations, dynamic restrictions and technical actors |
| Catalog/subscriptions | Declarative plan/benefit direction; subscription state, booking, renewal, change, cancellation and entitlement transitions |
| Payments | Checkout, verified asynchronous results, pending/refused/cancelled/paid outcomes, retry/idempotency, refund handling |
| Customer invoicing | Seller/buyer data, numbering, currency/tax calculation policy, immutable issued documents, delivery/access and correction workflow |
| System email | Verification, recovery, invitations and billing notices; durable dispatch, templates, retries and observable failures |
| User experience | Reusable self-service screens, account/billing administration, useful errors and customization points |
| Operations | Local simulators, migrations, configuration/secrets, deployable sample, health/recovery and actionable logs |

Invoice behavior must be defined for an explicit supported seller/customer and tax
scope. Decide PDF/e-invoice formats, invoice issuer (application seller or provider),
credit notes, retention and legal review. Do not claim universal tax/legal compliance.
Legal or financial requirements require authoritative review when those contracts
are specified. Unsupported jurisdictions/formats must be explicit, not silently
accepted with guessed tax treatment.

Plan changes remain deliberately open: decide whether existing subscriptions retain
old benefits, when changes take effect and how upgrades/downgrades/proration behave.
A versioned catalog is a candidate, not an accepted requirement for a complex plan
versioning engine. The chosen minimal coherent model must cover existing customers
and upgrades/migrations without silently changing contracts during deployment.

Mollie/Stripe and Brevo are intended provider candidates. Decide which real adapters
must ship in 0.6.0; do not claim both payment providers implemented before that is
agreed and evidenced. Local payment/mail adapters are mandatory and credential-free.
They must exercise the same verification/result-processing and delivery workflows,
not a separate happy-path shortcut. Live credentials are never required in CI.

## Ready-to-use test providers

Provide payment and mail test providers in addition to the selected Mollie/Stripe
and Brevo integrations. An application must start and exercise its complete local
flows without API keys, paid accounts or outbound provider calls. Choose test versus
real bindings explicitly through normal application/profile configuration.

The first-run default test scenario returns a documented, predefined success result.
Configuration and test controls also select deterministic refusal, cancellation,
pending, timeout/failure and recovery scenarios, including predefined response
sequences. A mail provider exposes rendered messages in a local inbox rather than
sending them externally. A payment response must still travel through the normal
checkout/result and entitlement workflow; simulated success is never a bypass.

Test providers are visibly identified in the UI and operational events and can be
reset/seeded for reproducible E2E tests. Verify default startup without any provider
credentials and assert no unexpected network traffic. Never silently fall back to
simulation when a real provider is misconfigured or unavailable. Production startup
must reject test bindings unless an explicit, documented deployment option permits
a deliberately simulated environment; no silent successful fake payment.

## Business-oriented observability and safe logging

Generate structured, understandable operational events for registration lifecycle,
membership/permission decisions, restriction allocation/denial, subscription
transitions, payment/reconciliation, invoice issue/correction and mail dispatch.
An application developer should be able to explain what happened, its outcome,
which component must react and how to correlate related work.

Use stable event/reason codes, safe operation/provider identifiers, transition names,
attempt/outcome and an opaque operational correlation reference. Logging fields must
be allowlisted and reviewed. Do not log passwords, credentials/tokens, reset or
verification links, email addresses, names, addresses, invoice/document contents,
payment instrument details, authorization headers or arbitrary request/response
bodies. Raw account/user IDs are not automatically harmless: omit them by default
and define any pseudonymous reference and access/retention policy explicitly.
Sanitize third-party exception text and prevent unsafe payloads from leaking through
stack traces, HTTP errors or simulator diagnostics. Do not print full local mail
content in ordinary application logs; the local inbox is a separate controlled UI.

Keep operational logs distinct from business ledgers and any formal audit trail.
Neither successful logging nor a missing log line decides whether payment happened
or quota was consumed. Test useful positive/negative event sequences and scan captured
logs for deliberately seeded sensitive values, including failures and retries.

## Clear outcomes, errors and recovery

Define a shared outcome/reason catalog before implementing the workflows. Every
failure contract must specify a stable typed code, a safe user message, available
next action, developer/operator classification, correlation reference, and retry
semantics. Successful, denied, pending, exhausted and technically failed operations
must be distinguishable without parsing exception text. Public details must not
reveal hidden objects, other accounts, confidential policy data or provider payloads.

| Situation | Required understandable result and reaction |
| --- | --- |
| Missing authentication or expired session | Login/re-authentication path; distinguish from missing permission |
| Missing right or invalid scope | Explicit denial; no execution or privilege gain; suitable contact/role guidance without data disclosure |
| Per-operation limit exceeded | Explain the allowed request adjustment when safe; do not partially truncate work silently |
| Capacity or quota exhausted | Identify the applicable restriction and safe remaining/reset information; offer delete/reduce/wait/upgrade only when meaningful |
| Subscription inactive or benefit unavailable | Explain the effective entitlement state and permitted account-management action; avoid a generic login error |
| Payment refused/cancelled | No successful-payment claim; explain retry/change method/cancel action with safe provider-neutral reasons |
| Payment pending or outcome unknown | Explicit processing/verification state; no premature paid entitlement, duplicate charge or invoice retry |
| Renewal failed | Apply the agreed grace/retry/suspension policy; notify appropriate customer/admin; recovery path must be specified |
| Provider/configuration/infrastructure failure | Distinguish local configuration from remote failure where known; safe operator evidence and retry guidance, never a fabricated business refusal |
| Invoice or email processing failed | Preserve authoritative payment/subscription state; expose delivery/issuance recovery without charging again |

REST responses, MVC pages/forms, background workers and developer-facing APIs must
agree on meaning while using appropriate transport presentation. Automatic retries
are permitted only for expressly defined idempotent operations; uncertain external
effects require reconciliation. Expected denials need useful, proportionate logs,
not indiscriminate ERROR stack traces. Choose HTTP statuses, error structures,
localization/message customization and public disclosure rules in D03 rather than
baking speculative mappings into this planning document.

## Technical tenancy design slice

The [technical tenant contract](tenant-technical-contract.md) now has implemented
context, generated storage/schema, module and event-worker slices, with PostgreSQL
proofs recorded in the release ledger. Trusted identity-driven selection, provisioning,
webhooks and the complete SaaS acceptance remain OPEN. The original design document
is not by itself implementation evidence.

## Authorization foundation

The [usecase authorization contract](../contracts/usecase-authorization.md),
[module requirements](../contracts/module-authorization.md) and
[explicit-access startup check](../contracts/explicit-authorization.md) are implemented.
Generated guards and Spring/PostgreSQL tests cover nested execution and safe failures.
Actual user/membership/role policies, identity UI, dynamic restrictions and assembled
SaaS acceptance are still required. The next design slice is the
[identity/membership proposal](identity-membership-0.6.0.md).

## Decisions before implementation

The rows below track decision areas, not claims that every earlier foundation is still
unimplemented. Tenant storage/context, permission requirements, module contracts and
the explicit-access startup check now have implemented contracts and evidence in the
release ledger. Identity-specific D02/D05 details and the newly explicit D14 remain
under review. Unresolved release-affecting choices still block acceptance. A proposal
is not approval; record accepted resolutions and their PRs.

| ID | Decision |
| --- | --- |
| D01 | Core/module boundaries, repositories, licenses, public contracts and release coordinates |
| D02 | Typed actor/scope, account resolution, technical actors and background authorization |
| D03 | Permission declarations, all/any, public defaults, object scope and failure taxonomy |
| D04 | Fact providers/access APIs for dynamic limits/capacity/quota; transaction ordering |
| D05 | Role/group grants, entitlement combination, multiple allocations and explicit administration boundaries |
| D06 | Quota/capacity ownership, persistence, idempotency, external outcome rules and recovery |
| D07 | Plan/price definitions, effective dates, renewal/change/cancellation and existing subscribers |
| D08 | Payment providers, recurring-payment mechanism, webhook verification, refunds and reconciliation |
| D09 | Supported invoice/tax scope, issuer, numbering, documents/corrections and required review |
| D10 | Mail provider, delivery ledger/templates, secure links and retry behavior |
| D11 | Reusable UI delivery/customization, module activation and single-command local start/deployment |
| D12 | Release inventory and automated acceptance gate; scope agreements across extension repositories |
| D13 | Tenant technical foundation implemented; provisioning and whole-SaaS integration remain OPEN (no platform admin) |
| D14 | Identity credentials, email login-key canonicalization, challenge lifetimes, public-flow abuse protection and session policy — proposed in the identity contract |

These can be resolved incrementally. Identity credentials, tax algorithms and payment
semantics must not be guessed to avoid asking a genuinely necessary question.

## PR sequence

The next implementation sequence starts with real identity/membership ownership before
restriction accounting; both remain required for 0.6.0. Earlier foundation slices are
recorded below for context, not as tasks to repeat.

1. **Plan and acceptance ledger** (planning baseline): align roadmaps; no language/runtime change.
2. **Authorization/tenant contract and Spring spike**: D02/D03/D13, entrypoint-to-usecase example,
   denial taxonomy, chosen enforcement boundary and nested-call proof.
3. **Tenant foundation and rights/tooling**: resolve D13 with the authorization context;
   implement automatic isolation across schema, repositories, modules and workers. Then
   continue with the shared compiler/analyzer model, generation, runtime/starter,
   LSP/IDE metadata/navigation/completion, executable reference and tutorials together.
4. **Identity/accounts/roles contract and packaging decision**: review the
   [identity/membership proposal](identity-membership-0.6.0.md), then resolve D01/D14 before
   publishing extension implementations. Existing permission/tenant foundations do not
   supply a login system.
5. **Identity/accounts/roles implementation**: reusable modules, public flows, protected
   administration and initial end-to-end UI. Resolve packaging before publishing.
6. **Dynamic restrictions**: limits/capacity/quota facts, atomic accounting and explicit
   APIs; concurrency/failure tests, then functional/external reservation lifecycle.
7. **Subscriptions/payments/invoices/mail**: deliver complete vertical slices through
   modules, adapters, migrations and UI; resolve D07–D10 before their implementations.
8. **Application assembly**: real consuming application, documented deployment, local
   simulators and provider sandbox checks. Library internals alone do not satisfy this.
9. **Dedicated completeness audit and release gate**: close the acceptance ledger,
   fresh artifact consumption, plugin verification and maintainer go/no-go.

Split these into smaller reviewable PRs where useful. Target `feature/0.6.0`, include
code/tests/docs in each feature PR, and keep supported user documentation accurate.
No automatic merges or publication are authorized by this plan.

## Preserve the simple application; add SaaS explicitly

Keep existing small Vernac examples and current non-SaaS Wizard templates intact.
The full SaaS example is a separate application, tentatively `examples/saas-workspace`,
not a transformation of Dispatch or the basic Spring Boot starter. Add an explicit
**SaaS application** option to New Vernac Project alongside the existing choices.
It must generate an independently buildable starter with the required modules,
activation/migrations, reusable UI, local database and predefined test payment/mail
providers. A first run needs no real API keys. Existing choices/default behavior stay
usable without SaaS dependencies. Verify both paths from the actually packaged plugin.

## Documentation and evidence

Update contracts, language/AI references, configuration reference, FAQs, IDE What's
New and tutorials with each implementation. Compile reference snippets. Provide a
story from clean checkout through deployment, registration, paid use, invoice/email,
renewal/failure and recovery. Include diagrams for trust boundaries and asynchronous
lifecycles where useful. Label simulations and unimplemented proposals precisely.

Use the [feature checklist](feature-review-checklist.md) for consistency, compiler
quality, Java interoperability and tooling. Track every release promise in the
[0.6.0 acceptance ledger](release-0.6.0.md); source code or a green reactor alone is
not evidence of a complete, integrated product.

References for the implementation spike:
- [Spring request and method authorization](https://docs.spring.io/spring-security/reference/features/authorization/index.html)
- [Method security and ordering](https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html)
- [Browser CSRF protection](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
