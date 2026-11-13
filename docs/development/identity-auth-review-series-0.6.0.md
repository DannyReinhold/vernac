---
title: Identity/Auth review series for 0.6.0
---
# Identity/Auth review series

Checkpoint: 13 November 2026. All listed branches are cumulative and target
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

## Delivered slices

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

1. Explicit application assembly using the ordinary binding, trusted origin and
   source policy, writer transaction manager and bounded workers. Full request limits
   must apply before form parsing. Fail startup on incomplete production configuration.
2. Spring Session JDBC with separately reviewed framework-table migration ownership,
   restricted runtime privileges, thirty-minute idle expiry, host-only production
   cookies, session-ID rotation, POST logout, CSRF and validated local return paths.
   Prove two instances/restart, fixation, expiry, logout and stale-session behavior
   through the actual HTTP filter chain.
3. Public login, recovery request/reset and password-change pages/actions. Fragment
   handling must erase the bearer token from history before explicit protected POST;
   GET never consumes a challenge. Prove neutral public outcomes and no auto-login
   after verification/recovery.
4. Review [the concrete D05 proposal](../contracts/saas-account-roles-d05-proposal.md),
   then implement its catalog, account/owner model and atomic personal provisioning.
   Public verification/registration cannot close before provisioning rollback,
   replay/restart, owner and schema proofs pass.
5. Add complete browser acceptance, controlled local preview authorization,
   deployment/operations documentation and the remaining delivery monitoring gate.

The D05 proposal is not accepted merely because its documentation PR passes CI.
Independent login/session work does not require inventing the role policy.

## Coarse progress

Engineering estimate only: Identity/Auth is roughly two-thirds implemented across
accepted/private primitives and the new review series; the full 0.6.0 release is
roughly one-third. These are not test percentages or a release-readiness statement.
The public journey and operational acceptance gates are still open.
