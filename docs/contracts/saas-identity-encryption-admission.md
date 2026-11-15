---
title: Private durable encryption admission
---
# Private durable encryption admission

Status: implemented private primitive, not public issuance or a sender. Builds on the
[delivery envelope](saas-identity-delivery-envelope.md). No module export/automatic bean
is added. The ninth generated migration adds only key identity/attempt and registry
state; the previous eight histories are unchanged. The database stores fingerprints,
IDs and usage, never AES key material.

## Durable attempt before business locks

`JdbcIdentityEncryptionAdmission` uses its own fixed transaction-scoped PostgreSQL lock,
a bound writer on the same DataSource, READ_COMMITTED and an independently committed
transaction. Initialization and admission reject any ambient transaction/resources.
They never acquire the business-capacity or subject lock. There is no suspended outer
writer or per-key lock allocation. Each attempt is committed before the caller obtains
business/subject locks; denied attempts return a fixed cause-free error.

`IdentityDeliveryCrypto.prepare()` charges that attempt and returns an instance-bound,
single-use Permit. `seal(context, payload, permit)` consumes the permit before calling
Spring's encryptor and performs no admission transaction itself. It can therefore join
later atomic issuance without lock inversion. A permit that is abandoned, rejected by
later business checks, used in a rolled-back transaction or followed by an encryption
failure is never refunded. A foreign or reused permit cannot encrypt. Retrying delivery
only decrypts the stored envelope. Trusted callers must apply coarse ingress/body checks
before preparing; prepare does not replace public rate or storage admission.

## Key identity, usage and bounded history

A private globally unique key name binds to exactly one globally unique SHA-256 material
fingerprint. Registration and the first attempt are atomic. Existing name/material drift
and material reuse under another name fail across restart and rotation. Both facts have
C-collated database unique constraints. Every key has a hard lifetime maximum of
1,048,576 attempts. There is no reset, increase, age-based release or automatic deletion.

The registry retains at most 64 lifetime key identities, including retired read/write
keys. A fixed singleton tracks occupancy; bounded consistency reads inspect at most 65
rows. Full history blocks new key registration but existing eligible keys continue.
Missing/corrupt registry accounting fails closed, never reconstructing zero over retained
history. Exhaustion requires an explicit reviewed operational/migration decision, not
silent recycling. After restoring an old database backup, rotate to fresh key material
before resuming issuance; restored counters cannot prove attempts made after that backup.

The in-memory crypto key set still permits at most four installed keys. Historical
fingerprints are not a source of keys and cannot decrypt a lost deployment secret.
Neither arbitrary domain factory use nor privileged SQL tampering is supported. Restore,
configuration, rotation and operational alert wiring remain assembly responsibilities.

## Proofs and remaining work

Restricted PostgreSQL cases cover uninitialized/damaged state, persistent usage after
abandoned permits/business rollback/restart, ambient-transaction rejection, two concurrent
instances contending for the final attempt, lifetime cap preservation, key drift/aliasing
and a full 64-key registry. Unit coverage also proves one-use/instance-bound permits.
CI requires every named proof. The schema checker replays all nine migrations against a
fresh expected schema; no existing application database is used.

The [atomic request helper](saas-identity-mail-requests.md) now charges and persists
protected jobs together with challenge issuance. Next:
then leases/retries/cleanup and shared Brevo/Noop provider integration. Public endpoints,
D05 account provisioning and Spring Session workflows remain separate; A11 is OPEN.
