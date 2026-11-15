---
title: Identity/Auth review series for 0.6.0
---
# Identity/Auth review series

Checkpoint: 15 November 2026. All listed branches are cumulative and target
feature/0.6.0. Merge the earlier PRs first using ordinary merge commits so later
diffs retain only their own slice. No PR in this series was automatically merged.

| Order | PR | Review scope |
| --- | --- | --- |
| 1 | [#50](https://github.com/DannyReinhold/vernac/pull/50) | Spring crypto feasibility on Java 21/25 |
| 2 | [#51](https://github.com/DannyReinhold/vernac/pull/51) | Spring Boot baseline |
| 3 | [#52](https://github.com/DannyReinhold/vernac/pull/52) | Private protected delivery envelope |
| 4 | [#53](https://github.com/DannyReinhold/vernac/pull/53) | Durable encryption admission and key history |
| 5 | [#54](https://github.com/DannyReinhold/vernac/pull/54) | Atomic challenge and protected mail-job ledger |
| 6 | [#55](https://github.com/DannyReinhold/vernac/pull/55) | Fenced worker, bounded retries and cleanup |
| 7 | [#56](https://github.com/DannyReinhold/vernac/pull/56) | Shared mail port, Brevo and bounded Noop inbox |
| 8 | [#57](https://github.com/DannyReinhold/vernac/pull/57) | Fresh session epochs and absolute lifetime |
| 9 | [#58](https://github.com/DannyReinhold/vernac/pull/58) | Identity worker/provider bridge and templates |
| 10 | [#59](https://github.com/DannyReinhold/vernac/pull/59) | Two-phase Spring credential authentication |
| 11 | [#60](https://github.com/DannyReinhold/vernac/pull/60) | D05 role/provisioning decision proposal |
| 12 | [#61](https://github.com/DannyReinhold/vernac/pull/61) | Request-time stale-session invalidation |

GitHub checks and draft status on each exact head are authoritative. The local
execution environment became unavailable during provider work; subsequent changes
are preserved in GitHub and validated by the repository's normal CI. A green
primitive PR is not acceptance of an unimplemented public browser journey.

| 13 | [#62](https://github.com/DannyReinhold/vernac/pull/62) | Bounded Identity forms and validated local return paths |
| 14 | [#63](https://github.com/DannyReinhold/vernac/pull/63) | Explicit Spring login HTTP adapter |
| 15 | [#64](https://github.com/DannyReinhold/vernac/pull/64) | Opt-in HTTPS browser chain, CSRF renewal and POST logout |
| 16 | [#65](https://github.com/DannyReinhold/vernac/pull/65) | Shared JDBC sessions and restricted framework schema |
| 17 | [#66](https://github.com/DannyReinhold/vernac/pull/66) | Minimal CSRF-protected login page |
| 18 | [#67](https://github.com/DannyReinhold/vernac/pull/67) | Explicit actual JDBC authentication assembly |
| 19 | [#68](https://github.com/DannyReinhold/vernac/pull/68) | Mandatory pre-session ingress and bounded admission maintenance |

## Delivered slices

- [Mandatory pre-session browser admission](../contracts/saas-identity-request-admission.md),
  plus a bounded public admission-maintenance entry point.
- [Explicit JDBC authentication assembly](../contracts/saas-identity-authentication-assembly.md),
  with default-policy startup and actual password/provider PostgreSQL proofs.
- [Minimal CSRF-protected login page](../contracts/saas-identity-login-page.md),
  with actual form submission in chain/session tests.
- [Explicit shared JDBC sessions](../contracts/saas-identity-jdbc-sessions.md), with
  separate framework schema ownership and secure host-only cookies.
- [Opt-in HTTPS browser chain](../contracts/saas-identity-browser-chain.md), including
  current-session checks, CSRF renewal, POST logout and no saved-request URLs.
- [Explicit login HTTP adapter](../contracts/saas-identity-login-http.md), with
  Spring filter-chain/CSRF/fixation/context persistence tests.
- [Shared mail providers](../contracts/saas-mail-providers.md) and
  [the Identity bridge](../contracts/saas-identity-mail-provider-bridge.md).
- [Session metadata validation](../contracts/saas-identity-session-trust.md),
  [request checks](../contracts/saas-identity-session-filter.md) and
  [Spring authentication](../contracts/saas-identity-authentication-provider.md).
- Existing private admission, capacity, challenge, credential, registration, login
  ownership, protected queue and worker contracts remain in force.

The new Identity modules do not automatically install public routes or infer
application roles, accounts or tenants. No real provider email is sent by tests.

## Remaining implementation sequence

1. Finish ordinary application/profile binding and production lifecycle wiring: trusted
   origin/source policy, selected mail provider, encryption keys, bounded mail worker and
   scheduled admission maintenance. The explicit login/authentication/session assembly
   APIs now exist; automatic activation is deliberately absent. Register framework Session
   before Security once in the actual servlet container. Configure and test header/body,
   read/total-time and concurrent-request limits at the connector/proxy boundary.
2. Implement public recovery request/reset and authenticated password-change pages/actions.
   Fragment handling must erase the bearer token from history before explicit protected
   POST; GET never consumes a challenge. Prove neutral public outcomes and no auto-login
   after recovery. The login HTML/POST, CSRF renewal, POST logout, stale-session checks,
   shared JDBC storage and idle-expiry slices are implemented and have separate proofs;
   their completion does not close these remaining journeys.
3. Review [the concrete D05 proposal](../contracts/saas-account-roles-d05-proposal.md),
   then implement the catalog, account/owner/membership model, protected assignment and
   invitation policies, and atomic personal provisioning. Public verification/registration
   cannot close before provisioning rollback, replay/restart, owner and generated schema
   proofs pass. No role policy was silently accepted or invented during the login work.
4. Add complete real-HTTP/browser acceptance composing actual JDBC credentials, admission,
   Session filters, worker and public journeys. Current Spring filter-chain and two-instance
   PostgreSQL proofs identify their mocked servlet transport/test-manager boundaries.
5. Finish controlled local inbox-preview authorization, deployment/operations documentation,
   framework-schema migration tracking and remaining delivery monitoring acceptance.

D05 is a review proposal, not an accepted role policy merely because its documentation
PR passes CI. Independent login/session work does not imply account or product grants.

## Acceptance status

The 15 November continuation adds focused PRs #62–#68 after the existing #50–#61
review series. Merge in that order with ordinary merge commits; no PR was auto-merged.
Exact-head GitHub results remain authoritative. The new optional browser path has
executable proofs, but Identity/Auth as a whole and the complete 0.6.0 release remain
OPEN. No updated percentage is inferred from the number of PRs or passing unit tests.
