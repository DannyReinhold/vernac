---
title: SaaS identity credential and browser-flow policy
---
# SaaS identity credential and browser-flow policy

Status: **D14 policy accepted for 0.6.0** through PR #33. Implementation is partial:
[credential primitives](saas-credential-primitives.md) and
[private credential persistence](saas-identity-credentials-jdbc.md) are available, while
public flows, mail, keyed admission/complete cleanup and authentication/session integration
remain unfinished. The [storage-capacity primitive](saas-identity-capacity.md) is
implemented; real-row accounting and cleanup integration are still required.
Private [challenge persistence](saas-identity-challenges.md) now couples actual records
to the budget in the writer transaction; it does not complete any public flow.
This policy introduces no new language keywords or automatic HTTP/Spring configuration.
Acceptance of policy is not acceptance of registration/authentication or any SaaS gate.

The existing [identity contracts](saas-identity-foundation.md) and
[optional JDBC directory](saas-identity-jdbc.md) remain the implemented foundations.
This policy belongs to optional SaaS modules and their Spring integration, not the
compiler or general runtime. Applications without the SaaS assembly are unaffected.

## 1. First supported journey

The standard browser journey uses a verified email login identifier and a password.
MFA, passkeys and federated login are later additions, not silently included guarantees.

1. The visitor submits an email address through a CSRF-protected registration form.
   There is **no password field at this step**. Persist a pending identity, a
   purpose-bound verification challenge and a protected mail-delivery request together.
2. A link opens a confirmation page. GET must not consume the challenge, activate an
   identity or create an authenticated session. The page explains the action and lets
   the visitor choose a password, including password-manager/autofill support.
3. The explicit POST validates the challenge and proposed password. It atomically
   consumes the challenge, stores the credential, activates the identity and provisions
   the personal account, tenant mapping and first owner membership. Concurrent submits
   have one winner. A provisioning failure rolls back all these local changes.
4. Redirect to normal login. Verification does not create an authenticated session.

This email-first choice prevents an attacker from registering someone else's address
with an attacker-known password and waiting for its real owner to activate it. A
verification action must never activate a previously supplied unknown password.
Pending identities cannot authenticate, select accounts or obtain product access.

The later provisioning dependency is mandatory for the complete journey. Until accounts
and memberships exist, tests may prove challenge/credential primitives but MUST NOT
label registration end-to-end complete or publish a partially working standard flow.

## 2. Email delivery address and login key

Keep two explicit values: the address used for delivery and its stable login lookup key.
UserId remains the identity; an email address is not an aggregate ID or TenantId.

The first policy deliberately supports ASCII email addresses, including explicitly
supplied ASCII/Punycode domains. Internationalized local parts and conversion of Unicode
domains are not supported in this first version and must produce a clear validation
message. They must not be silently transliterated. This is a documented scope boundary,
not a claim that such addresses are invalid email generally.

- Remove U+0020 spaces at the two input ends only. Reject remaining whitespace/control
  characters, non-ASCII input, display names, comments, quoted local parts and domain
  literals. Do not parse an address out of an arbitrary mailbox header.
- Require one at-sign; an ASCII dot-atom local part of at most 64 bytes; and a DNS-style
  domain of at least two labels. Each label is 1–63 letters/digits/hyphens, with no edge
  hyphen; the full domain is at most 253 bytes. The complete address is at most 254 bytes.
- In the local part, allow ASCII letters/digits and `!#$%&'*+-/=?^_` plus the backtick,
  braces, vertical bar and tilde; dots separate nonempty atoms. No leading/trailing or
  consecutive dots. The address is syntactic input, not proof of deliverability.
- Preserve the resulting spelling for delivery. The login key is the entire resulting
  address lowercased using Locale.ROOT. This is an intentional **case-insensitive SaaS
  login policy**, not a claim that all mail servers treat local parts as equivalent.
- Do not remove plus suffixes or dots and do not apply provider-specific alias rules.
  The ASCII key is compared with exact database equality and a uniqueness constraint.

| Input | Delivery address | Login key/result |
| --- | --- | --- |
| ` Alice+Reports@Example.COM ` | `Alice+Reports@Example.COM` | `alice+reports@example.com` |
| `alice+reports@example.com` | As supplied | Same key; no second identity |
| `alice@example.com` | As supplied | Different key from the plus-address |
| `a.lice@example.com` | As supplied | Different key; no dot folding |
| `Alice <alice@example.com>` | None | Invalid input |
| `alice@bücher.example` | None | Unsupported non-ASCII domain; explain ASCII/Punycode input |

A conflicting registration MUST NOT replace the delivery address, credential, verification
state or ownership of an existing identity. An already active/disabled/closed identity
must not receive a registration challenge that can activate or reset it. Recovery is a
separate purpose. A pending identity may request a superseding challenge under the resend
rules. No second UserId/personal account is created because two requests race.

Any future canonicalization change requires an explicit migration, collision report and
reviewed resolution. Never silently reinterpret existing keys during login or upgrade.
Verified-address changes and identifier reuse after closure need their own future flow;
closure does not automatically release a login key for a different person.

## 3. Password policy and storage

The baseline is a password-manager-friendly single-factor policy, informed by NIST's
password guidance. It is not a NIST assurance-level or regulatory compliance claim.

| Rule | Baseline |
| --- | --- |
| Normalization | Valid Unicode scalar input, NFC, identically for creation and comparison |
| Length | 15–128 Unicode code points after NFC; reject invalid surrogate sequences |
| Other transformations | No trimming, case folding, whitespace collapsing or truncation |
| Character composition | No uppercase/digit/symbol mixture rule; spaces and Unicode supported |
| Password changes | No arbitrary periodic expiry; reset on compromise/recovery or explicit user change |
| Weak-secret screening | Required offline, versioned common/compromised-password blocklist at creation/change |
| Encoder | Spring Security Argon2PasswordEncoder, Argon2id version 19 |
| Initial costs | 19,456 KiB memory, 2 iterations, parallelism 1; 16-byte random salt, 32-byte hash |
| Storage envelope | Explicit versioned DelegatingPasswordEncoder ID; no plaintext/no-op fallback |

Count code points, not UTF-16 code units. NFC is an explicit credential-boundary rule;
it does not change ordinary Vernac value equality or introduce general model normalization.
For example, composed/decomposed equivalent Unicode password spellings match after NFC;
leading/trailing spaces remain significant. Apply length bounds before expensive hashing;
reject public-flow bodies larger than 16 KiB and raw password input beyond
1,024 code points before normalization. Do not truncate either. Old credentials retain their recorded policy
version if a future password representation changes.

The offline blocklist must ship with reviewed provenance/license and a content version.
It compares the whole normalized secret, not arbitrary substrings. The release gate must
reject a missing, empty, no-op or tiny illustrative-only dataset. Product-specific obvious
passwords may extend it; production authentication must not depend on an external breach
API. Existing valid credentials are not rejected at ordinary login because a later list
changes. Recovery/change applies the current creation policy. A test fixture is not a
production blocklist.

Use Spring's PasswordEncoder implementation and its required maintained BouncyCastle
dependency; do not implement Argon2 or password comparison. Pin parameters against the
managed Spring Security version, rather than assuming its named defaults match this
policy. Benchmark on deployment hardware; increased costs need resource/load tests.
The initial settings are a floor, not a performance or security guarantee. Cap concurrent
expensive work and queued requests; overload must fail safely rather than exhaust memory.

Only approved stored algorithm IDs/parameter ranges may be evaluated. Unsupported or
corrupt stored credentials are operational failures, never a no-op password fallback.
Successful login may upgrade an older approved encoding under concurrency control;
a racing reset/disable must not be overwritten or resurrected by that upgrade. A pure
rehash does not count as a password change or invalidate sessions. A real change does.
Plaintext passwords and encoded hashes must not enter domain events, logs, ordinary DTO
results, public module exports or generated toString output.

## 4. One-time challenges and delivery

Verification, password recovery and invitation are distinct purposes with independent
records and consumption rules. The first implementation must use platform SecureRandom
for a 32-byte secret, encoded as unpadded Base64url (43 characters), plus a separate random
UUIDv4 locator. The transport token is the canonical 36-character lowercase UUID,
one dot and the 43-character secret (80 ASCII characters total). The locator is not
the secret and grants nothing alone. Reject malformed
input before database work; purpose, subject, expiry, supersession and consumed state
remain server-side facts. Do not accept an arbitrary action or target from the link.

Store a SHA-256 verifier of the high-entropy secret, not the plaintext secret, in the
challenge record. Bind its purpose/subject explicitly. Use constant-time secret-verifier
comparison and a guarded database transition/row lock for one-winner consumption; a
Java check followed by an unconditional update is insufficient. Creation, resend and
consumption serialize on the relevant identity/purpose so a concurrent resend cannot
silently revive or consume a superseded challenge.

| Purpose | Initial lifetime | Successful action |
| --- | --- | --- |
| Registration verification | 24 hours | Set chosen credential, activate and provision atomically |
| Password recovery | 30 minutes | Replace credential, advance security epoch and revoke old sessions |
| Membership invitation | 7 days | Later D05 flow; authenticated verified intended recipient required |

At `now >= expiresAt` a challenge is expired. Use an injected Clock and UTC instants;
one authoritative timestamp per transaction decision. Resend has a 60-second cooldown
and supersedes earlier challenges of the same identity/purpose. Never extend old tokens
in place. Recovery requests do not disable an identity or invalidate its current password.
Recovery does not reactivate disabled/closed identities, change login address or grant roles.
Consumed/expired/superseded/malformed/wrong-purpose links produce the same safe public
action-unavailable outcome. GET is never the consumption endpoint, including mail scanners.

Durable retry creates a real confidentiality requirement: a delivery worker must recover
the link secret to send the email. A challenge hash alone cannot provide that. Plaintext
links MUST NOT be placed in the generic event/outbox payload or a recorded event.
D10 must supply a restricted, encrypted-at-rest delivery envelope with deployment-managed
keys, rotation/key IDs, retention/expiry and fail-closed key-unavailable behavior before
this workflow ships. The challenge table still stores only its verifier. A disposable
local inbox is a separate controlled UI; it is not ordinary application logging.
Do not replace durable mail with a temporary direct-send shortcut or pretend that hashing
alone solves retry. Outdated mail may already have been sent; token validation is final.

Links use a configured trusted public HTTPS origin, never request Host or forwarded values.
Confirmation screens use no-store, no-referrer and no third-party assets. Prefer a URL
fragment for the secret: a small first-party page can transfer it to the explicit
CSRF-protected POST after removing it from visible history. Supply a manual-paste path
for non-scripted clients. The secret must not leak into server/proxy access-log URLs,
analytics, referer headers or error reports. No automatic POST merely because a link opened.

## 5. Public outcomes and abuse protection

The UI/adapter must distinguish safe validation feedback from credential/existence facts.
Public results must not disclose whether an address is registered, pending or disabled.

| Situation | Browser/API interpretation |
| --- | --- |
| Syntactically invalid email or unacceptable proposed password | Safe field-specific validation; no database/existence details |
| Valid registration/recovery/resend request | Same neutral accepted result, even when no new action is appropriate |
| Invalid/expired/reused challenge | Action unavailable; deliberate resend/recovery option |
| Wrong password, unknown login, ineligible identity | Same generic authentication failure; no “user exists” hints |
| Missing CSRF proof | Rejected by Spring Security; no state change |
| Source/global admission exceeded | Retryable rate-limit response with bounded Retry-After |
| Subject-only public-mail limit exceeded | Same neutral accepted result; no delivery and no existence oracle |
| Database, key or required policy infrastructure unavailable | Sanitized technical unavailability; never invalid password or accepted delivery |

In the standard JSON ingress these map respectively to validation 400, neutral 202,
action unavailable 422, authentication 401, CSRF 403, visible throttling 429 and technical
unavailability 503. HTML renders useful safe form feedback/redirects with equivalent
meaning; it must not expose exception class names or causes. Expired/malformed tokens
must not select different public messages. Authorized operational tooling may use
allowlisted reason codes, without logging secrets or exposing them in public responses.

The [private global ingress gate](saas-identity-ingress.md) implements the coarse
deployment cap below; public ingress wiring and keyed admission are still unfinished.
Before expensive password work, apply independent atomic limits shared across instances.
These are security admission controls, **not paid quotas or tenant capacity**. Start with:

| Operation | Identifier/locator budget | Trusted client-source budget |
| --- | --- | --- |
| Login attempts | 10 / 15 minutes per login key | 60 / 15 minutes |
| Registration, resend and recovery-mail requests combined | 3 / hour per login key, plus cooldown | 20 / hour |
| Challenge completion attempts | 10 / 15 minutes per locator | 60 / 15 minutes |

Also admit at most 300 public identity requests per minute across the deployment
(combined routes), before subject lookup; make this operational cap configurable.
Use fixed server-time windows with atomic admission and bounded retained keys; document
boundary bursts rather than claiming a sliding-window guarantee. Missing/invalid locators
still spend the source/global budget. Source identity comes from the peer or explicitly
trusted proxy configuration, not arbitrary forwarded headers. Internal limiter keys use
purpose-separated keyed digests; raw addresses, IPs, secrets and session IDs are not logs
or metric labels. Shared-store failure fails closed. Failed expensive operations still
spend an attempt; business-transaction rollback must not refund abuse counters.

No permanent account lockout is triggered by unauthenticated requests. Identifier throttling
can temporarily affect a legitimate user, so neutral recovery guidance is required. Unknown
accounts use a comparable dummy encoder verification after admission; do not claim total
constant-time network behavior. Start with at most 4 concurrent password hashes per instance,
a maximum queue of 16 and a maximum admission wait of 250 ms; overflow is sanitized capacity
unavailability. These deployment limits must be configurable and load-tested, not silently
lower the hashing cost under load. Local test mode must not remove CSRF or admission rules.

### 5.1 Bounded storage, mail backlog and cleanup

Rate limits bound admission speed, not lifetime storage. Rotating sources and addresses
can otherwise fill the database despite each request satisfying its individual budget.
The standard assembly therefore MUST enforce independent deployment-wide storage budgets.
These are configurable operational safeguards, not subscriptions or tenant quotas.

| Resource | Initial hard budget across all instances | Expiry/cleanup rule |
| --- | --- | --- |
| Unverified registrations | 10,000 pending registrations, including reserved admissions | Delete abandoned registrations after 7 days from first creation; resend does not extend this deadline |
| Unexpired actionable challenges | 20,000 across purposes, including reservations; at most one current challenge per identity/purpose | Token expiry is authoritative even before physical cleanup |
| Unsent identity mail | 10,000 jobs AND 64 MiB aggregate encrypted payload, including reservations, retries and in-flight jobs | No retry/send beyond challenge validity; stop obsolete jobs |
| One identity mail payload | 64 KiB maximum including envelope overhead; no attachments | Reject before enqueue; never truncate a security link |
| Retained terminal challenge/delivery metadata | 20,000 records combined; no secret payload retained | Retain at most 24 hours after becoming terminal, then delete; expire oldest terminal metadata earlier if the cap requires it |
| Admission-control keys | 50,000 combined identifier/source/locator keys, including reserved allocations | Reuse bounded counters rather than append attempt history; expire after the last applicable window/cooldown ends |

All initial limits must be validated positive finite configuration. A lower configured
limit must not silently delete active users or live work to reach the new limit; stop
new allocations, report pressure and drain/clean up safely. Provisioning/recovery must
not gain an unlimited exception to these budgets. Independent route/resource gates keep
registration exhaustion from automatically disabling login or consuming existing valid
verification links; the latter still need their own applicable safety checks.

Apply bounded body/field checks and the global admission gate before identifier lookup,
new per-identifier limiter allocation, expensive work or business writes. Limiters may
update their bounded counters, but rejected requests must not create identities,
challenges, mail jobs, dead letters or one durable audit row per rejected request. If the
limiter key budget is full, reject new-key traffic through a coarse bounded gate rather
than evicting a still-active key and thereby resetting an attacker's attempt allowance.
A new address/source must not create an unbounded new allocation in a secondary mechanism.

Business allocations require atomic capacity reservations coordinated across instances
in the same storage boundary. A count-then-insert check is insufficient. Pending identity,
challenge and mail reservations, actual writes and rollback/release must be consistent;
duplicates and retries do not spend another slot, and failed transactions do not leak
reservations. Independent abuse-attempt budgets remain spent after business rollback.
A concurrent worker must not double-release a capacity slot or exclude its claimed job
from the backlog while it still holds its payload. Include payload bytes, not only job
counts. Capacity accounting must remain correct after restart and cleanup failure. Persisted
rows, including expired rows awaiting cleanup, remain charged to the applicable active,
backlog or terminal budget until deletion or an atomic accounted transition. Time passing
alone must not free storage capacity while leaving uncounted rows behind.

For a public route that may allocate a resource, perform its shared capacity gate before
existence-dependent branching and reserve/release unused capacity as necessary. Global
storage/backlog/key exhaustion returns the same sanitized temporary-unavailability result
(503 with bounded Retry-After) for existing and unknown addresses on that route. It must
not become an existence oracle or a false claim that mail was accepted. Ordinary subject-only
rate throttling keeps the neutral result specified above. Do not disclose occupancy or
internal limits publicly; the operator receives a safe aggregate pressure reason.

Run indexed, bounded-batch cleanup at least every 5 minutes, coordinated safely across
instances and restart. It must serialize with verification/resend on the relevant pending
identity: either verification/provisioning wins and the active identity survives, or cleanup
wins and the old action is unavailable. Never delete active/disabled/closed identities,
accounts or memberships as abandoned-registration cleanup. A safely removed pending address
may register again with a fresh UserId; old tokens cannot target the new identity. Cleanup
must not delete a pending registration while verification/provisioning commits independently.

Erase the protected mail secret payload after successful delivery or cancellation/expiry;
retain only bounded safe terminal metadata. Superseded/consumed challenges become terminal,
not a permanent request history. A local test inbox must also have bounded storage/retention;
it is not exempt because no real provider is used. Alert on cleanup lag and capacity pressure
using aggregate bounded-cardinality metrics. Sample/rate-limit rejection logs and configure
log rotation; an attacker must not turn each rejection into a new persistent audit/log record.
Physical reclamation and backup retention need normal database operations; deleting rows is
not a guarantee of immediate disk erasure or a fixed database size including WAL/indexes.

Tests must fill the last available slot concurrently across at least two application contexts,
prove every cap remains respected after rollback/restart, exercise mail byte/count limits and
limiter-key saturation, and verify that cleanup racing with activation never deletes an active
identity. Seed many distinct addresses/sources; prove rejected requests create no business or
unbounded auxiliary records. Verify the same public capacity outcome for known/unknown
addresses, continued allowed login/verification paths, and safe failure when cleanup stalls.
These proofs are mandatory before public registration is accepted as implemented.

These bounds deliberately trade availability of new requests for bounded application growth.
They do not make the application immune to distributed denial of service or protect network
bandwidth, connection pools or every database resource by themselves. Deployment-level
connection/request limits, timeouts, monitoring and appropriate ingress controls are still
required. Do not advertise unconditional availability or a DDoS-proof starter.

## 6. Sessions, revocation and the usecase guard

The first browser integration uses Spring Security and Spring Session JDBC, with explicit
reviewed Flyway ownership for framework tables. Disable automatic production schema
initialization. Use the same application writer topology and restricted runtime role;
framework session housekeeping remains separate from product aggregate persistence.
Do not invent a JWT/session protocol or add session tables to arbitrary product ownership.

- Regenerate the session ID on authentication using Spring mechanisms. Login and logout
  retain CSRF protection; logout is POST and invalidates the browser session.
- Default inactivity timeout: 30 minutes. Absolute authenticated lifetime: 12 hours from
  authentication, not renewed by reads or account switching. No remember-me initially.
- Production session cookies are host-only, Secure, HttpOnly, SameSite=Lax and path `/`;
  use a `__Host-` name with no Domain attribute. Disable URL-based session tracking.
- An explicit loopback-only local HTTP configuration may use a differently named non-Secure
  cookie. No automatic insecure fallback based on arbitrary headers/profile mistakes.
- Return targets are validated local paths. Never redirect to arbitrary client-supplied URLs.

A stored principal identifies a subject; it is not a permanent active-status snapshot.
Successful password change/reset, disable and closure advance an internal security epoch
atomically with the relevant change. The trusted authentication/session stamp records the
epoch at authentication. Later session use and protected execution compare it with current
trusted metadata; stale epochs deny access. Deleting known Spring Session rows is cleanup,
not the sole correctness mechanism: an in-flight request could save a session again.
No new public principal/stamp API is committed here; the implementation must cover its
serialization and upgrades without adding generated domain objects to session payloads.
Do not enable unrestricted polymorphic deserialization or accept serialized session
objects supplied by clients.

Pure password rehash and account selection do not advance the epoch. Password recovery
never logs the user in automatically. Validate authenticated identity eligibility before
opening any product tenant scope. The existing invocation-time AuthorizationGuard still
checks each reached usecase inside REQUIRED; endpoint authentication does not replace it.
Global public identity flows do not need a fabricated tenant and must not clear a verified
ambient scope. Epoch/status changes do not promise cancellation of already running work
or rollback of external effects. Preserve the existing actor/tenant transaction pin; the
D02 follow-up for user-facing revocation during an invocation remains a required proof.

## 7. Implementation gates and remaining decisions

Accepting D14 selects the above default policy. Configurable deployment values must have
validated bounds, explicit production requirements and documented change behavior.
Changing login/password representation or persisted semantics requires a versioned migration;
changing rate thresholds does not bypass the public outcome contract. Do not expose a broad
“disable security for local testing” switch.

Implement and verify in reviewable slices:

1. Credential representations, normalization/bounds and encoder/blocklist policy tests.
   Prove salt randomness, parameter IDs, safe failure, oversized/Unicode input and upgrades.
2. Internal credentials/challenges/admission persistence with generated migrations and
   real restricted-role PostgreSQL tests: duplicates, concurrent resend/consume, rollback,
   expiry, storage/backlog/key saturation, cleanup races and restart. No general credential
   repository is exported.
3. D10 protected mail transport/local inbox and D05 account/owner provisioning integration.
   Prove durable retry without secrets in events/logs and without duplicate accounts.
4. Complete public registration/verification, Spring login/session/logout/recovery and
   useful UI outcomes. Exercise multiple application instances, revocation races, CSRF,
   proxy/cookie settings, seeded-secret log scans and the separate SaaS sample/Wizard.

D10 encryption/key ownership and D05 role/account administration remain separate review
items; an invitation lifetime is not an implemented invitation or role catalog. MFA and
federated identity are outside the first credential journey, not replacements for any
remaining 0.6.0 scope. A01/A02 and the full SaaS/final manual acceptance gates remain OPEN.

## References and compatibility

Reviewed against primary guidance on 2026-11-12. These sources inform specific choices;
the policy does not claim blanket compliance. Rolling Spring documentation may describe
newer releases; implementation must compile and test the repository-managed versions
(currently Spring Boot 3.4.2 / Spring Security 6.4.2) and record any required dependency.

- [NIST SP 800-63B-4: password verifier guidance](https://pages.nist.gov/800-63-4/sp800-63b.html)
- [OWASP: password storage and Argon2id parameters](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
- [Spring Security: PasswordEncoder and upgrades](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html)
- [Spring Session 3.4: JDBC storage and transactions](https://docs.spring.io/spring-session/reference/3.4/configuration/jdbc.html)
