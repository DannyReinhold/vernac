---
title: Atomic registration recovery and password-change application boundary
---
# Public Identity journey assembly

`IdentityJourneys` explicitly composes the existing private admission, challenge,
registration, credential, encryption and durable mail helpers with the accepted account
policy. It implements the transient `IdentityBrowserActions` transport port. Configure
one canonical writer, trusted Clock, matching IdentityJdbcAuthentication/IdentityAccounts,
application-scoped BoundedCredentialPasswords, ordinarily selected bounded MailProvider,
trusted origin and explicit active/retained deployment keys. It starts no worker in its
constructor and selects no provider fallback.

Register its `mailLifecycle()` as an explicit Spring lifecycle bean. That existing
bounded worker owns dispatch, lease fencing, retries, erasure, challenge/registration
cleanup and persisted-cursor admission maintenance. Provider calls run outside business
transactions/actor/tenant context; simulated sending requires explicit loopback mode.
Real selected providers retain the shared <=30-second transport contract. No custom
SMTP, crypto, session protocol or unbounded background fallback is introduced.

Registration/recovery requests apply independently committed coarse/source/subject
admission before existence lookup and expensive/storage work. Subject-only denial,
ineligible/unknown addresses and normal completion share the neutral outward result.
Global/source throttling is typed and bounded; technical failures stay distinct. One-use
encryption admission precedes the writer; challenge and encrypted mail job persist
atomically. The ordinary worker delivers after commit through the bound provider.

Verification performs bounded Spring password work outside the writer, then atomically
consumes the purpose-bound challenge, enrolls credentials, activates the identity,
releases pending-registration capacity and creates the personal account/tenant mapping/
first owner membership in one physical writer. Any provisioning failure rolls everything
back, including challenge consumption. Replay/concurrent verification cannot provision
twice. The first product roles come from explicit application configuration, not owner
status. No product TenantContext or authenticated session is created.

Recovery consumes only RECOVERY challenges and replaces credentials/security epoch in
one writer. Password change requires the current nominal stamped identity, independently
committed admission and actual current-password verification outside transactions; final
replacement confirms the credential snapshot/epoch. Both revoke old authentication at
later fresh checks and require ordinary reauthentication. They never mint a session or
additional account. GET/fragment/CSRF handling belongs to the following browser adapter.

Five mandatory actual PostgreSQL proofs use real bounded Spring Argon2, the actual
assembly, durable encrypted mail worker and an explicitly bounded local test provider.
They prove full verification/provisioning and no login, privilege-injected rollback and
retry after restart, two-context verification race, purpose/replay/epoch/password-change
semantics and password-policy/expiry/neutral unknown recovery. Provider callbacks assert
absence of transaction/actor/tenant context. No real email is sent.
