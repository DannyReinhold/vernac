---
title: Identity mail delivery — D10 design proposal
---
# Identity mail delivery — D10 design proposal

Status: **PROPOSED for review; no delivery implementation or public route**.
Baseline: 2026-11-13, after canonical login-address ownership (PR #48).

This is the identity portion of D10, required by the accepted
[D14 credential policy](../contracts/saas-identity-credentials.md). Merging this
proposal accepts the direction below; it does not close system-email acceptance A11,
approve an unreviewed dependency upgrade. Invitation
and billing mail still need their own domain/template contracts. D05 account/owner
provisioning remains a separate prerequisite for public registration.

## Boundaries and existing components

Use the shared mail-provider port and explicit adapter selection described by the
[SaaS provider plan](saas-0.6.0-plan.md#ready-to-use-test-providers) and
[module binding contract](../contracts/modules.md). Brevo is the intended real mail
provider for this slice. The explicitly selected Noop/test provider performs no external
send and supplies the planned bounded local inbox, preview and deterministic failure/
recovery scenarios. A discard-only implementation cannot satisfy local registration E2E.
These are planned adapters, not existing implemented mail APIs.

Identity depends on the shared port, not on Brevo-specific DTOs or a second identity-only
transport. Keep provider DTOs behind the adapter boundary. Only the selected adapter is
instantiated and validated: Noop/test needs no Brevo credentials; selected Brevo requires
its configuration and must never silently fall back to Noop. The application must run
its complete local workflow without outbound provider calls. Neither adapter logs
rendered messages, addresses or links. Provider libraries and Spring integration remain
implementation choices inside the adapter; SMTP/JavaMailSender is not the required
transport or an additional 0.6.0 deliverable in this proposal.

A private identity delivery ledger owns challenge mail and its resource accounting.
It is not a general-purpose event payload and is not exported through the identity
module. Domain facts may report safe outcomes but cannot contain recoverable secrets.
An adapter accepts a prepared message through a narrow port; it has no identity-write
or tenant-selection authority. Reuse existing Vernac persistence, migrations and shared
capacity locking. Do not introduce a second generic outbox or provider DSL keywords.

## Protected envelope and dependency gate

Use authenticated AES-GCM encryption provided by Spring Security Crypto, with
externally supplied random AES-256 keys and library-generated random IVs. Vernac owns
serialization and lifecycle coordination, not a cipher, random generator or password
key derivation. Do not use password-based encryptors, CBC defaults, deterministic IVs,
or an encryption key derived from a login address, challenge verifier or admission key.

The implementation must first establish a reviewed, supported dependency baseline and
prove the selected API on that baseline. The currently managed 6.4.2 dependency must
not be treated as a recommendation for new deployments. Spring's
[2026 AES advisory](https://spring.io/security/cve-2026-47842/) describes unsafe CBC
constructor defaults and replacement APIs. Its explicit SecretKey/GCM migration path
informs this proposal; it does not justify silently upgrading just one transitive jar
or claiming all uses of GCM are affected. Prefer the replacement `AesGcmBytesEncryptor`
API on a compatible, maintained Spring stack. Dependency alignment and crypto-adapter
proof are the first implementation slice, before durable secret writes exist.

The protected payload contains its format version, job ID, key ID, UserId, challenge
purpose/locator, expiration, original delivery address and complete link secret.
Authenticate all of these together and compare the decrypted context with trusted
ledger/challenge metadata before rendering or sending. Substituting ciphertext from
another row must fail this comparison. Bounds apply before decoding/decryption as well
as afterward. Malformed, unknown-version, unauthenticated or mismatched payloads fail
closed with a safe aggregate diagnostic, never raw exception/payload logging.

Keys belong to deployment secret configuration, separate from limiter and password
configuration. Require one active write key and a bounded set of read keys with stable,
nonsecret IDs. A key ID cannot silently change material. Rotate by deploying the new
read key everywhere, switching the writer, draining old jobs, then removing the old key.
Missing keys stop affected delivery and new issuance where necessary; never send without
decryption or silently replace a configured key. Document a conservative per-key usage
limit and rotation procedure, accounting for concurrent instances and failed encryption
attempts, in the adapter proof. No process-local counter may claim a deployment-wide
nonce/usage guarantee. Nonexportable KMS/HSM keys require a separate adapter and are not
implied by the initial in-memory key ring.

## Atomic issuance and bounded storage

Registration, resend and recovery reserve every potentially needed resource before
existence-dependent branching. Include challenge slots, mail-job slots and a conservative
payload-byte reservation, even for a request that later needs no mail. On success,
reconcile to the exact stored envelope size; release unused reservations atomically.
Public capacity failure has the same sanitized temporary-unavailability result for known
and unknown identifiers. Independent abuse-attempt budgets remain spent after rollback.

The business transaction creates/reuses the pending owner, supersedes the prior current
challenge where applicable, writes the verifier and enqueues the protected job together.
A rollback cannot leave a usable challenge without its job, or a job without its owner.
Resend never renews the pending registration's seven-day deadline or changes its stored
recipient. Recovery must not change credentials or reactivate an identity at request time.
Lock ordering must match the existing writer/capacity boundary and be tested for two
instances; do not add nested independently committed business reservations.

The D14 defaults remain authoritative: 10,000 unsent jobs **and** 64 MiB total stored
envelope bytes, 64 KiB per job including overhead, no attachments. Leased/in-flight,
expired-but-not-deleted and retry rows remain charged. Encoding and envelope metadata
count toward the byte budget; reject an oversized complete payload instead of truncating.
Capacity must recover after restart from durable accounting, not process-local estimates.

## Dispatch, retry and erasure

A worker claims a bounded batch with a durable lease and fencing token in a short
transaction. It rechecks owner/challenge eligibility and expiry before dispatch. Send
outside the business transaction, using explicit finite connection/read/write timeouts
and a lease longer than the bounded transport attempt. A stale worker cannot acknowledge,
requeue or release a newer claim. Never release charged payload merely because its lease
expired; the new claimant still owns the same stored bytes.

Provider acceptance is not recipient delivery. A crash or timeout after remote acceptance
can produce a duplicate on retry; promise at-least-once attempts while eligible, not
exactly-once email. Reuse a stable delivery correlation ID as an operational aid,
without assuming provider deduplication. Apply bounded exponential backoff, a finite attempt ceiling and
an absolute cutoff at challenge expiry. Exhausted work is erased/accounted as terminal;
no unbounded dead-letter queue or durable per-attempt history is permitted.

Supersession, consumption, owner cleanup and expiry cancel queued work. They cannot
recall mail already accepted or atomically stop a transport call already in progress.
The challenge verifier is always the final authority, so a late message cannot restore
eligibility. Serialize lifecycle transitions with the relevant subject and accounting
locks; test the race rather than promising impossible cancellation.

After accepted submission or cancellation, erase ciphertext and release its bytes/job
slot exactly once in an atomic accounted transition. Initial identity delivery retains
no terminal delivery row; aggregate redacted metrics suffice. Any later terminal history
must share D14's 20,000-record/24-hour challenge-and-delivery budget. Polling and cleanup
need bounded, indexed batches, durable progress and multi-instance coordination at least
every five minutes. Index/query support must be established before claiming a scheduler
is production-ready; a full-table scan is not the scheduler design.

## Rendering and local operation

Render purpose-specific verification and recovery templates with escaped untrusted text.
Use the configured trusted HTTPS origin, never request Host/forwarded headers. Keep the
secret in the fragment/manual-entry flow required by D14; GET must not consume it.
Completion pages use no-store/no-referrer and no third-party assets. Template versions
and safe error categories may be observable; recipient/link/body are restricted data.

The local inbox is explicitly enabled, access-controlled and bounded by count, bytes and
retention. It may display the link to its authorized local operator; it is never ordinary
logging or an unlimited plaintext second database. Automated tests use the Noop/test provider and simulated Brevo responses, not real
customer addresses or production provider credentials.

## Review and implementation sequence

1. Review this D10 identity proposal and select a compatible maintained Spring baseline;
   prove library encryption, tamper/context rejection, key failure and rotation behavior.
2. Implement private ledger/envelope persistence and migration with two-instance real
   PostgreSQL proofs: last count/byte slots, rollback, restart, lease fencing, retry,
   supersession, cleanup and exactly-once resource release. Scan all observable outputs
   and generic events for seeded synthetic secrets.
3. Add bounded worker/cleanup scheduling and Brevo/Noop adapters. Simulate timeout after
   acceptance, crash before acknowledgement, expired leases and unavailable keys. Prove
   that duplicates do not mint or reactivate challenges and obsolete work cannot retry.
4. Compose registration/recovery only after D05 provisioning and the public browser/
   Spring Security contracts are resolved. Exercise the complete journey and update A11
   only with its actual broader acceptance evidence.

No endpoint, dependency, migration, key or provider setting changes in this proposal.
The next implementation PR should begin at item 1, not bypass the dependency gate with
handwritten cryptography or mark the whole mail feature delivered.
