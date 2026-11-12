---
title: Actor and authorization runtime foundation
---
# Actor and authorization runtime foundation

Status: **Java runtime/Spring foundation implemented in 0.6.0-SNAPSHOT**.
The [local application language contract](usecase-authorization.md) now adds permission
and requires declarations with generated entry guards. Module metadata, advanced IDE
support and the SaaS reachable-usecase audit remain OPEN. Existing usecases without a
clause do not become protected automatically. This document specifies the Java SPI.

The supported application platform is Java/Spring Boot. The core APIs live in
`org.vernac.runtime.authorization` in `vernac-runtime`; the explicitly selected Spring
adapter lives in its `.spring` package. This split is not a framework portability claim.

## Actor, tenant, permission and resource

| API | Contract |
| --- | --- |
| `VerifiedActor(realm, subject, kind)` | Trusted subject identity and HUMAN/TECHNICAL kind. The realm prevents unrelated issuers' equal subject strings colliding. It is not credentials, membership or a grant. |
| `ActorResolver.resolve()` | Returns an Optional verified enabled actor. Empty means unauthenticated; failed verification infrastructure must fail technically, not silently return empty. |
| `PermissionKey` | Qualified namespace/declaration/member identity. Requirements snapshot `qualifiedName()` so a mutable implementation cannot change the tree later. Declaring a key grants nothing. |
| `ResourceReference.of(IdType.class, id)` | Required, nominal ID/VO input. It preserves the real Java type and value, never its toString. Equal UUIDs in distinct ID classes remain distinct. |
| `PermissionRequest` | Operation identity, verified actor, selected TenantId, permission key and nullable typed resource. No raw request, Authentication or credentials. |
| `PermissionEvaluator.evaluate(request)` | Pure ALLOW/DENY decision. DENY maps to safe ACCESS_DENIED. No business mutation, reservation, usage consumption or payment call. |

The selected tenant is taken from `TenantContext`; a resource does not choose or
change it. The current permission SPI requires a verified tenant for permission leaves.
No missing/null tenant is interpreted as an unrestricted global grant. Public and
standalone authenticated operations can run without tenant selection. A distinct
verified global application-policy scope is not supplied by this increment.

The runtime type carrier is a Java integration SPI. The upcoming compiler must restrict
`on` to required named ID/VO inputs; constructing a ResourceReference from arbitrary
Java does not prove that it is a valid language input or an ownership claim. Referenced
VOs/IDs must retain their immutable domain contract. No aggregate/request is inferred
from an ID, and no tenant selection or repository query is inferred automatically.

## Ordered immutable requirements

`AuthorizationRequirement.Mode.PUBLIC` and `.AUTHENTICATED` are standalone forms.
Use `permission(key[, resource])`, `all(...)` and `any(...)` for permission trees.
Groups MUST be nonempty, MUST NOT contain standalone modes and MUST NOT repeat an
exact sibling permission/resource pair. Lists are copied and exposed immutably.

Evaluation retains declaration order:

- `all`: stop on first DENY; allow only if every visited child allows.
- `any`: stop on first ALLOW; deny if every visited child denies.
- A technical failure aborts immediately, even in `any`. Null/abstaining results and
  unexpected provider exceptions are configuration failures, never ALLOW or DENY.

There is no successful-decision cache across invocations. A reached inner operation
receives a new decision for its own operation/resource. Unreached branches or inner
usecases are not preauthorized. Evaluation is an invocation-time decision; it does not
promise instantaneous cross-process revocation or replace database tenant isolation.

## Guard and invocation lifetime

Infrastructure can use this Java fragment inside a managed REQUIRED method, after its
required-input checks and before validates/body/result processing:

```java
DomainChecks.requireNonNull(invoiceId, "ExportInvoice.invoiceId");
var requirement = AuthorizationRequirement.permission(
    PermissionKey.of("example.billing.InvoicePermissions.EXPORT"),
    ResourceReference.of(InvoiceId.class, invoiceId));
try (var invocation = authorization.enter("example.billing.ExportInvoice", requirement)) {
    // Validate parameters, run the operation and check its result here.
}
```

This is an infrastructure fragment, not new Vernac syntax or a complete application.
The existing [executable Spring/PostgreSQL fixtures](../development/authorization-entry-spike.md)
exercise the production guard with proxied entrypoints. The compiler now emits this entry boundary for explicit protected local usecases.
An omitted clause retains existing behavior; requires public uses an optional provider.

`AuthorizationGuard.enter` MUST require a real synchronized Spring transaction for
protected entry. It does not create one. No actor/scope data lookup or evaluator may
run after that prerequisite fails. Required-null domain checks remain the entrypoint's
responsibility and continue to raise DomainValidationException before authorization.

The guard captures immutable actor identity and selected tenant. Nested frames MUST
retain both; changing the subject, issuer, actor kind or tenant fails technically.
A selected tenant is pinned for protected invocation but JDBC access still requires
the existing physical DataSource/connection coordinator. The guard is not SQL binding,
a membership resolver or an authorization token for an arbitrary connection.

The returned `Invocation` MUST be closed, normally with try-with-resources. Frames
are thread-owned and LIFO; double-close on the owning thread is harmless. Failed entry
cleans its frame. Closing restores an outer frame without unpinning the transaction.
`currentActor()` is a read-only infrastructure view, not an identity setter. No actor
context is implicitly inherited by executor threads or persisted into event payloads.
Technical workers need explicit trusted authentication/mapping; there is no ADMIN fallback.

An exception from a reached proxied inner REQUIRED call retains Spring's rollback-only
semantics even if the caller catches it. This does not restore Java aggregate state,
create a savepoint, retry work or commit independently. See the [usecase contract](usecases.md).
Calling static companions directly remains an unsupported bypass, not a security sandbox.

## Explicit Spring configuration

Import `VernacAuthorizationConfiguration` intentionally. It creates one AuthorizationGuard
only when exactly one ActorResolver and one PermissionEvaluator are bound. Missing or
multiple bindings MUST fail startup; no default evaluator grants access. This is provider
binding validation, not the future audit of every reachable/exposed usecase.

Use Spring Security in the application when selecting `SpringSecurityActorResolver`.
The `spring-security-core` dependency is provided by the application, not installed
transitively into every pre-existing Vernac app. For a Boot web application, use its
normal Spring Security dependencies and retain its own SecurityFilterChain.

Example configuration fragment (the two policy interfaces and their implementations
belong to the application/module; they are not generated Vernac APIs):

```java
@Configuration(proxyBeanMethods = false)
@Import(VernacAuthorizationConfiguration.class)
class AuthorizationSetup {
    @Bean ActorResolver actors(TrustedSubjects subjects) {
        return new SpringSecurityActorResolver(subjects::resolveEnabled);
    }
    @Bean PermissionEvaluator permissions(InvoicePolicies policies) {
        return policies::evaluate;
    }
    interface TrustedSubjects {
        Optional<VerifiedActor> resolveEnabled(Authentication authentication);
    }
    interface InvoicePolicies {
        PermissionDecision evaluate(PermissionRequest request);
    }
}
```

The Spring resolver rejects missing, unauthenticated and AnonymousAuthenticationToken
values before invoking the mapper. An anonymous token's `isAuthenticated()` flag alone
MUST NOT establish an actor. Disabled, locked, expired or credential-expired UserDetails
principals are rejected as well. For other principal types and current account state,
the trusted mapper MUST verify the configured principal/issuer and enabled subject;
it MUST map technical actors explicitly. Do not infer trust from a principal display
name, request header, role claim or plan. This adapter does not implement registration,
login, session/token validation, tenant membership or continuing account-state refresh.

The configuration installs no filter chain, authentication mechanism or permission
interceptor. Spring URL/login/CSRF protection remains complementary to the body guard.
A policy reading a MANDATORY repository shares the existing REQUIRED transaction.
Arbitrary Spring AuthorizationManager logic can be adapted inside the application
policy, but no authority-string mapping, role precedence or subscription benefit is
inferred automatically.

## Failures and HTTP/MVC handling

AuthorizationException is unchecked and outside the domain/port rejection hierarchies.
It carries only a safe code; raw backend messages/causes/suppressed exceptions cannot
be attached to its public diagnostic. Unexpected actor/evaluator runtime exceptions
become configuration failures. Only explicitly classified configuration/unavailable
provider failures are retained; throwing a denial exception from a provider is not
an alternative to returning DENY.

| Code | HTTP default | Meaning |
| --- | --- | --- |
| AUTHENTICATION_REQUIRED | 401 | No verified enabled actor |
| ACCESS_DENIED | 403 | Evaluated permission requirement denied |
| SCOPE_REQUIRED | 403 | Permission requires verified tenant selection |
| AUTHORIZATION_CONFIGURATION_ERROR | 500 | Missing transaction, invalid context, broken/abstaining provider or binding |
| AUTHORIZATION_UNAVAILABLE | 503 | Explicit temporary verification/policy infrastructure failure |

`InboundHttpErrors` classifies these before generic declarative/Java error handlers.
Only direct exceptions and boundary-owned invocation wrappers are recognized; arbitrary
cause chains are not reinterpreted. Auth errors MUST NOT become generic validation,
domain rejection, custom success or a different status via `withStatus`.

Generated MVC failure handling checks authorization before endpoint/adapter policies
or Java hooks. MvcResponses also treats it as terminal: no redisplay loader, echoed
input map or previously supplied model. It returns the configured error view and safe
code/message. HTTP/MVC helpers log technical auth codes without raw exception,
endpoint, actor/resource/tenant data. Expected denials do not generate severe logs.
Application-owned filter entrypoint/access-denied handlers can use the same classifier.
These helpers do not install those handlers or implement browser login redirects,
validated return targets, authentication challenges or a complete audit/monitoring system.

A normal aggregate byId absence after authorization retains 404. Resource policies
must avoid revealing hidden/missing resource facts through different public denials.
Quotas, payment rejections and business limits are not authentication failures.

## Evidence and remaining work

Unit tests cover tree semantics and immutability, typed requests, transaction/actor/
scope prerequisites, frame nesting/thread cleanup, technical failures/redacted metadata,
Spring principal classification/provider startup binding, and terminal HTTP/MVC helpers.
The existing nine authorization PostgreSQL/MockMvc scenarios now delegate to these
production APIs; the handwritten parallel guard is removed. Generated MVC execution
checks that even a throwing custom error hook is bypassed for authorization and that
no loader runs. Generated HTTP policy tests preserve the auth defaults.

Still OPEN: permission/module metadata and compatibility; advanced editor support; reachable-usecase audits; actual identity/
membership/role/subscription modules; safe operational correlation; full SaaS end-to-end
acceptance. An existing operation with no requires clause is not secured merely by upgrading.
A03/A04/A19/A20 and the [0.6.0 release gates](../development/release-0.6.0.md) remain OPEN.
