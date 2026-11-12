---
title: Private global identity admission gate
---
# Private global identity admission gate

The optional identity JDBC artifact implements the deployment-wide coarse request
budget from the [D14 policy](saas-identity-credentials.md#5-public-outcomes-and-abuse-protection):
initially **300 public identity requests per UTC minute across all instances combined**.
This is a private foundation, not an installed filter or complete abuse protection.
It accepts no address, IP, locator, secret, tenant or caller-provided window identifier.

## Fixed model and separate committed admission

One private global Vernac IdentityIngress aggregate has a fixed non-secret technical ID,
an epoch-minute number, used count and positive persisted limit. A fourth generated
module migration adds its table; earlier migration bytes remain unchanged. The module
exports are unchanged. Ordinary applications cannot import these definitions.

The package-private JdbcIdentityIngress helper uses the existing generated repository,
NamedParameterJdbcTemplate, Spring TransactionTemplate and the capacity helper's fixed
PostgreSQL transaction lock. There is no new crypto, authentication/session framework,
request log or external rate-limit dependency. This is a small fixed counter, not a
new general-purpose rate-limiter API.

Explicit startup initializes the capacity ledger first, then initializes this gate.
The gate reserves one LIMITER_KEYS slot and creates its singleton in the same transaction.
Concurrent initializers create one row/charge once. Restart preserves attempts. Configuration
drift fails rather than resetting or silently widening the limit. Explicit changeLimit
preserves usage, including usage above a lowered limit. The fixed row is reused forever;
requests never allocate new keys and no expiration job is needed for this singleton.
It remains charged until a future reviewed lifecycle removes it. Dynamic identifier/source
key storage, expiry, digest/key ownership and its remaining 49,999 slots are separate work.

Call tryAcquire **before** subject lookup, new keyed allocations, expensive hashing,
challenge processing and business transactions. It owns and commits a short Spring
transaction with explicit READ COMMITTED isolation and a five-second transaction timeout.
A denial writes no per-request record. Missing initialization, key-capacity denial at
startup or shared-store failure fail closed; raw database causes are not returned.
The helper does not reserve/refund business storage or execute a business callback.
An admitted attempt stays spent if later registration, delivery or provisioning rolls back.

Calling from an already active/synchronized transaction or with bound transaction resources
is rejected before opening the admission transaction. Do not invoke it inside a usecase
or while holding record/budget locks: suspending such a writer could deadlock on its own
locks. A wrong manager/DataSource combination fails the existing canonical writer checks.
The helper does not clear or invent a tenant context.

## Window semantics

The injected trusted server Clock is sampled after obtaining the database lock. The
window is floor(epochSeconds / 60), including instants before the Unix epoch. A later
minute resets usage; an equal or earlier minute does not. Backward clock movement cannot
refund attempts. Keep deployment clocks synchronized; a large forward jump can advance
the window, and this is not a defense against a compromised clock/database/operator.

The first N admissions in a window succeed; the next fails. Count addition checks the
limit first, so Long.MAX_VALUE cannot overflow. This is a fixed window: traffic can
consume N admissions just before a minute boundary and N just after it. It is not a
sliding-window or traffic-smoothing promise.

The internal Decision contains only admitted and retryAfterSeconds (zero on admission,
1–60 on denial). Retry-After is bounded guidance to the next local minute boundary,
not an availability promise after clock regression or shared-resource pressure.
The later HTTP adapter owns the public 429 response. Infrastructure failure is distinct
from ordinary throttling and must map to safe technical unavailability. No HTTP mapping,
public configuration properties or automatic Spring bean/filter is installed here.

## Evidence and remaining work

Real restricted-role PostgreSQL tests cover two Boot contexts racing for the final
admission, concurrent bootstrap, exact boundaries, restart, business rollback independence,
configuration drift/lowering, key-capacity saturation, wrong transaction boundaries,
permission failure/recovery and bounded singleton storage. Model tests cover maximum
counter arithmetic and backward time. CI/release gates require these proofs without skips.

Identifier/locator/source admission, trusted-proxy resolution, bounded password-work
concurrency, public ingress wiring and complete identity/mail cleanup remain mandatory.
This coarse shared cap alone is not sufficient public registration protection.
A01/A02 and whole-SaaS/final manual acceptance stay OPEN.
