---
title: Explicit Identity login HTTP adapter
---
# Login HTTP adapter

`IdentityLoginFilter` is an explicitly installed Spring authentication-processing
filter for one exact POST servlet path. It requires an AuthenticationManager,
SecurityContextRepository, SessionAuthenticationStrategy and validated local-return
policy. It fails closed if the bounded [form filter](saas-identity-http-input.md)
has not parsed the current request. Install it after CSRF and request-time session
validation, never as a second servlet-container filter. GET does not authenticate.

The request supplies address, password and optional returnTo. Transport-source metadata
comes only from the numeric servlet remote address: no DNS, form field, Host or
forwarded-header trust. A reverse proxy deployment must separately review its transport
source policy; without one, all requests share the proxy's source bucket. Do not enable
an arbitrary forwarding filter before this adapter and thereby relabel untrusted headers
as trusted remote metadata.

A successful manager result must be an authenticated nominal IdentityPrincipal with
server-created IdentitySessionStamp and no credentials. Standard Spring processing
invokes the supplied fixation strategy and explicitly saves the context through the
supplied repository. Success returns no-store 303 to the validated return path. The
adapter erases the transient input token on success and failure. It creates no roles,
account selection, tenant scope or saved-request redirect.

Wrong/unknown/ineligible subjects share a fixed 401. The JDBC provider exposes only
source/global admission denials through IdentityAuthenticationThrottledException;
these become fixed 429 with a bounded Retry-After. Subject throttling stays neutral.
All other Spring authentication failures become fixed 503 without exception details.
No password or backend cause is copied into a response.

Five mandatory tests run the actual Spring FilterChainProxy, security-context loading,
CSRF and session-ID strategy with mock servlet requests. They prove CSRF-before-provider,
pre-parse size rejection, fixation and explicit context persistence, transport source,
credential erasure, neutral failures, safe redirects and fail-closed assembly. They do
not establish cross-instance Spring Session JDBC storage, connector/proxy timeouts or
real browser journeys. Full application assembly remains in the review sequence.
