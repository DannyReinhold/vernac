---
title: Private identity delivery envelope
---
# Private identity delivery envelope

Status: **private primitive implemented; durable admission, ledger, configuration and
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

## Attempt admission is a mandatory, unfinished integration gate

Every encryption calls the explicitly supplied `EncryptionAdmission` with a nonsecret
key ID and SHA-256 material fingerprint before invoking the library. The future durable
implementation must independently commit a nonrefundable attempt, bind ID/material and
reject material reuse under another ID across configuration changes and restarts. Enforce
at most 1,048,576 encryption attempts per key deployment-wide, including attempts whose
encryption or later business transaction fails. Retrying delivery decrypts an existing
job rather than minting another ciphertext. Unused/failed attempts are conservatively
spent; business rollback must not refund them.

There is no production admission implementation, process-local counter or default allow
in this slice. Therefore this primitive must not yet be wired to durable issuance. The
next PostgreSQL proof must cover last-slot concurrency, restart, failed encryption,
business rollback, key-ID drift/aliasing and bounded registry storage. Its independently
committed key-attempt lock must not acquire subject/business-capacity locks; prove the
lock ordering and transaction propagation before composition. A callback alone is not
evidence of a deployment-wide cryptographic usage bound. The concurrent unit test uses
an explicitly identified test double and makes no persistence claim.

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

Next: generated private key-admission state and a bounded durable attempt registry with
real two-instance/restart/rollback proofs. Then compose protected job persistence and
atomic challenge issuance, followed by leased workers and Brevo/Noop adapters. A11,
public registration, D05 provisioning and final SaaS release acceptance remain OPEN.
