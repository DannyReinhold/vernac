# Reusable capabilities and declarative usecase authorization

Status: **planned, not implemented**. Updated 2026-11-11 for the 0.6.0 objective.

The current product and implementation plan is
[SaaS foundation — 0.6.0 delivery plan](saas-0.6.0-plan.md). Completion is tracked in
[0.6.0 release acceptance](release-0.6.0.md). These supersede the earlier assumption
that SaaS is an unrelated later project; core/extension packaging, repositories,
licensing and individual module release versions are still open decisions.

## Purpose

A consuming application should obtain self-registration, personal/organization
accounts, rights/roles, subscription management, payments, customer invoicing,
system email and reusable self-service UI through integrated modules and general
Vernac mechanisms. Developers implement product behavior rather than assembling
these lifecycles themselves. No capability in this proposal is implemented merely
because its metadata or planning document exists.

Use the accepted [module contract](../contracts/modules.md) for exports, activation,
provider binding, compatibility and migration ownership. Model libraries may expose
shared values intentionally; functional modules encapsulate their state behind
application contracts. Avoid a universal User aggregate or a mandatory remote SaaS
platform. Provider-specific keywords are not a default language design choice.

## Settled direction

- Typed usecase requirements, explicit all/any and scoped actor/resource decisions.
- Check each inner usecase only when called; no privilege escalation or automatic
  traversal of every possible call. Quota ownership must prevent accidental double use.
- Endpoint authentication/protection and usecase authorization complement each other;
  public endpoints do not bypass protected usecases. System work needs explicit identity.
- Roles, memberships and purchased benefits remain distinct. Their combination must
  be deliberate; exact grant/entitlement syntax and conflict resolution remain open.
- Rights, per-operation limits, current capacity and consumption quotas are different.
  Every kind can depend on repository-loaded or later facts, not only input parameters.
- Shared capacity and consumption need atomic persistence, lifecycle and concurrency
  tests. A permission check must not silently book usage.
- Prefer scoped access such as `self.quota(ExportedInvoices).consume(amount)` and a
  functional consume helper, plus explicit reservations. These are API proposals,
  not supported source examples. Callbacks must not be retried implicitly.
- Local transactional effects and external effects need different accounting policies;
  a rollback cannot erase an external request. Specify unknown outcomes and recovery.
- Spring Security supplies integration rather than a competing password/session
  framework; prove ordering with REQUIRED/MANDATORY transactions and nested calls.
- Local payment/mail simulation must drive the same application paths as real adapters.

The [delivery plan](saas-0.6.0-plan.md) contains open decisions D01–D12, the PR
sequence and required lifecycles. No scope reduction or complex plan-versioning
mechanism is implied without an explicit decision.

## Existing foundations and follow-up

Versioned type/functional modules, persistent migration coordination, module events
and HTTP entrypoints already provide foundations. SaaS-specific policies and modules
remain to be implemented. Earlier post-release parser/analyzer consolidation was
completed; do not list module design or that consolidation as new prerequisites.

Apply the [feature review checklist](feature-review-checklist.md) to every slice,
including language consistency, shared compiler logic, Java interop, IDE/LSP support,
executable documentation and module metadata. Keep user references accurate as code
lands. [IDE follow-up](intellij-deep-integration.md) and the broader
[platform roadmap](platform-roadmap.md) remain separate workstreams.
