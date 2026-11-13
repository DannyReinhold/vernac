---
title: Identity Spring authentication provider
---
# Identity Spring authentication provider

The explicit package-private JdbcIdentityAuthenticationProvider implements Spring
AuthenticationProvider. It is not automatically registered and installs no HTTP
endpoint or session store. It handles only standard UsernamePasswordAuthenticationToken
requests carrying the transient, redacted IdentityLoginInput. Ordinary Spring username
requests are left to other providers.

A trusted HTTP adapter must supply the transport peer address; arbitrary forwarded
headers or form inputs cannot establish that source. Invalid login addresses remain
eligible for global/source admission accounting but cannot reach a credential lookup.
The provider rejects ambient business transactions, tenant scopes and invocation actors.

The independently committed admission gate runs before subject lookup. Source/global
throttling has a bounded typed retry hint for the eventual HTTP adapter. Subject-only
throttling uses the same generic authentication failure as unknown, wrong-password or
ineligible identities. No permanent lockout or existence-dependent public message is added.

A short writer transaction reads a private active credential snapshot. Spring's bounded
password worker verifies outside the transaction; unknown/ineligible identities take the
dummy path. A second short writer transaction confirms both credential version and
security epoch before issuing a result. Existing concurrency proofs cover snapshot
replacement and disable races; a later revocation is caught by fresh session checks.
Storage, format, admission and worker failures remain cause-free technical errors.

The successful standard Spring token contains IdentityPrincipal, no credentials, empty
authorities and IdentitySessionStamp with the confirmed epoch and authentication time.
No account, role or tenant is inferred. Handled input tokens erase credentials on both
success and failure. Authentication alone does not grant product permissions.

Two required real PostgreSQL tests exercise the actual Spring Argon2 password path,
canonical login ownership, generic unknown/wrong/inactive failures, credential erasure,
epoch revocation across restart, persisted throttling and rejection of ambient
transactions before admission. They do not claim HTTP/CSRF or session-store coverage.

Still open: HTTP adapter assembly, fixation protection, Spring Session JDBC, cookies,
logout, request-time session invalidation, safe return targets and full public
registration/verification/recovery. Password encoding upgrades remain separate; this
slice does not silently rehash or change credentials at login.
