---
title: Explicit HTTPS Identity browser security chain
---
# Identity browser chain

`IdentityBrowserSecurity.build` builds one explicitly requested Spring Security chain
from the application's HttpSecurity, AuthenticationManager, fresh IdentitySessionTrust
and validated return policy. It is not an auto-configuration or a replacement for
Vernac invocation-time actor, tenant and product-permission guards.

The chain rejects insecure transport with a fixed no-store 400, without a Host-derived
redirect. Deploy behind an explicitly trusted HTTPS connector/proxy policy. Forwarded
headers are not trusted by this code. It loads a standard HTTP-session context, checks
current identity/epoch before handlers, bounds Identity forms before CSRF, and processes
the explicit login adapter after CSRF. It disables default form login, HTTP Basic,
URL session rewriting and saved-request caching.

The two public paths are GET/POST `/identity/login`. All other requests require a nominal
authenticated IdentityPrincipal; the fresh session filter invalidates ineligible/stale
identities. Technical principals do not gain browser access. Authentication does not
supply product roles. The consuming application still supplies its login page and
handlers. This builder claims one chain; applications combining multiple chains must
review matcher/order coverage instead of assuming this chain covers another chain.

Successful login applies Spring's session-ID change and CSRF authentication strategies,
clearing the prelogin CSRF token before explicit security-context persistence. Logout
is only POST `/identity/logout` with a current CSRF token, invalidates the session and
returns no-store 204. GET does not log out. Anonymous protected requests return no-store
401 without creating a session or recording a saved-request return URL. CSRF/access
denials return no-store 403.

Five additional mandatory tests build the chain through a real @EnableWebSecurity
application context and use its FilterChainProxy. They cover ID rotation and persistence,
old-CSRF rejection, protected POST logout, stale versus temporarily unavailable metadata,
HTTPS-only behavior, no saved requests, and form/CSRF/provider ordering. The servlet
transport is mocked; cross-instance JDBC, cookies and real browser acceptance remain
separate. Configure the shared Session repository with the required thirty-minute idle
expiry before using this in a production application.
