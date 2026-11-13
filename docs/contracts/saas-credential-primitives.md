---
title: Implemented credential primitives
---
# Implemented credential primitives

Status: implemented in **0.6.0-SNAPSHOT**, following the accepted
[D14 policy](saas-identity-credentials.md). This is a library slice, not registration,
a login provider, a browser/session integration or complete abuse protection.

## Artifact and explicit construction

The Java helpers are in `org.vernac.saas.identity.security.credentials` inside the
existing optional `org.vernac:vernac-saas-identity-spring-security` artifact. There is
no new Vernac keyword, exported domain definition, automatic bean or HTTP endpoint.
The compiler/general runtime and ordinary project starter do not depend on them.

The artifact explicitly uses the managed Spring Security crypto implementation and
BouncyCastle `bcprov-jdk18on` 1.86. No cryptographic provider is registered globally by
these helpers. The pure `vernac-saas-identity` type library remains free of these
credential/security dependencies. Actual tests use Spring Security 6.4.2 and the
pinned BouncyCastle dependency, not a fake password encoder.

Construct a service once from an explicitly loaded offline corpus. No permissive
PasswordEncoder, empty corpus or always-accept fallback is supplied. Do not construct
services or reload corpus data per untrusted request. Admission/rate/concurrency limits
must surround these operations in the later public-flow implementation; the helpers
do not themselves implement the D14 distributed limits or four-hash concurrency gate.

## LoginAddress

LoginAddress.parse(String) validates the D14 ASCII email subset, preserves the delivery
spelling and derives the case-insensitive login key with Locale.ROOT. It trims only
U+0020 spaces at the two ends, rejects raw input over 16,384 UTF-16 units before copying,
and enforces the 254-byte total/64-byte local-part/DNS-label bounds after trimming.
It does not remove plus suffixes/dots, convert Unicode domains, query DNS or prove that
the address is deliverable. It is a technical ingress representation, not a generated
ValueObject, UserId or claim that email servers have case-insensitive local parts.

- deliveryAddress() is the preserved address for the trusted delivery boundary.
- loginKey() is the exact database lookup/uniqueness key.
- toString() is redacted. Never log the explicit accessors instead.

Invalid/unsupported input throws CredentialInputException, a DomainValidationException,
with a safe Code/message and no original input/cause. A public login adapter still must
map such input to its generic authentication outcome rather than disclose account facts.
Database uniqueness, concurrent registration and existing-account protection are later
persistence requirements; parsing alone does not enforce them.

## OfflinePasswordBlocklist

OfflinePasswordBlocklist.load(InputStream, Manifest) consumes **and closes** a supplied
local UTF-8 corpus. Manifest holds version, source URI, declared license, lowercase
SHA-256 of the exact resource bytes, and expected unique entry count. The source must
be an HTTPS URI without credentials, query or fragment; it is provenance metadata,
not a URL fetched by this code. A matching digest authenticates no publisher by itself;
review and pin the manifest/resource together in source control.

The format is one whole password per line, using BufferedReader line separators; a
final separator is optional. There is no comment syntax, trimming or case folding.
Entries use the same NFC/scalar validation as submitted passwords. NFC duplicates are
rejected; passwords containing line separators cannot be represented in this particular
corpus format. This is a finite common-password list, not exhaustive compromise detection.

Technical sanity bounds are 8 MiB of resource bytes, 1,000–100,000 unique entries, and
at least 100 entries meeting the current 15-code-point creation minimum. Individual
entries must satisfy the raw/normalized upper bounds. These floors reject empty,
short-only or illustrative tiny lists; they **do not establish content quality or license
validity**. Unknown encodings, malformed UTF-8, duplicates, mismatched counts/digests,
unsafe/unversioned metadata and read/close failures fail with a sanitized
CredentialConfigurationException(BLOCKLIST_INVALID), without a raw cause.

version() and entryCount() describe the loaded immutable snapshot. toString() and the
Manifest representation are redacted. There is no API exposing the entire stored list.
The synthetic fixture remains TEST-ONLY. The separate explicit `bundled()` factory
now supplies the [pinned real common-password corpus](saas-password-corpus.md), including
license/notice and reproducible provenance. It reads a bounded format-versioned classpath
manifest and applies the same loader; missing/corrupt resources fail closed. Applications
still explicitly construct the service; no bean or network update is introduced.

## CredentialPasswords

The explicit service exposes three operations:

| Operation | Implemented semantics |
| --- | --- |
| encode(CharSequence) | Apply creation rules and the loaded whole-secret blocklist, then generate a salted versioned Spring Argon2id encoding |
| matches(CharSequence, String) | Validate stored format/work, normalize submitted input and compare with Spring; do not reapply the current blocklist/creation minimum |
| performDummyVerification(CharSequence) | Comparable admitted unknown-subject work; returns no authentication decision, even for its non-secret known dummy text |

All inputs must be valid Unicode scalar sequences. Both encoding and matching use
NFC without trimming, case folding or whitespace collapsing. Creation requires 15–128
code points after NFC. Before normalization the helper permits at most 1,024 raw code
points, with an early 2,048-UTF-16-unit check. A supplementary character counts once.
Required null input fails with DomainValidationException; malformed/oversized login input
returns false without expensive verification. Ordinary public request body bounds remain
the responsibility of the future ingress and are not implemented by this library.

New encodings use `{vernac-argon2id-v1}` and PHC Argon2id version 19, memory 19,456 KiB,
2 iterations, parallelism 1, a random 16-byte salt and a 32-byte hash. Random salts mean
equal passwords must not be compared by comparing encoded strings. The library does not
implement Argon2 or equality itself. A shared instance has no per-subject mutable state;
concurrent callers still need a bounded admission gate before costly operations.

Before invoking Spring, stored input is capped at 256 characters and must have exactly
the declared ID/algorithm/version, canonical integer/base64 fields, a 16-byte salt and
32-byte hash. Approved verification bounds are memory 19,456–65,536 KiB, 2–4 iterations
and parallelism 1. Other IDs, plaintext, noop, malformed data and excessive work fail
technically with STORED_CREDENTIAL_INVALID, not false or a permissive fallback. Unexpected
runtime encoder failures become ENCODER_UNAVAILABLE without a raw cause. Do not convert
these technical failures into a successful login or an incorrect-password message.

No legacy algorithm registry, rehash orchestration or credential persistence is delivered.
A later approved encoding/policy upgrade must provide concurrency-safe storage changes;
these helpers do not overwrite a racing reset/disable. The dummy service value is not a
user credential or grant. Do not treat matches alone as complete authentication: eligibility,
current security epoch, admission, Spring authentication and invocation-time usecase checks
are still necessary. The existing live-status actor bridge remains unchanged.

Input exceptions expose only allowlisted codes/messages, and service representations are
redacted. Explicit returned encodings and caller-owned passwords remain sensitive; never
put them in events, DTO results or logs. NFC and Spring internally create Java strings;
this API does not promise total memory zeroization.

## Verification and remaining work

Tests exercise the real encoder and dependency, random salts/PHC shape, cross-Spring
verification, NFC/code-point and whitespace cases, safe rejection, corpus integrity and
bounded loading, unsupported/excessive stored work, non-granting dummy verification and
parallel independent subjects. CI/release checks require every suite and reject skips.

Pending: credentials/challenges/admission persistence,
atomic storage budgets/cleanup, protected mail/local inbox, account provisioning,
public registration, login/session/logout/recovery and complete UI/SaaS assembly.
D14 policy acceptance is not implementation acceptance. A01/A02 and final manual/full-SaaS
release gates remain OPEN.


## Private persistence format validation

CredentialPasswords.supportsEncoding delegates to the same bounded stored-PHC validation
used before Spring verification. It is a cheap format/work-policy predicate: no hashing,
password comparison, entropy/strength attestation or authentication grant. Unsupported,
missing, plaintext/no-op or corrupt formats return false. Private persisted encoding
invariants use this common parser rather than duplicating a PHC grammar. See the
[JDBC credential lifecycle](saas-identity-credentials-jdbc.md).


## Bounded expensive work

[BoundedCredentialPasswords](saas-credential-work.md) now supplies an explicit shared
application owner for encode/matches/dummy operations. It delegates to these existing
primitives and requires calls outside writer/resource scopes. The low-level API here
remains unbounded and does not install the standard SaaS admission pipeline by itself.
