---
title: Private keyed identity admission and bounded cleanup
---
# Private keyed identity admission and bounded cleanup

The optional identity JDBC artifact now implements the keyed counters from the
[D14 policy](saas-identity-credentials.md#5-public-outcomes-and-abuse-protection), in addition
to the [coarse ingress gate](saas-identity-ingress.md) and
[local password-work owner](saas-credential-work.md). It is private infrastructure,
not an installed HTTP filter, authentication grant or complete registration flow.

## Initial policy and trusted inputs

| Operation | Subject budget | Trusted source budget |
| --- | --- | --- |
| Login | 10 / 15 minutes per canonical login key | 60 / 15 minutes |
| Registration/resend/recovery mail combined | 3 / hour per canonical login key | 20 / hour |
| Challenge completion | 10 / 15 minutes per UUIDv4 locator | 60 / 15 minutes |

Private Rule configuration permits positive attempt limits and windows of 1–86,400
seconds. The complete immutable six-kind policy must agree across instances. Changing
it is not a live reset operation; drift fails closed. This is fixed UTC epoch-window
accounting with boundary bursts, not sliding windows or permanent account lockout.
The challenge resend cooldown is still a separate lifecycle rule.

login/mail receive the existing parsed LoginAddress, preserving its ASCII/case-folded
login-key contract without provider-specific normalization. Source inputs are InetAddress
instances from a trusted peer/proxy resolver. HMAC input uses their address bytes, not
DNS, textual IPv6 spelling, request headers or toString. This helper does not validate
arbitrary Forwarded/X-Forwarded-For headers or supply trusted-proxy configuration.

challenge accepts only a canonical lowercase UUIDv4 locator, never a secret/full token.
Missing/malformed locators still spend global/source attempts but allocate no locator
key. A null parsed address similarly charges global/source before returning INVALID_INPUT;
the later adapter owns safe field/authentication feedback. A bounded adapter must extract
any canonical locator even when the link secret is invalid so applicable locator limits
can still be spent. No caller-supplied purpose, timestamp or account target is accepted.

## Standard HMAC and stable installation configuration

The installation explicitly supplies a stable 32-byte HmacSHA256 SecretKey and a bounded
non-secret key label. Use securely generated deployment-managed material, not a sample
key, per-request/per-boot random key or plaintext environment dump. Constructors validate
format, not entropy. Missing, unavailable, wrong-algorithm or wrong-length keys fail with
safe technical codes and no retained causes. No key is generated/fetched automatically.
Non-exportable HSM keys are outside this initial adapter's supported key representation.

AdmissionDigests delegates to JCA Mac/HmacSHA256 and SecretKeySpec. Fixed versioned domain
prefixes and the six kind names separate source/login/mail/locator inputs. Only full
256-bit HMAC pseudonyms are persisted, with redacted value rendering. Raw addresses, IPs,
secrets and session IDs are never stored in these rows. The helper copies supplied key
material without modifying caller-owned arrays and creates a fresh Mac for each operation.
It introduces no custom cryptographic primitive or mail encryption mechanism.

The leading 128 digest bits provide a technical UUID primary-key locator for indexed
lookup. This ID is not a token/authorization capability; the full digest and kind must
match before a stored row is used. A locator collision fails technically instead of
merging allowances or accepting a different subject. Do not log generated rows or
pseudonymous IDs, use them as metric labels, or expose internal digests to clients.

A singleton configuration row persists key label plus a keyed policy check. Initialization
and every keyed/cleanup transaction verify it. Key, label or rule drift never silently
creates a fresh allowance namespace or resets active counters. This slice intentionally
has no online rotation/reset API. Any operational change requires an explicit coordinated
drain/migration procedure preserving the active-window policy. Do not clear metadata to
bypass configuration mismatch. D10 encrypted mail key ownership remains separate work.

## Transactions, attempts and bounded key storage

Initialize the capacity ledger and coarse gate explicitly, then initialize this helper.
The coarse singleton and keyed configuration each charge one LIMITER_KEYS slot; the
remaining default capacity is 49,998 dynamic rows. All keyed creation/deletion and its
reservation/release occur under the existing database budget lock, with normal generated
repositories, versions and metadata. Concurrent initialization charges the fixed row once.

Every request commits the coarse gate first. Coarse rejection does no keyed lookup or
allocation. A second short READ COMMITTED writer transaction handles keyed admission.
An already active transaction/synchronization/bound resource scope is rejected, rather
than suspended or refunded by later business work. Keyed-store failure cannot roll back
an already committed coarse attempt. No business identity lookup or expensive password
work happens here; no pending identity, credential, challenge or mail job is allocated.

Before key-existence-dependent branches, reserve two potential dynamic slots. Reuse
existing rows and release unused reservations in that same transaction. Thus key-capacity
pressure has the same technical outcome for existing/new keys and known/unknown addresses.
This conservative rule requires two spare slots even for traffic reusing existing keys;
near/full capacity can temporarily reject that traffic too. It never evicts active keys
or grants an unlimited existing-user exception. This availability tradeoff avoids a
capacity/existence distinction. Other business-storage budgets are independent.

The source is checked/spent first. A source denial allocates no subject key; a subject
denial still spends the admitted source attempt. Each kind/subject has one reusable
fixed-window row. Denied attempts do not extend its window or append request history.
Window advance resets its counter in place. A persisted time high-water mark is shared
by admission and cleanup, preventing backward clock movement/restart from recreating an
older allowance after physical cleanup. Keep server clocks synchronized; this is not a
defense against a compromised clock, key, database or operator.

Private Decision values distinguish global/source/subject throttling and invalid input,
with bounded retry guidance. They are not public response DTOs. The future adapter must
preserve D14 neutral subject-only responses, source/global throttling and sanitized 503
resource/storage failures, without exposing key existence, limits or occupancy. Safe
failures retain no raw driver/key causes and do not become incorrect-password outcomes.

## Primary-key cursor cleanup

cleanup accepts batches of 1–500. It seeks by primary-key UUID, selects at most that many
rows with ORDER BY id/LIMIT, and persists a shared sweep cursor. No full expired-row sort,
OFFSET scan or unmodeled SQL index is introduced. Generated primary-key indexes support
the seek; PostgreSQL chooses the physical plan. Selected rows are validated and only
expired rows are deleted through their generated version-aware repository. Release
counts follow actual successful deletes in the same transaction. Cleanup races with
admission/reset and other cleanup instances serialize; a renewed live window survives.

The result reports scanned/removed counts and whether that sweep reached its end. On
wrap, the next batch begins from the start; keys inserted behind the cursor are visited
on a following cycle. Passing time alone does not free a still-persisted row. Failed
cleanup rolls back deletion, accounting, high-water updates and cursor movement together.
The fixed configuration/global rows are retained and remain charged.

One batch is not a complete cleanup cycle. The standard assembly must schedule cleanup
at least every five minutes, run enough short batches to visit the retained key set, and
monitor sweep/expiry lag and capacity. At the default cap this means about 100 maximum-size
batches plus the wrap batch for a full set. Tune batch size/cadence and validate throughput
on the deployment database; a stalled/undersized cleaner can safely exhaust admission.
There is no automatic job/bean installed here. Indexed bounded scanning does not guarantee
fixed physical disk/WAL/backup size or instantaneous storage reclamation.

## Evidence and remaining work

Tests cover JCA key separation/copying/safe failures, model counter overflow and monotonic
time, real restricted-role PostgreSQL defaults, case-folded mail keys, purpose separation,
source charging after subject denial, malformed locators, restart/business rollback,
key/rule mismatch, uniform capacity pressure and bounded row counts. Two Boot contexts
race for a last attempt, configuration initialization, renewal vs cleanup and cleanup vs
cleanup. Permission failures roll back keyed allocation/cleanup/accounting while preserving
the earlier coarse attempt. A constructed UUID-locator collision proves full-digest
mismatch is rejected. Existing identity tables stay empty under admission-only traffic.
All required named CI/release proofs must execute without skips.

Login-key/pending registration ownership, protected delivery, account provisioning,
HTTP/source resolution, scheduled full cleanup and browser authentication/session/UI
remain unfinished. These counters plus the bounded password owner are not a complete
public abuse-defense assembly. A01/A02, whole-SaaS and final manual acceptance stay OPEN.
