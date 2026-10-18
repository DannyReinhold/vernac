# Reusable capabilities and declarative usecase authorization

Status: **planned, not implemented**. Planning baseline: 2026-10-12, after Vernac
0.2.0; development line 0.6.0-SNAPSHOT. This is an actionable design backlog, not
an accepted language contract or a promise that this work ships in 0.4.0.

## Scope and sequencing update (2026-10-14)

SaaS capabilities will be developed in a separate project with independent releases;
its licensing model is undecided. Core Vernac currently prioritizes IDE integration,
language consistency and reusable adapter/platform foundations. This proposal remains
design input for general policy extension points, not a commitment to implement
subscriptions or provider integrations inside the compiler. See the
[platform roadmap](platform-roadmap.md).

## Goal

Applications should reuse well-defined domain capabilities without operating a
separate platform. A Maven dependency should be able to supply an implementation
and public Vernac definitions. Initial motivating capabilities are identity,
tenancy, subscriptions, payments (Mollie/Stripe), notifications (Brevo) and local
simulation adapters. No provider integration is implemented by this proposal.

Keep the core language architectural. Generic subdomains are implemented *using*
Vernac, rather than becoming provider-specific compiler keywords. Capabilities is
a product-family term, not a new DDD kind of bounded context. A module/JAR is not
automatically a bounded context; a bounded context need not be a remote service.

Two extension forms must remain distinct:

1. A model library intentionally shares public values/types with its consumers.
2. An encapsulated domain module exposes application contracts and integration
   events while keeping aggregates, repositories and persistence private.

A single universal User aggregate spanning identity, billing and product behavior
is not the target. Subscription accounts can belong to tenants rather than users.
The consuming application's own model is protected by ports and ACL where its
language differs. Do not require a redundant wrapper for every direct module API.

## Decisions to preserve

- External modules may supply compiled implementation plus public Vernac metadata.
- First-class declarative access requirements initially apply to **usecases only**.
  Outbound port authorization is out of scope for this iteration.
- A caller may be denied, allowed without additional product limits, or allowed
  subject to explicitly modeled limits. Ordinary domain invariants always apply.
- A permission requirement belongs to the operation. How it is granted belongs to
  a policy/module/application configuration, not a hard-coded subscription provider.
- Authentication, membership authorization and purchased entitlements are different
  inputs. A paid subscription does not authorize every user; roles do not imply a
  purchased feature. Their conjunction/disjunction must be deliberate.
- Spring Security should supply identity integration and authorization machinery.
  Do not build a second password/session/token framework.
- Permission checks must not silently consume quota. Consumption/reservation is a
  distinct business operation.
- Domain-facing contracts use typed values; avoid magic strings, -1 for infinity,
  null allowance objects and maps of untyped constraint values.
- Local provider simulators and automated test control are first-class deliverables.

## Workstream A: consuming Vernac modules

The [accepted module design contract](../contracts/modules.md) is authoritative for
export/import semantics, activation boundaries, persisted external VOs and migration
ownership. Versioned type and functional modules, persistent coordination, module events
and HTTP entrypoints are implemented. The prerequisite module work is complete; SaaS
policies and ready-made capabilities remain future work.

The implemented package contains compiled Java, `META-INF/vernac/module.json` and
optional source attachments. Its data-only descriptor records public names/signatures,
compatibility information, source locations, component dependencies and ownership.
Persistent owners package verified migrations in isolated resource locations. Consumers
reuse exported types and compiled classes, while the application explicitly selects
providers and activates functionality. The compiler/LSP share Maven's resolved index.

Do not duplicate or redefine these mechanisms in the authorization workstream. Build
capabilities on the accepted contract and its lifecycle tutorial, retaining explicit
ownership and reviewed migrations when values change across module boundaries.

## Workstream B: authorization contract

Provisional syntax only; **do not generate this as current Vernac code**:

```text
permission ArchiveDocuments;
usecase ArchiveDocument(TenantId, DocumentContent)
    requires ArchiveDocuments for tenantId
    ...
```

The concept has three explicit inputs:

- Actor: the authenticated person or deliberately configured system identity.
- Permission: a typed identity for an application capability.
- Scope: the tenant/resource affected by this invocation, derived from typed inputs.

A tenant ID supplied by a caller is a requested scope, never proof of membership.
Object access must also verify the object's relationship to that scope. An anonymous
call, missing policy or missing actor must never accidentally become unrestricted.
Define explicit public-usecase semantics before enabling authorization globally.

A successful decision may carry a typed allowance, such as ArchivingAllowance.
The exact syntax for binding it and exposing it through the existing self Access
view is open. If introduced, the binding must be invocation-local, not a mutable
field of a singleton bean. Different policies returning incompatible allowance
types must fail at configuration/compilation boundaries rather than at a cast.

An allowance is a decision for a particular actor, scope and invocation, not a
permanent transferable authority token. Define its validity if the subscription or
membership changes. Do not promise instantaneous global revocation or serializable
policy snapshots without implementing them. Avoid independently evaluating a policy
twice and using inconsistent results for authorization and allowance construction.

## Restrictions are not one mechanism

| Restriction | Example | Enforcement |
| --- | --- | --- |
| Access | Actor cannot archive for tenant | Before business execution |
| Operation input | Maximum document size | Check actual validated content size |
| Shared capacity | Tenant storage budget | Atomic reservation/allocation |
| Periodic consumption | Monthly operations | Atomic ledger/counter with period |
| Data scope | Only own documents | Restricted query and object access |
| Domain invariant | Document already archived | Domain behavior/validation |

Do not treat result truncation as secure filtering: unauthorized rows must not
leak through counts, pagination or subsequent operations. Data-scope policy is a
later design slice, not automatic post-filtering in the first implementation.

For single-operation limits, typed Unlimited/Limited values are a candidate
representation. They do not exempt the operation from its ordinary invariants.
Avoid inventing a general constraint-expression language before testing real needs.

For shared budgets, checking a count and then inserting is not concurrency safe.
The reservation/consumption API must decide and book atomically, for example using
a conditional SQL update or row lock. A read-only permission check cannot provide
that guarantee. Do not recompute quota from every aggregate row on every request
without examining cost and concurrency semantics.

When all writes use the same database, budget booking and business persistence
should commit/rollback together. State explicitly whether attempts, successful
operations, bytes currently stored or lifetime usage are counted. Specify release
on deletion, upgrades/downgrades, period boundaries, time zone, over-limit existing
usage and idempotency. Inject a clock for time-dependent tests.

External storage/provider work needs a reservation lifecycle: reserve, perform,
confirm or release/expire. A database rollback does not undo an upload/payment.
Handle crashes, retries and duplicate callbacks. This is application coordination,
not an effect hidden inside a Spring authorization interceptor.

## Spring Security integration direction

Use Authentication/SecurityContext for the technical identity and translate it
into the application's actor model without leaking credentials into domain code.
The application retains control over authentication and its SecurityFilterChain.
A Vernac starter must not unexpectedly replace existing security configuration.

Candidate implementation: generated method metadata plus a Spring Security
AuthorizationManager and AuthorizationManagerBeforeMethodInterceptor. This
metadata is generated Java, not user-written annotations in Vernac. A generated
explicit guard is an alternative if proxy behavior proves unsuitable; choose one
coherent enforcement path rather than two independent policy evaluations.

Required implementation decisions and tests:

- Define interceptor ordering relative to REQUIRED transactions and input checks.
  A policy querying a MANDATORY repository needs an existing transaction.
- Enforce all normal Spring-managed invocations, including calls from another
  usecase; document proxy/self-invocation/manual-construction boundaries precisely.
- Fail startup if a declared requirement lacks its necessary policy infrastructure.
- Separate forbidden decisions from policy infrastructure failures. Translate
  explicit denials consistently with Spring Security; never turn outages into an
  allow decision or disguise them as a customer subscription problem.
- Keep audit reasons structured and avoid exposing credentials or arbitrary input
  payloads in exceptions/logs. HTTP presentation is an inbound concern.
- Define background/system identities and deliberate delegation. Deferred events
  do not automatically inherit a user's SecurityContext or gain all permissions.
- Do not persist bearer tokens in events. Decide whether processing is an authorized
  continuation or a fresh operation that must recheck current rights.
- Keep nested execution context scoped and cleared on failure; no ThreadLocal leaks.
  Async propagation is explicitly outside the first synchronous-usecase slice.

Spring references for implementation (verify against the selected Spring version):
- https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html
- https://docs.spring.io/spring-security/reference/servlet/authorization/architecture.html

## Provider modules and local simulation

Payments, subscriptions and mail have different responsibilities. Payments capture
provider-neutral payment/refund states; subscriptions determine purchased periods
and benefits; the product determines what those benefits mean. Provider DTOs remain
behind ACL. Public integration events must not expose arbitrary internal aggregates.

Local payment adapters should offer a browser checkout and programmatic controls
for success, refusal, cancellation, pending status, delayed/duplicate callbacks and
refunds. Exercise the same inbound result-processing path as the provider adapter.
A browser return is not payment confirmation. Local mail should offer an inbox,
preview and controlled failures. No production credentials are required locally.

## Incremental backlog and acceptance gates

All tasks below are open. Scope a release after the design slices are reviewed.

- [ ] Agree on one actor/scope/permission example and forbidden/error semantics.
- [ ] Demonstrate ArchiveDocument: denied, allowed, size-limited, budget-limited.
- [ ] Define typed allowance binding and its lifetime; no implicit quota consumption.
- [ ] Implement Spring integration against the actual supported dependency versions.
- [ ] Prove two concurrent last-slot requests cannot both consume the same slot.
- [ ] Test rollback, duplicate requests, independent tenants and nested usecases.
- [ ] Define anonymous/public and explicit system invocation behavior.
- [ ] Define module descriptor and exports; build and consume a separate module JAR.
- [ ] Provide module-aware compiler diagnostics, LSP completion and navigation.
- [ ] Define module migration ownership and verify upgrades alongside application DDL.
- [ ] Build the local checkout-to-entitlement demonstration, then one real provider.
- [ ] Add subscriptions and email without coupling application behavior to providers.
- [ ] Review the feature checklist: user purpose, DDD boundaries, uniform syntax,
      compiler quality, Java interoperability, diagnostics, plugin support, tests,
      reference, tutorials and FAQs.

Do not implement outbound authorization, a universal policy DSL, distributed quota
transactions, inbound controller generation or all SaaS modules as prerequisites.
The next concrete design session is the ArchiveDocument example; resolve semantic
questions before choosing final keywords or generating Spring annotations.

Related current contracts: [usecases](../contracts/usecases.md),
[ports and ACL](../contracts/ports-adapters-and-acl.md),
[events](../contracts/events.md), [feature checklist](feature-review-checklist.md).

## Immediate prerequisite: post-release code consolidation

Before new syntax work, audit and remove obsolete parser productions and obsolete
compiler/generator paths deliberately (no compatibility requirement for abandoned
experiments). Check associated tests, fixtures and editor projection. Reconcile
stale status/wording in contracts with the supported project pipeline. Preserve
valid behavior tests; replace obsolete expectations only with an explicit reason.

- [x] Inventory retained legacy syntax versus supported project compilation.
- [x] Remove the obsolete single-file analyzer, generator fallback modes and abandoned grammar rules.
- [x] Route compilation through shared project resolution; verify compiler/LSP tests.
- [x] Reconcile affected documentation and continuously compile AI-reference examples.

The follow-up consolidation implements these prerequisites. This does not implement
the capability/authorization proposal above. Deep IDE refactoring and debugger work
is tracked separately in the [IntelliJ roadmap](intellij-deep-integration.md).
