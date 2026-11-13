---
title: Private identity delivery envelope
---
# Private identity delivery envelope

Status: **private envelope and durable admission implemented; ledger, configuration and
sending integration OPEN**. Depends on the [Boot 4 baseline](../development/spring-boot-4-baseline.md)
and the accepted [D10 direction](../development/identity-delivery-0.6.0.md).

`IdentityDeliveryCrypto` is package-private handwritten infrastructure in identity-jdbc.
It is not a module export, Spring bean, public crypto API or sender. Activation does not
create keys, select a provider or write secrets. Brevo/Noop remain the planned shared
mail-port bindings; both use the same protected backlog lifecycle when integrated.

## Library and bounded key set

Spring Security's `AesGcmBytesEncryptor.withSecretKey(...).build()` supplies authenticated
encryption and randomized IVs. There is no Vernac cipher, IV generator, password KDF or
CBC fallback. Deployment supplies one active key ID and at most four distinct raw AES-256
keys, including retained read keys. IDs are bounded ASCII identifiers. Invalid/missing
configuration fails closed. Keys are copied; mutating the caller's byte array does not
change an encryptor. Temporary copies are cleared best-effort; this does not claim that
Java Strings/JCA objects or process memory can be reliably erased. KMS/nonexportable
keys require another adapter and are not implied by this one.

Rotate by installing a new read key everywhere, switching the active writer, draining
old jobs and finally removing the old read key. Old ciphertext remains readable only
while its matching read key is configured. Duplicate material under two IDs in the same
key set is rejected. Historical ID/material stability and cross-instance alias rejection
require the durable admission registry below; the in-memory key set cannot prove them.

## Durable attempt admission and permits

The [durable key registry](saas-identity-encryption-admission.md) independently commits
a nonrefundable attempt with the active key ID/material fingerprint. Call `prepare()`
outside all business transactions/resources. It returns an instance-bound, single-use
permit. Pass that permit to `seal(context, payload, permit)` inside the later business
transaction: sealing performs no nested admission transaction. Invalid/unused permits,
failed encryption and later business rollback never refund prepared attempts. Retrying
delivery decrypts the existing job rather than creating another ciphertext.

The private PostgreSQL gate enforces at most 1,048,576 attempts per key and 64 lifetime
key identities, retaining drift/alias checks across restart. Its fixed lock is distinct
from business-capacity/subject locks. No process-local production counter or default
allow exists. The envelope unit test still uses an explicitly identified admission test
double; real registry properties are established by its PostgreSQL integration cases.
Durable challenge/job composition and public issuance remain OPEN.

## Framing and context binding

The storage string is versioned `v1.<key-id>.<canonical-URL-base64-ciphertext>`, ASCII,
with a 65,536-byte hard maximum. Inside authenticated encryption a fixed, bounded frame
binds version, key ID, job UUID, subject UUID, purpose name, challenge locator UUID and
exact expiration (seconds and nanos). Opening compares that frame with authoritative
ledger/challenge context. Changing a row's public metadata or substituting another
job's ciphertext cannot yield its payload under a different expected context. Unknown
versions/keys, noncanonical encodings, tampering and truncation fail closed.

The opaque protected payload is 1–48,000 bytes, leaving room for framing, IV/tag and
base64 overhead within D14's 64 KiB envelope cap. The returned complete envelope size
must be charged to the future global payload budget. This lower payload maximum is
intentional; no security link is truncated. Input bounds apply before library decoding
and encryption. Expiration is checked against the supplied Clock, with equality expired.
Reconstruction does not renew the deadline. Issuance/worker code still must validate the
recipient, link format and live owner/challenge eligibility; successful decryption grants
no identity, role, tenant or challenge authority.

Opening returns bytes only through its explicit accessor. Context/key-ring string
representations are redacted. Every operational rejection is the same fixed,
cause-free `IDENTITY_DELIVERY_UNAVAILABLE`; third-party/configuration exceptions and
payload excerpts are not propagated. Callers must not log returned cleartext or complete
links and must clear temporary byte arrays where feasible. No generic event/outbox
integration is added. The ledger must erase ciphertext and release job/byte capacity
atomically when delivery becomes terminal.

## Evidence and next slice

Eight unit cases exercise library-backed round trips/reconstruction, every context field,
rotation/removal/rekeying, malformed/tampered/oversized envelopes, exact expiry, payload
bounds, configuration copying/rejection, redaction, sanitized admission failures and
concurrent callers sharing a test admission boundary. CI requires every named case.
The full identity-jdbc suite also runs with its restricted PostgreSQL integration cases.

Next: compose protected job persistence and atomic challenge issuance, followed by leased
workers and Brevo/Noop adapters. A11, public registration, D05 provisioning and final SaaS
release acceptance remain OPEN.
