---
title: Private atomic identity mail requests
---
# Private atomic identity mail requests

Status: private registration/recovery issuance and protected job persistence implemented;
provider workers, cleanup scheduling and public HTTP/Spring integration OPEN. This is not
a complete mail journey or an authentication grant. Module exports remain unchanged.

## One business transaction

Acquire an encryption permit through the [durable admission gate](saas-identity-encryption-admission.md)
before starting business transactions. The trusted request helper then requires the
same bound writer used by identity capacity, registration and challenges. It first reserves
one potential actionable challenge, one unsent job and 65,536 bytes, before any identifier
existence lookup. Registration additionally uses its existing pending-slot gate before
ownership lookup. Recovery does not allocate a pending identity.

Known, unknown, disabled and otherwise ineligible addresses cannot bypass these shared
resource gates. Capacity failure is a fixed cause-free temporary-unavailability error;
ordinary successful return is neutral whether a job was created, cooldown applied or
there was no eligible owner. The future public assembly still must perform body/coarse/
keyed admission and map these outcomes according to D14; this helper is not a rate limiter.

Successful issuance stores/reuses the original pending owner, writes the purpose-bound
challenge verifier and saves the protected job together. The challenge table never
stores the recoverable token. A replacement reuses the existing charged challenge slot
and refunds the unused potential slot. Cooldown/ineligible results refund unused job,
byte and challenge reservations. A caught internal failure poisons the bound transaction;
identity, challenge, job and business accounting roll back together. The earlier key
attempt remains spent. Original delivery spelling and the seven-day registration deadline
are preserved on repeat requests.

## Protected content and exact accounting

A bounded internal content codec carries the original validated recipient and canonical
locator/secret token inside the [authenticated envelope](saas-identity-delivery-envelope.md).
Its decoder validates the address, token locator and complete framing. It rejects trailing,
malformed or mismatched content with a safe error. Content/envelope string representations
are redacted; raw links never enter generic events. Issuance clears temporary payload bytes
best-effort. Recoverable strings still require normal process-memory/diagnostic discipline.

A job binds subject, purpose, locator and expiry to its encrypted envelope. One database
unique constraint permits only one job per challenge locator. All persisted states,
including leased/cancelled work, remain charged by complete ASCII envelope length. After
encryption, reconcile the conservative byte reservation to the exact stored size in the
same transaction. Initial defaults remain 10,000 jobs AND 64 MiB, with a 64 KiB per-job cap.
There is no terminal job history or unbounded per-attempt table.

## Supersession, consumption and cancellation

Challenge replacement, consumption and expiry cleanup cancel associated mail under the
same budget/writer lock. READY work is physically deleted and its job/byte slots refunded
exactly once. LEASED work becomes CANCELLED and retains ciphertext and capacity until
worker settlement. Repeated cancellation does not refund it. This prevents a resend or
verification from freeing storage still owned by a worker. Removing queued mail and
refunding its bytes roll back if the challenge action/provisioning callback fails.

The model includes bounded attempt, due-time and lease fields for the next worker slice;
no worker is installed here. Infrastructure must check due time, owner/challenge eligibility,
lease expiry and fencing token before controlled transitions. Domain factories/internal
repositories are not an alternative public issuance interface. Sending remains outside the
business transaction, with bounded retries/transport attempts and final token validation.
Pending-registration cleanup and worker sweeps must also reclaim now-obsolete jobs;
scheduling/indexed durable cursor ownership remains an explicit next step.

The tenth generated migration adds only the private job table, its nanosecond range
checks and unique challenge key. Previous nine migrations/snapshots are unchanged.

## Evidence

Seven restricted PostgreSQL cases cover encrypted-only storage/original recipient/cooldown,
restart and atomic consumption/rollback erasure, uniform known/unknown count/byte pressure,
rollback and caught encryption failure, supersession retaining leased payloads, two distinct
Spring application contexts competing for the last backlog slot, and recovery eligibility.
No provider network calls occur. Existing challenge/identity/accounting suites still run.
The live schema checker replays all ten migrations against the expected schema.

Next: bounded, fenced dispatch/retry/cleanup and shared Brevo/Noop providers, then browser
flows with D05 provisioning and Spring Session. A11 and public registration remain OPEN.
