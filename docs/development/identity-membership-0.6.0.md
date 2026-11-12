---
title: Identity, membership and roles — proposed SaaS module contract
---
# Identity, membership and roles — proposed SaaS module contract

Status: **review proposal, not implemented functionality**. This is the next slice of
[the 0.6.0 SaaS plan](saas-0.6.0-plan.md). Merging this document accepts the design
baseline; it does not publish artifacts or close release acceptance. The source/license
decision and the small implemented foundation are recorded separately below.
The current compiler/runtime APIs remain authoritative. Names of future modules,
operations and configuration below are proposals, not callable APIs.

## 1. Boundary and release decisions

The language/runtime already supplies nominal permissions, generated usecase guards,
verified actor integration, tenant isolation and explicit-access inspection. Identity,
accounts, membership and role administration belong in reusable functional modules,
not provider-specific compiler keywords or a universal User aggregate in product code.

Source/release decision accepted after this proposal's review: SaaS extensions live
under `saas/` in the existing Vernac repository, are free/open source under the same
Apache-2.0 license, and initially share version 0.6.0 with the coordinated release.
They remain independent Maven artifacts; compiler and general runtime MUST NOT depend
back on them. There is no separate private repository or pending commercial license.

D01 is resolved for this baseline. The first actual artifacts are
`org.vernac:vernac-saas-identity` and `org.vernac:vernac-saas-identity-spring-security`.
Their [implemented foundation](../contracts/saas-identity-foundation.md) supplies nominal
contracts and an explicit actor bridge. The optional
[JDBC directory](../contracts/saas-identity-jdbc.md) now adds internal persisted
eligibility state without registration, credentials or membership operations. Remaining lifecycle sections below are
still proposals, not delivered registration or account functionality. Future artifact
splits will follow actual boundaries rather than creating every planned module now.

| Boundary | Owns | Deliberately does not own |
| --- | --- | --- |
| Identity | Stable user identity, credential lifecycle, enabled state, verification/recovery challenges | Subscription state or product data |
| Accounts/access | Account-to-tenant mapping, memberships, scoped role assignments, invitations, ownership transfer | Passwords or provider payment credentials |
| Spring security adapter | Authentication/session handling, trusted actor mapping, verified selection, CSRF and safe ingress failures | A second copy of product permission formulas |
| Product | Its aggregates, rights and object-specific policy facts | Handwritten tenant predicates throughout repositories |
| Billing/entitlements | Subscription-derived benefits and limits in a later slice | Automatically promoting a member to account owner |

Export IDs, immutable values, narrow usecases and deliberate extension ports. Keep
identity/membership repositories, token records, credential representations and
management aggregates internal. A consuming application may adopt an exported UserId
or use its own reference and ACL. Do not embed a foreign User aggregate in product data.

## 2. Identities are different things

| Identity | Meaning |
| --- | --- |
| UserId | One stable human login identity; never the email address |
| AccountId | A personal or organization workspace/billing relationship |
| TenantId | The immutable technical data-isolation identity mapped to that account |
| MembershipId | One user's participation and scoped role assignments in one account |
| VerifiedActor | The runtime realm/subject/kind reference, not the entire User model |

One account maps to one tenant; changing its subscription does not change the tenant.
A user can participate in several accounts. Creating a personal account does not make
that user the owner of every organization they join. An account is not a login and a
subscription is not a membership. There is no implicit platform administrator.

The proposed standard human actor maps the identity module's issuer/realm and UserId
to VerifiedActor. Never infer it from email, display name, an arbitrary principal name
or request claims. A technical actor is explicit and separate; it does not impersonate
a human owner or gain account-wide rights by being technical.

## 3. Registration and the personal workspace

Proposed default journey:

1. Accept a validated address through a CSRF-protected public flow (no password yet).
   Concurrent registration uses a database uniqueness constraint on the chosen login
   key. The outward response must not disclose whether that address already exists.
2. Persist a pending identity, a purpose-bound verification challenge and a protected
   mail-delivery request in one local transaction. Raw passwords never enter domain events,
   persistence or logs. A delivery failure leaves a recoverable pending registration.
3. The explicit verification POST sets the visitor-chosen password, atomically
   consumes the challenge, activates the identity and provisions its personal account,
   tenant mapping and first owner membership.
   Failure rolls back that local provisioning; duplicate verification cannot create
   another personal account. Enforce uniqueness in the database, not only application checks.
4. Require normal authentication after verification. Verification alone does not mint
   a session or authorize a supplied redirect URL. The UI provides a clear next step.

A unique personal-account ownership key and idempotent provisioning identifier must
survive retries and restart. Organization creation is a separate authenticated operation
that creates its own tenant and owner membership. External mail is delivered after
commit through the shared mail/outbox path, not inside the database transaction.

Login-key canonicalization is an explicit prerequisite: preserve the delivery address;
do not invent provider-specific dot/plus normalization. The concrete ASCII/case policy,
uniqueness representation and migration behavior are accepted as policy in the
[D14 credential contract](../contracts/saas-identity-credentials.md).
Never change those rules invisibly for existing users.

## 4. Credentials, sessions and challenges

Use Spring Security's authentication/provider/PasswordEncoder contracts; no custom
password comparison, reversible password storage, home-grown session protocol or
JWT implementation. Use an adaptive encoder with an upgrade strategy. Pin encoder
parameters and input bounds in the implementation contract against the managed Spring
versions; reject unsupported lengths rather than silently truncating. The
[D14 policy](../contracts/saas-identity-credentials.md) selects the algorithm, parameters
and password policy. The [first primitives](../contracts/saas-credential-primitives.md)
are implemented; credential persistence and complete public workflows are not.

Proposed first browser path: stateful Spring Security sessions with Spring Session JDBC
for shared storage across instances. Session tables have an explicit reviewed migration
owner, separate from generated product tables. Do not mix session persistence SQL with
Vernac schema ownership or turn on uncontrolled production schema initialization.
Session fixation protection, persistence, expiry and logout use Spring mechanisms.
A principal carries a stable identity reference, not a permanent copy of all grants.

Public login, registration, recovery and invitation forms retain CSRF protection.
Use configured origins and validated same-origin/local return targets; never build
security links from untrusted Host/forwarded headers. Cookies, session timeouts,
proxy trust and secure production settings need executable configuration tests.

Verification, password recovery and invitation challenges are distinct purposes. Store
only a verification representation of the secret; bind purpose, subject/invitation,
expiry and consumed state. Generate secrets using a reviewed platform cryptographic
facility. Consume atomically with the corresponding state transition. GET can render
a confirmation screen but must not complete a recovery or consume a one-time action.
Concurrent acceptance has one winner. Expired/reused/malformed challenges share safe
public outcomes; expected failures must not leak secret material or stack traces.

Resend invalidates superseded challenges for the same purpose. Password recovery does
not make an existing identity disabled while a stranger requests email. Successful
password reset invalidates existing sessions; disable/closure also blocks their later
use. A stale stored principal cannot override current disabled state. Rate limiting
is required for public credential/challenge operations, distinct from paid usage quotas.
Concrete lifetimes, resend policy, encoder settings, abuse/storage limits and cleanup
requirements are accepted in
[D14](../contracts/saas-identity-credentials.md); full runtime delivery remains OPEN.

## 5. Membership, selection and the existing runtime

Identity/access records are controlled global metadata: initial authentication and
membership discovery must work before a product tenant is selected. Their repositories
remain internal; module usecases must enforce actor/account predicates explicitly.
This is not a license to expose global User or Membership byId operations to clients.

A requested AccountId in a route or session is only a selection request. The trusted
adapter resolves the current enabled actor, active account, active membership and
immutable account-to-tenant mapping using controlled metadata access. Only then may
it open TenantContext.openVerified for the bounded execution. The browser does not
supply authority merely by sending a TenantId. Clear/restore selection in finally;
never retain it in a reusable worker thread.

The pre-transaction selection check is not the final permission decision. Every reached
protected usecase still uses AuthorizationGuard; its evaluator rechecks enabled identity,
active membership and current role/entitlement facts in the actual business transaction.
The existing actor/tenant pin rejects mid-transaction replacement. A policy may read
controlled global access metadata and tenant-scoped product facts without changing scope.
Technical failures deny execution with the existing distinct safe technical outcome.

Switching accounts is explicit and verified, outside a running product transaction.
Do not silently fall back to a different account after membership loss. A public global
flow needs no fabricated tenant. Public entry does not clear an existing verified scope
or grant rights to a later inner usecase. Deferred event routing restores trusted origin,
not a captured browser session; its technical actor/grants remain a separate contract.

Do not promise instantaneous cancellation of in-flight work. A revocation affects later
checks according to transaction visibility; already performed external effects cannot
be undone by ending a session. Recheck sensitive administration where appropriate,
without pretending to provide a distributed instantaneous revocation barrier.

## 6. Roles grant capabilities, plans gate benefits

Roles are account-scoped named sets of existing permission keys. Assignments are
persistent membership facts; product usecases continue to declare permission keys,
never role labels. Combine several assigned roles by union. The first slice has no
negative grants, implicit hierarchy or wildcard granting every future permission.

Proposed declarative catalog shape, **not implemented configuration**:

```text
roles:
  example.workspace.editor:
    permissions:
      - example.workspace.DocumentPermissions.READ
      - example.workspace.DocumentPermissions.EDIT
  example.workspace.reader:
    permissions:
      - example.workspace.DocumentPermissions.READ
```

The eventual catalog loader must validate stable qualified keys against the available
permission declarations at startup, detect duplicates/unknown references and expose
read-only descriptions to the UI. Do not interpret arbitrary Spring authorities as
these grants. A code-reviewed catalog defines roles; administrators assign permitted
roles through protected usecases. Custom-role editing and a new role DSL keyword are
not assumed. Role catalog format and ownership require D05 approval before implementation.

The standard account Owner role has finite explicit account-management capabilities.
Product rights are granted explicitly, not by an all-powerful owner bypass. Account
ownership is also a protected invariant: disabling/removing/demoting the last active
owner must fail. Serialize competing owner mutations on an account-level guard/lock;
two concurrent departures must not both pass a count check. Ownership transfer is an
explicit authenticated operation, not an invitation with an arbitrary role payload.

An invitation is bound to account, recipient and allowed role assignment. Accept it
only as the authenticated intended identity after address verification. A valid link
alone cannot create ownership or membership for another identity. Inviting an already
active member is idempotent and never silently escalates roles. Expired/revoked/reused
invitations and concurrent acceptance have deterministic outcomes.

For a product capability, combine independent facts:

- The actor and account membership must currently be eligible.
- Assigned roles must grant the exact requested permission.
- Only capabilities explicitly declared plan-gated also need an effective entitlement.
- Object-specific policy checks still apply; nominal resource IDs and tenant isolation
  are not replaced by the role catalog.

All/any evaluates these pure decisions through the existing guard. Subscription payment
never promotes a member to owner; buying a benefit cannot override missing role rights.
Quota/capacity accounting remains an explicit later operation, not a role check side effect.

## 7. Outcomes and observability

| Situation | Required interpretation |
| --- | --- |
| Registration/recovery request accepted | Neutral public result; delivery/provisioning may still be pending |
| Verification or invitation invalid/expired/reused | Safe action unavailable; provide resend/recovery when meaningful |
| Authentication missing/expired | Existing unauthenticated outcome and deliberate login flow |
| Disabled identity | No new/current authenticated business access; no public existence disclosure |
| Membership absent/revoked or wrong account | Existing forbidden outcome; no fallback tenant or hidden-object disclosure |
| Last-owner mutation | Typed business rejection; explain ownership transfer to an authorized administrator |
| Access storage/identity provider unavailable | Technical unavailability, not a fabricated invalid password or permission grant |

Operational events need allowlisted lifecycle/reason/operation codes and safe correlation.
Never log passwords, credential hashes, challenge hashes/secrets, addresses, session IDs,
links, arbitrary request/response bodies or unrestricted raw user/account/tenant IDs.
A local inbox is a controlled separate surface, not a logging destination. Failed mail
must be retryable without creating another user, membership or consumed verification.

## 8. Review decisions and implementation sequence

| Decision | Proposed direction | Still required before implementation |
| --- | --- | --- |
| D01 | ACCEPTED: same repository, Apache-2.0, separate `org.vernac` artifacts, shared 0.6.0 version | Apply this boundary to each added module; do not add SaaS dependencies to core |
| D02 | Stable realm/UserId actor; verified account-to-tenant selection | Concrete module adapter API and enabled-state/session refresh proof |
| D05 | Account roles union explicit keys; no wildcard/negative/hierarchy; explicit owner invariants | Approve catalog/configuration ownership and protected assignment operations |
| D14 | Spring authentication/encoder, JDBC browser sessions, purpose-bound challenges | ACCEPTED through PR #33; implement/test the [D14 baseline](../contracts/saas-identity-credentials.md); full delivery gates remain OPEN |
| D10 | Shared mail/outbox integration; credential-free local inbox | Actual mail-module contract; no temporary direct-send shortcut |

D14 was accepted through PR #33, so credential behavior is not guessed while writing
code; its runtime/flow acceptance remains separate. Source/license/version choices are now settled as recorded above; publication remains
a separate release action.
Once reviewed, proceed in small executable slices:

1. Packaging is established; finalize credential defaults and add identity/access migrations and
   separate consumer tests. Fresh/upgrade histories and trusted access metadata queries
   precede public exposure.
2. Implement pending registration, verification and idempotent personal provisioning
   through the mail port/local inbox, then Spring login/session/logout and recovery.
3. Implement account selection, scoped memberships, invitations and owner-safe role
   administration; connect the real PermissionEvaluator to generated usecases.
4. Add reusable screens and a separate SaaS sample/Wizard option. Enable both explicit
   tenancy and explicit authorization, retain the non-SaaS starter, and test denial UI.

Required proofs include concurrent duplicate registration, challenge expiry/replay,
mail failure/retry, atomic provisioning rollback/restart, disabled/stale sessions,
account switching and pooled-thread cleanup, foreign memberships, concurrent last-owner
changes, invitation escalation attempts, role/plan separation and seeded-secret log scans.
Exercise real generated boundaries and restricted PostgreSQL roles, not mocks of every
module. A01/A02/A03/A04/A11/A12/A18/A20/A21 and the full release ledger remain OPEN.

## Spring reference points

These are design references, not proof that this proposed module runs. The repository
currently manages Spring Boot 3.4.2; implementation must compile/test its actual resolved
versions. Rolling reference pages may describe newer Spring versions.

- [PasswordEncoder and encoding upgrades](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html)
- [Authentication persistence and session protection](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html)
- [CSRF, including login/logout](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
- [Spring Boot 3.4 Spring Session integration](https://docs.spring.io/spring-boot/3.4/reference/web/spring-session.html)
