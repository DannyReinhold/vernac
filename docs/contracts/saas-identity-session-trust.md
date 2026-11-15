---
title: Identity session trust
---
# Identity session trust

This slice checks fresh eligibility and the security epoch of an existing Spring
Authentication. It does not install a login endpoint, HTTP session store, filter
chain, role assignment or tenant selection.

The server-created serializable IdentitySessionStamp is stored in Authentication
details and contains its version, epoch and original authentication instant.
IdentityPrincipal remains the technical UUID-only subject DTO. Domain objects and
credentials are not serialized into the session. All default string representations
are redacted.

IdentitySessionTrust requires an authenticated human IdentityPrincipal, the expected
stamp type, no future authentication time and an absolute age strictly below twelve
hours. Only then does it read current trusted metadata. Missing, inactive, disabled,
closed or changed-epoch identities deny access. A backend outage is a sanitized
technical authorization-unavailable failure, not anonymous eligibility or a grant.
There is no metadata cache. Recursive lookups fail closed and release their guard.

SessionIdentityActorResolver is an explicitly installed ActorResolver for this
browser-session path. It performs the fresh check on every invocation, grants no
roles and selects no tenant. Existing non-session integration APIs are unchanged.
JdbcIdentitySessionEpochs is the package-private JDBC implementation. It joins an
existing transaction or opens a short read-only transaction and exposes no hash or
account metadata.

Five required CI proofs cover Spring token serialization, absolute expiry, changed
epochs and sanitized failures, repeated actor resolution and real PostgreSQL
revocation across application restart. No migration is introduced.

Application assembly must still create this stamp after successful credential
authentication, install request-time checks before protected handlers, distinguish
technical failure from invalid sessions, and supply Spring Session JDBC, fixation
protection, CSRF, cookies, idle expiry and logout. These primitives alone do not
complete session authentication.
