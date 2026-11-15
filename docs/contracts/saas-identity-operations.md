---
title: Identity/Auth operations
---
# Identity/Auth operations

Identity/Auth is an explicitly assembled optional boundary. Module activation installs
private persistence and the selected directory provider; IdentityJdbcAuthentication,
IdentityAccounts, IdentityJourneys, their exact mailLifecycle bean, the Session filter
and the browser chain must be deliberately assembled by the application. The
[executable sample](../../examples/identity-auth/README.md) shows that composition.

## Startup and deployment

Run reviewed module migrations through the ordinary activation coordinator before
identity initialization. Apply the application's separately reviewed Spring Session
schema with deployment credentials. Runtime credentials must be NOSUPERUSER/NOBYPASSRLS
and have only required data privileges; no schema CREATE, TRUNCATE or migration-history
ownership. Startup rejects mismatched storage, incompatible persisted policy, unknown
roles, unavailable key configuration and an unsafe Session schema. Never bypass a failed
startup with an in-memory authentication/session store, a permissive evaluator or a
simulated mail provider on a public origin.

Every instance shares the exact labeled admission key/policy, delivery keyring and
role catalog. Supply cryptographically random independent 32-byte keys through your
secret manager. Keep time synchronized and use one authoritative database. Public
origins must be exact trusted HTTPS origins, without path/query/fragment/userinfo.
Forwarded client headers are not an admission identity; define trusted proxy/connector
limits and transport peer behavior explicitly. Bound connections, threads, body and
header sizes, idle/read time and TLS. Disable request bodies/headers and query strings
in sensitive access logs. Bearers are delivered only as fragments and submitted by
explicit CSRF-protected POST; no GET activates or consumes them.

## Delivery and restart

Register the exact SmartLifecycle returned by IdentityJourneys.mailLifecycle(). Its
bounded workers perform remote delivery outside the metadata writer. Durable leases,
attempts, retry deadlines, expiry and idempotency remain in PostgreSQL. A provider
timeout can mean remote acceptance; delivery therefore uses stable job correlation and
the documented retry semantics, not an exactly-once remote-send claim. Shutdown waits
for outstanding work through Spring's lifecycle callback; set deployment grace periods
accordingly. Lost leases cannot authorize stale database completion.

IdentityJourneys.operations() exposes an explicit internal-operator SPI: snapshot() reads
fixed aggregate READY/LEASED/CANCELLED mail counts, oldest READY age, pending registration,
challenge and invitation counts in a two-second read-only transaction. workerStatus()
reports process-local completed/unavailable pass counters for six fixed stages and
scheduler liveness, independently of database availability. No IDs or payloads are
returned. It installs no endpoint; authenticate and authorize any operator surface in
the application, and poll at an appropriate low frequency. Unavailable aggregate reads
throw the safe UNAVAILABLE reason rather than returning plausible zero counts. A pass
counter means the stage returned, not that remote delivery succeeded.

Monitor process readiness, worker liveness, fixed safe failure counters, bounded queue/
capacity utilization, oldest pending age, retry/terminal counts and shared-session
database availability. Do not attach address, subject/account/tenant IDs, session IDs,
token locators/verifiers, encrypted payloads, keys or raw exceptions to labels or logs.
The worker emits only the fixed background-unavailable message, at most once per minute.
A running scheduler alone does not prove successful delivery: alert on durable backlog
and worker errors, then inspect restricted operational aggregates. The library does not
publish an unauthenticated metrics or queue endpoint.

If mail is unavailable, keep neutral request responses and existing admitted jobs; use
the normal selected provider after restoration. Do not dump/decrypt the outbox into an
operator UI or logs. Expiry/cleanup and actionable budgets remain bounded. Never mark a
job accepted merely to clear a backlog. After restoring a database backup, reconcile
the external provider's possible prior acceptance and treat old delivered tokens as
potentially known; do not replay arbitrary sends or reuse an old deployment key without
reviewing persisted counters and grants.

## Keys and roles

Admission keys/policy are pinned to persisted bounded counters. Changing them is an
explicit migration/maintenance operation, not a rolling secret refresh or counter reset.
Take every writer out of service, preserve accounting, apply the reviewed migration,
then restart all instances with the same configuration. A mismatched rolling node must
fail startup. Do not silently discard active abuse counters to make startup succeed.

Delivery encryption supports an explicit active label and retained decryption keys.
Add the new key to every node's keyring first, then switch the active label consistently.
Retain old keys while any job/ciphertext/challenge lifetime still needs them; follow the
encryption-admission ledger rather than assuming a process restart made old ciphertext
irrelevant. Missing decryption material is a technical failure. The sample accepts one
active key for disposable acceptance; production rotation must use the keyring API.

Role catalogs are versioned, explicit, bounded application artifacts. Roll out compatible
catalog additions consistently. Before removing/renaming a role or permission, migrate
all membership and pending invitation assignments under a reviewed change; unknown
stored roles deliberately block startup instead of silently shrinking or increasing
rights. Ownership remains a separate finite metadata fact. Assigning product roles,
Spring authority strings, plan state or payment success never grants ownership or
bypasses product/object checks.

## Revocation and sessions

Browser sessions reside in PostgreSQL, with secure HttpOnly host-only cookies, SameSite
Lax and thirty-minute inactivity expiry. Password recovery/change advances the security
epoch; the current browser is invalidated only after successful commit and other
instances reject old stamps at their next checked request. Identity disable/closure
also requires the last-active-owner invariant. Membership/role changes are revalidated
for account selection and product invocation. No decision cache grants an indefinitely
valid membership. Revocation does not cancel already running database transactions.

Unavailable session or identity storage must fail closed. Do not retry a sensitive POST
as an automatic user action, silently switch to another account, accept an old password
after reset, or automatically log in after registration/recovery. Restoring technical
availability should retain a still-valid user's session and selection where the
underlying operation did not commit. Account closure and identity closure are lifecycle
transitions, not a promise to erase all retained product, audit or legal data.

## Local simulation

The optional local provider is bounded in-memory mail capture, not a public mailbox.
The sample's explicitly enabled local profile additionally enforces HTTPS, a loopback
transport peer and a separate random capability header before preview. No production
profile registers that route. There are no default credentials, a public inbox, URL
capabilities, arbitrary recipients exposed by global user search, or delivery-link logs.
