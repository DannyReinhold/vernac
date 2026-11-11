---
title: Usecase authorization — proposed 0.6.0 contract
---
# Usecase authorization — proposed 0.6.0 contract

Status: **proposed for review; not implemented**. Baseline: 2026-11-11.
This is the next design slice of the [0.6.0 SaaS plan](saas-0.6.0-plan.md), covering
D02/D03 and the authorization part of D04. MUST describes the proposed implementation
requirement, not current compiler behavior. Merging this document accepts a design
baseline; it does not verify A03/A04 or close the Spring feasibility work.

The current [usecase contract](../contracts/usecases.md) remains authoritative for
existing syntax. All new syntax below is deliberately fenced as text. No runtime,
grammar, supported AI reference or public language baseline changes in this PR.

## 1. Named permissions

Proposed declaration, resolved through ordinary namespace/import rules:

```text
namespace example.billing;

permission InvoicePermissions {
    VIEW;
    EDIT;
    EXPORT;
    ADMINISTER;
}
```

A permission identity consists of the full Vernac declaration name and member name.
`example.billing.InvoicePermissions.EXPORT` is distinct from the same member in
another namespace. A declaration MUST contain at least one distinct member. Member
names follow existing identifiers and the Java enum collision checks; uppercase is
conventional, not mandatory. Renaming a deployed permission changes its identity and
requires deliberate migration of stored grants; no alias or automatic grant exists.

Proposed Java representation: a public enum in `<namespace>.authorization`, implementing
a runtime permission-key interface and exposing stable qualified identities. It is
an authorization artifact, not a domain VO or an arbitrary String authority. It MUST
NOT silently become a permitted entity field, usecase input or persisted enum value.
Permission/type/generated-name collisions need source diagnostics before generation.
The final interface spelling is an implementation detail to settle in the spike.

A reusable module MUST explicitly export a permission declaration used by an exported
usecase requirement. Extend the existing metadata kind/compatibility rules rather than
inventing a second import system. A permission declaration alone grants nothing.
An export is not a role assignment, account membership or module activation.

## 2. Requirements and syntax

A usecase has at most one optional `requires` clause. Proposed clause order is inputs,
optional `returns`, optional `requires`, optional `uses`, optional `validates`, then
`behavior`. Existing naming, result and dependency rules are unchanged.

A complete proposed declaration with an externally supplied implementation:

```text
namespace example.billing;
id InvoiceId;
value ExportLocation(String);
permission InvoicePermissions { VIEW; EDIT; EXPORT; ADMINISTER; }

usecase ExportInvoice(InvoiceId)
    returns ExportLocation
    requires all {
        permission InvoicePermissions.VIEW on invoiceId;
        permission InvoicePermissions.EXPORT on invoiceId;
    }
behavior {
    execute implemented by example.billing.handwritten.ExportInvoiceImplementation;
}
```

The external implementation retains Access followed by the ordinary typed inputs;
it is not passed Spring Authentication, credentials or an alternative public execute
signature. Inline and external bodies go through the same generated guard.

Allowed forms:

```text
requires permission InvoicePermissions.EXPORT

requires authenticated

requires public

requires any {
    permission InvoicePermissions.EDIT on invoiceId;
    permission InvoicePermissions.ADMINISTER on invoiceId;
}

requires all {
    permission InvoicePermissions.VIEW on invoiceId;
    any {
        permission InvoicePermissions.EDIT on invoiceId;
        permission InvoicePermissions.ADMINISTER on invoiceId;
    }
}
```

- A single leaf after `requires` has no trailing semicolon. Leaves inside groups
  have semicolons; nested `all { ... }` / `any { ... }` groups do not.
- Groups MUST be nonempty. `public` and `authenticated` are standalone forms, not
  leaves that accidentally make an `any` group unconditional.
- `on` accepts exactly one named, required ID or VO input in this first slice. Resolve
  implicit parameter names first. Unknown/optional inputs, Java expressions, arbitrary
  getter paths, dependencies and result references MUST be rejected clearly.
- `on` identifies a resource for a typed policy; it is not an account switch or proof
  of ownership. Composite VO input permits explicit compound resource identifiers.
- Without `on`, a permission is evaluated for the current verified execution scope;
  it MUST NOT default to a global grant when no scope is available.
- `authenticated` requires a verified enabled human or expressly authenticated
  technical actor. The scope is required only by requirements/operations using one.
- `public` adds no identity/permission prerequisite. It neither clears an existing
  context nor grants rights to an inner protected usecase. Tokens supplied to public
  registration/recovery workflows still need their own application validation.
- An omitted clause retains the current no-additional-authorization behavior for
  existing applications. It is not a newly inferred public declaration or automatic
  registration of a web route. Generated endpoints remain governed by web security.
- The SaaS assembly MUST enable a startup audit requiring an explicit clause on
  reachable exposed usecases, including dependency/module metadata; accidental
  omissions fail startup rather than silently making a SaaS operation public.
  The audit examines injected/reachable usecase dependencies, not possible execution
  paths to preauthorize them. It needs exemptions only as explicit reviewed config.

No `not`, role literal, plan name, subscription keyword, arbitrary expression or
quota consumption is introduced in this first requirement tree. Later restriction
contracts must extend this consistently, not overload a permission with side effects.

## 3. Actor, execution scope and resource

The runtime boundary is typed and independent of a universal User/Account model:

| Value | Meaning and provenance |
| --- | --- |
| Actor | Verified subject reference and human/technical kind, obtained from trusted authentication integration |
| Execution scope | Verified account/organization/application context under which work is requested |
| Resource reference | Actual named input value plus its nominal Vernac type identity, not its `toString()` |
| Permission key | Qualified declaration/member identity |
| Operation identity | Qualified usecase name, for policy routing and safe diagnostics |

Authentication maps to an actor; it does not prove account membership. An account
selection from a path/header/session is a candidate until membership/scope resolution
verifies it. Never trust a browser-supplied role, plan or account claim directly.
A global administration operation needs an explicitly configured application scope
and policy, not a null/empty scope interpreted as unrestricted access.

For `on invoiceId`, the policy can load the invoice or scoped facts to check ownership
and capability. A VIEW/EXPORT grant in account A cannot authorize an invoice in B.
The policy receives the real nominal ID type; equal UUIDs in different ID types do
not collide. Do not pass the entire raw HTTP request, credentials or all input values.

An `any` alternative is a real alternative: a branch without `on` does not secretly
validate the resource of a skipped branch. In particular, use a scoped ADMINISTER
alternative as shown above when administration must retain resource ownership.
Account-scoped repository access is still required in the implementation; a header
permission tree does not automatically rewrite queries or make returned rows safe.

Scope resolution MUST be shared per invocation and made immutable for that invocation.
Two calls must not independently infer different current accounts. A nested call
inherits the actor and verified execution scope; its `on` input does not override
them. Ordinary Access MUST NOT expose arbitrary actor/scope setters or impersonation.
Explicit delegation/account switching is a later separate contract.

The check is a decision at invocation time, not a permanent authority token or a
promise of instantaneous cross-process revocation. Avoid global success caching in
this first slice. Membership/entitlement consistency and in-flight revocation limits
must be documented and tested, not advertised as stronger database isolation.

## 4. Provider contract and tree evaluation

Introduce one explicitly bound authorization service per application, using existing
module/provider binding. Policies may compose internally; ambiguous bindings MUST
fail startup. Protected usecases MUST have their required infrastructure at startup;
there is no auto-allow fallback or accidental empty-role interpretation.

The service receives actor, verified execution scope, permission, optional typed
resource reference and operation identity. It returns a typed Allow or Deny with a
stable, safe reason category, or raises a technical authorization failure. Exact
Java names may be selected in the spike; the semantic outcomes are fixed here.
Missing grants are Deny; missing/unavailable infrastructure is not a customer denial.

The application/module policy owns the combination of membership rights, explicit
grants and plan benefits. This contract does not decide role/group precedence or
which permissions are plan-gated (D05 remains OPEN). A paid plan never creates a
membership role. Internal policy reasons MUST NOT leak confidential account facts.

Evaluate groups in declaration order and short-circuit:

- `all`: stop at the first Deny; allow only when every visited child allows.
- `any`: stop at the first Allow; deny if every visited child denies.
- A technical failure aborts evaluation immediately, including inside `any`. It MUST
  NOT be treated as Deny and bypassed using a later branch. An earlier short-circuit
  naturally means later providers are not called.

Evaluation MUST be side-effect-free with respect to business state and usage.
Operational audit events are allowed; reservations, consumption, payment calls or
mutations are not. Invocation-local reuse of identical pure facts is acceptable;
never reuse an outer Allow as permission for a different operation/resource.
Reject exact duplicate sibling leaves with clear diagnostics. Do not silently sort,
rewrite or infer specificity in a way that changes visible evaluation order.

## 5. Generated enforcement and transaction ordering

**Proposed enforcement choice:** a guard in the generated bean's public `execute`,
using a Spring-backed authorization service. Keep `@Transactional(REQUIRED)` and
existing constructor injection. Do not add an independent permission check both in
an AuthorizationManager interceptor and in the method body.

Normal supported execution order:

1. Spring's transaction proxy opens or joins REQUIRED.
2. Open a properly scoped invocation context; preserve the trusted actor/scope of
   an outer invocation. Missing actor is representable for public operations.
3. Run existing required-input DomainChecks. Null mandatory values still raise
   DomainValidationException; this technical input check performs no resource lookup.
4. For a protected requirement, authenticate/resolve scope and evaluate the guard.
   A policy reading a MANDATORY repository is now inside the existing transaction.
5. Run usecase `validates`, then inline/external implementation and result checks.
6. Close invocation context on every exit; commit/rollback through the outer proxy.

A denied call MUST NOT reach domain preconditions with data lookups, implementation,
repository writes, event emission or external I/O. Ordinary required-null and HTTP
binding failures can occur before authorization and retain their established behavior.
A public usecase may still reject invalid inputs, own tokens or domain invariants.

Every actually reached inner generated `execute` runs its own guard. Calls to a
private helper are not new usecases. A rejected inner protected call propagates a
runtime failure; existing REQUIRED rollback behavior applies. No independent commit,
savepoint, automatic retry or restoration of Java object state is introduced.
An application catching a proxied inner failure cannot assume its transaction is
still committable; Spring rollback-only semantics remain authoritative.

A body guard is not dependent on an additional authorization proxy. Direct/manual
construction still does not create Spring transactions, however. Protected entry
MUST require an active transaction and configured trusted authorization service and
fail technically before policy/body if either is absent. Do not execute policies
with auto-commit after bypassing the supported managed entrypoint. Calling an internal
static companion directly is unsupported, like bypassing other generated API guards;
this is architecture enforcement, not a sandbox against malicious Java/reflection.

Role/permission checks use Spring Security's Authentication/SecurityContext mapping
and may adapt its AuthorizationManager, but exact supported APIs require a spike
against the checked-in Spring Boot 3.4.2 BOM. No upgrade to a newer Spring major is
implied. AnonymousAuthenticationToken MUST NOT count as an authenticated actor merely
because its `isAuthenticated()` flag is true. Clear/restorably scope runtime context
on errors and pooled-thread reuse. Async/deferred workers start a new explicit
technical/continuation context; do not persist bearer credentials in events.

## 6. Endpoint, UI and system integration

Retain the application's SecurityFilterChain for login/session or bearer mechanisms,
CSRF and URL/HTTP-method restrictions. An integration starter MUST NOT silently
replace it. Generated endpoint access metadata is a follow-up contract, not new
HTTP/MVC syntax in this PR. SaaS endpoints must have an audited explicit access mode.

- A public route only permits reaching its usecase; it never bypasses that guard.
- A private route can require login before binding/mapping. It need not duplicate
  the usecase's permission formula, and may add a deliberately stricter restriction.
- A webhook authenticates/verifies the provider, then invokes under a narrowly
  authorized technical actor. Browser checkout return is not payment evidence.
- MVC visibility helpers are side-effect-free hints, not execution authorization.
  Hiding a button does not secure its operation; nor does a hint reserve capacity.
- MVC redisplay loaders are new usecase invocations and recheck rights; do not let a
  denied POST disclose data through a broader page loader. No automatic replay.

No request-bound principal is available magically in jobs/listeners. Choose explicit
system grants; no blanket ADMIN fallback. Authorization for outbound port methods
remains outside this first slice. Resource policies must not become arbitrary SQL
filters or post-filter paginated data as a substitute for proper tenant queries.

## 7. Failures and presentation

Proposed public-safe default codes are below. All usecase authorization failures are
unchecked exceptions outside the generic VernacDomainException/PortRejectedException
hierarchies; existing `domain rejection` rules MUST NOT accidentally turn them into
422 responses. Do not fabricate an outbound AdapterInvocation for authorization.

| Runtime outcome | Default HTTP result | MVC / application reaction |
| --- | --- | --- |
| Missing, anonymous or expired actor | 401 / AUTHENTICATION_REQUIRED | Safe local login path for browser pages; explicit API denial |
| Actor lacks required permission | 403 / ACCESS_DENIED | Explain lack of access without disclosing grants/resource existence |
| No selected scope when one is required | 403 / SCOPE_REQUIRED | Offer authorized account selection; never infer a global scope |
| Requested scope/resource is not authorized | 403 / ACCESS_DENIED | Same safe denial for hidden/missing resource facts |
| Misconfiguration or invalid runtime context | 500 / AUTHORIZATION_CONFIGURATION_ERROR | Operator action; no role/subscription upgrade suggestion |
| Authorization backend temporarily unavailable | 503 / AUTHORIZATION_UNAVAILABLE | Safe temporary-failure message; no implicit retry of the operation |

A legitimate domain `byId` absence after authorization retains the existing 404
behavior. For resource authorization, a policy MUST NOT reveal hidden existence:
missing and inaccessible lookup facts yield the same safe denial. This deliberately
means a protected resource-specific call can return 403 before its body would have
returned 404. Do not inspect arbitrary cause chains or translate every policy bug
into ACCESS_DENIED. Unexpected policy exceptions are technical 500 failures.

Use one shared classifier for in-method failures and Spring entrypoint/access-denied
handlers so REST and MVC agree. Preserve the public `{code, message}` shape; runtime
errors may carry an opaque correlation reference for safe logging, with no new
mandatory response field implied here. Browser login redirects need configured local
paths/validated return targets; no password or token values in logs or URLs.

Auth failures are terminal for this first slice: existing adapter errors policies
and Java hooks MUST NOT convert them into success, generic validation or a redisplay
loader. Default classifier handling precedes those generic policies. New declarative
auth error selectors/customization are a separate explicit design change. This avoids
silently widening existing `otherwise`/`domain rejection` contracts. A payment/limit/
quota denial is not authentication failure; their own taxonomy follows later.

Log stable safe decision/failure codes, operation identity and an opaque correlation
reference. Do not log parameters, resource/subject/account IDs by default, tokens,
private policy detail, raw backend exceptions or sensitive stack-trace messages.
Use proportionate logging for expected denials. See the plan's allowlisted logging
and seeded-secret tests; a log is not an authorization ledger.

## 8. Compatibility, tooling and acceptance

Generated Java callers keep the same execute parameter/result API. Keep Spring types
out of domain objects and ordinary implementations. Permission requirements MUST be
included in exported module usecase metadata so consumer tooling and startup audits
see them. Changing a requirement is security-relevant even when Java ABI is unchanged;
module compatibility checks must report it for review, not ignore it as body noise or
silently accept it as an additive behavior change. Exact metadata version/migration
rules need the module implementation review before adding a new kind.

Compiler diagnostics must cover unresolved/duplicate permissions, illegal scope inputs,
empty/invalid groups, malformed clause order and generated Java collisions. IDE/LSP
must support symbol completion, references/hover, module-source navigation and useful
errors before generation, including incomplete edits. Vocabulary documentation should
associate permission declarations by the existing exact-name convention when applicable.
No Java injection is added inside a pure requirement tree.

Before this contract becomes implemented, prove at least:

- AUTH-01: local/imported permission identity, enum member collisions and metadata.
- AUTH-02: all/any ordering/short-circuit, technical failure propagation, invalid syntax.
- AUTH-03: required/null inputs and allow/deny ordering relative to validates/body/result.
- AUTH-04: conditional nested calls, public outer/protected inner, actor/scope retention,
  rollback-only behavior, pooled-thread cleanup and no stale successful decision reuse.
- AUTH-05: personal/organization/application scopes, cross-account/nominal-ID isolation,
  hidden/missing resource denial and typed technical actors without anonymous-token bypass.
- AUTH-06: managed/direct entry, active transaction, MANDATORY policy reads and missing
  infrastructure/startup audit, without introducing duplicate authorization evaluation.
- AUTH-07: REST/MVC/filter denial consistency, CSRF/public forms, no unsafe redirect or
  redisplay, no generic domain rejection swallowing auth failures.
- AUTH-08: safe useful logs, no seeded sensitive values, metadata/live editor behavior
  and executable reference/tutorial cases once syntax is implemented.

These are test requirements, not tests already executed. A03/A04/A15/A18 in the
[release ledger](release-0.6.0.md) stay OPEN. D02/D03 are proposed here, not all resolved.
Dynamic limit/capacity/quota APIs, role/plan combination and complete SaaS features
remain mandatory later slices; this contract does not reduce 0.6.0's outcome.

## Review decisions and next step

Review explicitly: `.authorization` permission enums; the proposed clause order and
public/authenticated forms; omitted-clause compatibility plus strict SaaS startup
audit; `on` binding to required typed inputs; body-guard ordering; safe 403 resource
opacity; and terminal auth handling before existing adapter error customization.
These choices refine agreed principles but have not been silently approved by the
previous plan merge. Resolve objections before compiler implementation.

Next PR: a small executable Spring integration spike proving AUTH-03/04/06/07 against
the actual managed dependency versions. Only after it passes should implementation
PRs modify the production grammar, generator, metadata, runtime and user references.
Reference sources (not proof that the spike has run):
- [Spring Security method-security design](https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html)
- [Spring Security 6.4.2 authorization SPI](https://github.com/spring-projects/spring-security/blob/6.4.2/core/src/main/java/org/springframework/security/authorization/AuthorizationManager.java)
- [Spring proxy/self-invocation boundaries](https://docs.spring.io/spring-framework/reference/core/aop/proxying.html)
