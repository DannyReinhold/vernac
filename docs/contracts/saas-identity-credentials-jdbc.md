---
title: Private credential persistence and identity security epochs
---
# Private credential persistence and identity security epochs

The optional identity JDBC artifact now persists one private credential per existing
UserId, with the [Spring-backed credential policy](saas-credential-primitives.md) and
redacted [value representations](value-objects.md#string-representation). This is a
persistence/lifecycle slice of [D14](saas-identity-credentials.md), not a login provider,
password protocol, public registration/recovery flow or session implementation.

## Private model and dependencies

IdentityCredential uses UserId as its primary key and contains a mutable
IdentityPasswordEncoding. The encoding validates through the existing bounded PHC
format/work parser exposed as CredentialPasswords.supportsEncoding. It accepts only
approved stored encodings; it does not hash, compare plaintext, certify password
strength, or prove that a candidate encoding came from the required creation policy.
Trusted creation/change callers must obtain it from CredentialPasswords.encode after
admission, outside writer locks. Argon2id and password comparison remain Spring Security's.

Both the generated encoding value and containing credential representations redact the
hash. The private handoff Snapshot also renders only a constant redacted label. Its
explicit encoded getter is for trusted verification code; it is not an ordinary DTO,
event payload or public module export. Do not log SQL bind values or explicit getters.
A redacted toString does not imply serializer/debugger access is redacted or encrypted.
No plaintext password reaches these persistence helpers.

The JDBC artifact now has a compile/runtime dependency on the existing optional identity
Spring Security artifact (and its encoder/BouncyCastle dependencies), rather than a test-only
dependency. That dependency remains inside optional SaaS artifacts: general compiler/runtime
modules gain no SaaS/security dependency. No new beans, filters, endpoints or module exports
are installed. Vernac registers no security filter or authentication bean here. The host application
should still review its own Spring Boot classpath-dependent auto-configuration with
this runtime-dependency change.

## Writer ownership and operations

The package-private JdbcIdentityCredentials helper requires its canonical bound writer
transaction and takes the existing capacity lock before identity/credential access. It
then locks the existing identity row. Callers composing status/credential/challenge work
must follow budget-before-record lock order and keep transactions short. Never hash,
perform remote delivery/provisioning or suspend into REQUIRES_NEW while holding these locks.
The helper does not open/commit a transaction or refund independent security admission.

- enroll writes only for an existing pending identity without a credential. A duplicate,
  missing or ineligible subject returns false and never replaces the existing encoding.
  The caller must check the result and atomically compose successful challenge consumption,
  activation and D05 local provisioning. Enrollment alone cannot authenticate a pending user.
- snapshot returns a private immutable handoff only for an active identity with a valid
  stored credential. It captures subject, encoding, credential version and security epoch.
  Perform expensive Spring password verification after ending that short transaction.
- confirm rechecks active status, the current security epoch and credential version in
  the writer. It is a concurrency check for the verification handoff, not authentication
  or a promise that metadata cannot change after the transaction ends.
- replace requires a matching snapshot and writes a new encoding while advancing the
  identity epoch in the same transaction. Authorization/current-password or recovery-token
  proof belongs to the trusted caller; the helper is not a callable reset endpoint.
- rehash also requires the matching snapshot but leaves the security epoch unchanged.
  The caller must already have verified that the replacement encodes the same password
  using the approved encoder. This operation is not an alternative password-change route.

Updates retain the checked credential aggregate/version through save; they do not validate
one version and then reload a different version to overwrite. Stale handoffs return false.
Other optimistic/storage failures remain technical failures, not wrong-password results.
Callback/writer failures after mutation mark the bound transaction rollback-only even if
caught. A failed credential write cannot leave a committed epoch advance behind. Invalid
provided encodings and unavailable/corrupt storage have bounded technical error codes without
retaining raw SQL causes or encoded values. No automatic business retry is added.

One credential row is tied to one existing identity. These helpers do not create identities,
allocate login addresses or implement abandoned-registration cleanup. The pending registration,
mail and keyed-admission accounting gates remain mandatory before public registration ships.
Permanent credential retention/deletion and obsolete challenge/mail cancellation belong to
the remaining complete lifecycle, not an implicit exception to its storage/cleanup policy.

## Epoch semantics and migration

Identity now has a private nonnegative IdentitySecurityEpoch. Activation preserves it.
A credential change, successful disable or first closure advances it. Repeated closure
is idempotent for the epoch. Exhaustion at Long.MAX_VALUE fails rather than wrapping;
status/credential changes must not partially commit. A pure rehash changes credential
version but not epoch, so future sessions must use epoch for revocation, not credential version.

The fifth generated module migration adds the credential table and required identity
epoch column. Its tracked transformation initializes existing metadata rows to 0, the
starting generation of this newly introduced counter. Earlier artifacts contained no
credential rows or supported epoch-stamped authentication/session flow; this backfill
is not an inferred historical password-change count or an authentication grant. Existing
identity statuses/IDs are preserved. All four earlier migrations remain unchanged.
The source-controlled backfill input is specific to this migration; later generation must
not reuse it when its key is no longer relevant. Normal migration review obligations apply.

No session revocation mechanism is complete merely because an epoch exists. Current
IdentityDirectory/IdentityActorResolver status lookup remains the existing foundation;
it does not yet validate an authenticated epoch stamp. The later Spring authentication
and browser integration must issue and check trusted epoch stamps, reject legacy/missing
stamps as appropriate, and implement the D02 revocation follow-up. It must not expose
this private storage Snapshot as a principal or serialize generated aggregates into sessions.

## Evidence and remaining work

Tests use real restricted-role PostgreSQL and independent Boot contexts. They prove unique
concurrent enrollment, restart, redacted representations, Spring matching outside writes,
rehash/change distinctions, stale updates, change/disable races, epoch exhaustion,
malformed stored encodings and shared challenge/enrollment/activation rollback. A permission
failure after the epoch write rolls back both records even when caught. Original-schema
upgrade tests prove existing active identity data survives with epoch 0, and private-import
proofs reject the new definitions. CI/release gates require the new named proofs without skips.

Public registration/verification/recovery, login-key ownership/uniqueness, keyed admission,
protected durable mail, D05 provisioning, complete cleanup, authentication/session integration
and the SaaS UI/example remain unfinished. A01/A02, complete anti-abuse, whole-SaaS and final
manual acceptance gates stay OPEN. Next are those trusted identity/admission/delivery flows;
no public endpoint should wrap these primitives alone.

The [bounded Spring credential owner](saas-credential-work.md) is now available for
the expensive verification/encoding step outside these writer transactions. Real
PostgreSQL tests reject work in the writer and preserve the snapshot/confirm boundary.
