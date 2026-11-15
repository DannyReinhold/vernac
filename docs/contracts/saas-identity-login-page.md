---
title: Minimal CSRF-protected Identity login page
---
# Login page

The opt-in [browser chain](saas-identity-browser-chain.md) now supplies GET
`/identity/login` through `IdentityLoginPageFilter`, after Spring CSRF. It renders
an accessible HTML form with labeled email/password fields, password-manager autocomplete,
the masked Spring CSRF token and a validated hidden local return path. POST is handled
by the existing login adapter; GET never authenticates or changes Identity state.

The form action includes the servlet context path and a fixed Identity login path.
Visitor-supplied addresses/passwords are never reflected. Attribute values are escaped;
invalid return targets resolve to the configured fallback. Missing CSRF integration
fails closed with fixed 503. Responses are no-store, no-referrer and use a restrictive
CSP with same-origin form submission, no scripts/resources/base URL or framing.

This is a minimal English page with no application branding or translation framework.
It does not expose links to unfinished registration/recovery routes or claim their
acceptance. Container request-line/header limits remain required before GET parameter
parsing. Login failures retain the adapter's neutral fixed status/message; the page
never displays an existence-dependent error or includes visitor credentials in a URL.

Three additional mandatory tests cover escaped CSRF, context-path actions, no reflected
credentials, return-path injection, security headers, missing-CSRF failure and route
boundaries. The existing configured-chain and PostgreSQL session proofs now extract and
submit the actual HTML form's masked token rather than a synthetic page response.
