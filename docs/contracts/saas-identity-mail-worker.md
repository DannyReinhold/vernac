---
title: Private fenced identity mail dispatch
---
# Private fenced identity mail dispatch

Status: private dispatch/maintenance engine and explicitly constructed polling lifecycle
implemented; shared Brevo/Noop adapter wiring and public Identity/Auth assembly OPEN.
No worker bean is installed by merely adding/activating the identity JDBC provider.

## Claim, submit, settle

`JdbcIdentityMailWorker` initializes four fixed durable cursor rows: dispatch, mail
cleanup, challenge cleanup and abandoned registration cleanup. Each cursor uses a
bounded primary-key seek, never a full-table expiration sort. At most 500 IDs are
inspected per call. Cursor movement and controlled mutations commit together under
the existing capacity/writer lock. Cursors survive restart; initialization does not
reset them. The eleventh migration adds only this private cursor table. Earlier
migrations/snapshots are unchanged.

Claiming advances the cursor, checks trusted identity/pending/challenge eligibility,
expiry, due time, read-key availability and attempt ceiling, then records a fresh lease
UUID, 45-second lease and incremented attempt count. A missing read key pauses that
job without spending attempts; expiry cleanup still reclaims it. Every persisted state
remains charged. Disabled/closed/missing identities, superseded/consumed challenges and
expired pending lifetimes cannot authorize a send.

After commit, recheck ownership/eligibility, authenticate/decode the envelope and compare
the original recipient and token verifier with current trusted records. The sender is
invoked outside all database transactions. It must bound each transport attempt to at
most 30 seconds, perform no hidden retries and not acquire business-write authority.
The upcoming provider bridge must prove this contract with local transport fixtures;
a caller-supplied unbounded sender is not a supported production integration.

Settlement opens a fresh short transaction and requires the same lease token. A stale
or duplicate acknowledgement cannot delete newer work or refund capacity twice.
Acceptance deletes ciphertext and releases exact bytes/job count. Failure retries with
60-second exponential backoff capped at 30 minutes, at most eight claimed attempts and
never beyond eligibility/expiry. A crash before sending still spends its claimed attempt.
An exhausted/cancelled/obsolete active lease keeps its payload charged until its owner
settles or its lease expires. Reclamation after a crash relies on the bounded transport
contract; it is not a proof that arbitrary third-party Java callbacks have stopped.

Provider acceptance is not recipient delivery. Timeout/crash after remote acceptance
may duplicate a message. A lease token fences database settlement, not remote effects;
final challenge validation remains authoritative. No exactly-once mail guarantee or
per-attempt durable history is added. Corrupt payloads never reach the sender and remain
subject to finite retry/expiry rules.

## Maintenance and lifecycle

Mail, challenge and pending-registration cleanup share the writer/accounting boundary.
Pending cleanup now also cancels its associated mail before deleting dependencies.
READY ciphertext is erased/refunded; leased work is marked CANCELLED and retained until
settlement. Active/disabled/closed identities are never purged as abandoned registrations.
A bad invariant fails closed rather than advancing a cursor past unaccounted work.

The optional `IdentityMailPolling` SmartLifecycle has two dedicated daemon threads:
one dispatch loop and one maintenance loop. It polls every second, scans 50 jobs for
dispatch and up to 500 records per maintenance cursor, and requires an explicit callback
for the existing durable keyed-admission cleanup. There is no default allow/no-op
production cleanup. The lifecycle is only a constructible primitive until the selected
provider/Auth assembly wires it after migration and key/config validation.

New threads do not inherit inheritable thread locals. Worker entry rejects ambient
transactions/resources, Spring Authentication, tenant scope or usecase actor context;
it never adopts browser credentials or a fabricated tenant. Background errors emit only
a fixed aggregate message, rate-limited to one per minute across loops. No job IDs,
addresses, ciphertext, tokens or third-party exceptions are logged.

Start initializes cursors without resetting progress. Stop prevents new ticks and lets
ongoing attempts settle before its SmartLifecycle completion callback; the owning Boot
assembly must allow a shutdown phase long enough for bounded transport/DB work. Separate
maintenance continues while dispatch is waiting on its provider. Provider/storage stalls
can still delay progress: the assembly must expose aggregate cleanup lag/pressure and
validate polling capacity against configured limits before claiming the D14 five-minute
maintenance objective. This slice does not claim an unconditional scheduling SLA under
backend failure or implement the final monitoring/operational acceptance.

## Proofs

Eight restricted PostgreSQL cases establish outside-transaction submission and restart,
byte/job release, backoff without re-encryption, crashed/stale claims and final-attempt
reclamation, missing keys and expiry/pending cleanup, cancellation retaining leased
payloads, two application contexts contending for one live lease, contaminated-context
rejection, graceful stop and no send for corrupted/closed state. Synthetic senders perform
no real provider calls. Current identity-jdbc reports contain 18 unit and 63 integration
cases, all passing without skips/errors/failures; every named worker case is mandatory
in CI. Eleven-migration live schema-check matches expected ownership.

Next: shared MailProvider module and concrete Brevo/local providers with transport and
local-inbox bounds, then explicit Auth assembly, D05 provisioning, login/session and
verification/recovery UI. A11/public registration/final SaaS acceptance remain OPEN.
