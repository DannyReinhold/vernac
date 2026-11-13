---
title: Identity request-time session validation
---
# Identity request-time session validation

IdentitySessionValidationFilter is an explicitly installed Spring OncePerRequestFilter.
Install it after Spring SecurityContextHolderFilter has loaded the authentication and
before handlers or tenant selection. Avoid a second servlet-container registration.
This slice supplies no automatic filter chain, session store or public endpoint.

For nominal IdentityPrincipal authentications it applies the
[fresh session trust check](saas-identity-session-trust.md). A stale/missing stamp,
inactive identity, epoch mismatch or twelve-hour absolute expiry clears the request
security context and invalidates an existing HttpSession before downstream handlers.
It never creates a session. The application's ordinary unauthenticated entry handling
then decides the login/401 outcome; the filter does not invent a redirect target.

A metadata/configuration failure terminates the dispatch with a fixed no-store 503.
It clears request authentication but retains the persisted session, so a transient
database outage does not log out every healthy browser. It logs no backend message
and grants no fallback access. Non-identity/technical principals retain their existing
application handling. Async/error dispatches request checks too; full application
dispatch ordering must be verified in later HTTP assembly tests.

Four required Spring mock-servlet tests prove valid/no-session behavior, stale/absolute
expiry invalidation before handlers, safe technical failure with session retention,
and unrelated/anonymous handling. These do not replace actual Spring Session JDBC,
cookie/fixation/CSRF/logout or full filter-chain tests.

Invocation-time SessionIdentityActorResolver remains necessary inside protected
usecases. A successful request check cannot freeze eligibility for later operations
or replace tenant/membership/permission checks.
