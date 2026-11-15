---
title: Identity/Auth review series for 0.6.0
---
# Identity/Auth review series

Checkpoint: 15 November 2026. The original 31 slices are consolidated into three
review PRs. Their source commits are preserved; no PR was merged automatically.

## Active review and merge order

| Order | PR | Included original slices | Review scope | Current base |
| --- | --- | --- | --- | --- |
| 1 | [#58](https://github.com/DannyReinhold/vernac/pull/58) | #50–#58 | Boot 4 baseline, encrypted durable mail, providers and session trust primitives | feature/0.6.0 |
| 2 | [#68](https://github.com/DannyReinhold/vernac/pull/68) | #59–#68 | Bounded login, HTTPS/CSRF/logout, shared JDBC sessions and ingress admission | feature/identity-mail-provider-bridge |
| 3 | [#80](https://github.com/DannyReinhold/vernac/pull/80) | #69–#80 | Accounts/roles, public journeys, invitations, browser sample, operations and acceptance | feature/identity-browser-request-admission |

Review and merge **#58 → #68 → #80**, using ordinary merge commits. After merging
#58, change #68's base to feature/0.6.0 before merging it. After merging #68, change
#80's base to feature/0.6.0 before merging it. Do not merge a successor into its
current predecessor branch. Avoid squash/rebase merges to preserve ancestry and
keep subsequent diffs focused. Keep predecessor branches until successors are retargeted.

The other original PRs are superseded and closed; their branches and test evidence
remain available. Ancestry was checked for every superseded head. #75's final test
correction and #78's sample fixes were cherry-picked into later branches: the affected
source files match exactly, while the final CI workflow adds the operations proof.
Only review metadata and this guide changed during consolidation; runtime/test source
and migration history were preserved. Re-run normal target-branch checks after integration.

## Historical implementation slices

The following table is an evidence index, not an additional merge queue.

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
| 13 | [#62](https://github.com/DannyReinhold/vernac/pull/62) | Bounded Identity forms and validated local return paths |
| 14 | [#63](https://github.com/DannyReinhold/vernac/pull/63) | Explicit Spring login HTTP adapter |
| 15 | [#64](https://github.com/DannyReinhold/vernac/pull/64) | Opt-in HTTPS browser chain, CSRF renewal and POST logout |
| 16 | [#65](https://github.com/DannyReinhold/vernac/pull/65) | Shared JDBC sessions and restricted framework schema |
| 17 | [#66](https://github.com/DannyReinhold/vernac/pull/66) | Minimal CSRF-protected login page |
| 18 | [#67](https://github.com/DannyReinhold/vernac/pull/67) | Explicit actual JDBC authentication assembly |
| 19 | [#68](https://github.com/DannyReinhold/vernac/pull/68) | Mandatory pre-session ingress and bounded admission maintenance |
| 20 | [#69](https://github.com/DannyReinhold/vernac/pull/69) | Strict app-owned D05 role catalog |
| 21 | [#70](https://github.com/DannyReinhold/vernac/pull/70) | Generated global-ID metadata references and foreign keys |
| 22 | [#71](https://github.com/DannyReinhold/vernac/pull/71) | Account, personal-owner and membership metadata |
| 23 | [#72](https://github.com/DannyReinhold/vernac/pull/72) | Current role/object policy, verified selection and last-owner protection |
| 24 | [#73](https://github.com/DannyReinhold/vernac/pull/73) | Actual registration/verification/recovery/password journeys |
| 25 | [#74](https://github.com/DannyReinhold/vernac/pull/74) | CSRF-protected public journey forms and fragment handling |
| 26 | [#75](https://github.com/DannyReinhold/vernac/pull/75) | Intended-recipient invitations, encrypted delivery and bounded cleanup |
| 27 | [#76](https://github.com/DannyReinhold/vernac/pull/76) | Workspace/owner screens and fresh product request selection |
| 28 | [#77](https://github.com/DannyReinhold/vernac/pull/77) | Actual metadata/mail/session browser-filter PostgreSQL acceptance |
| 29 | [#78](https://github.com/DannyReinhold/vernac/pull/78) | Ordinary deployable HTTPS sample, protected local preview and Chromium |
| 30 | [#79](https://github.com/DannyReinhold/vernac/pull/79) | Aggregate-only operations, worker failure monitoring and recovery |
| 31 | [#80](https://github.com/DannyReinhold/vernac/pull/80) | Final technical acceptance, documentation and mandatory identity/mail release inventory |

Exact-head GitHub checks are authoritative; the final [acceptance record](identity-auth-acceptance-0.6.0.md)
names source commits and test runs. The initial 15 November continuation ran local compilation
and targeted unit/PostgreSQL tests. After the local environment became unavailable, later
integration/sample verification ran through GitHub CI. No real provider email was sent.

D05 Option 1 was accepted by the project owner on 15 November 2026. The catalog,
metadata, policy, atomic provisioning, public journeys, invitations and browser/account
assembly are implemented in the listed slices; no further D05 approval is pending.

The standalone HTTPS sample composes the ordinary module/provider bindings and actual
generated permission guard. PostgreSQL proofs use restricted runtime credentials;
Chromium runs the real fragment script and form journeys. The operations API exposes
only fixed-cardinality aggregate state and safe stage counters; it installs no public
metrics endpoint. See the [current roadmap](identity-membership-0.6.0.md) and
[operational contract](../contracts/saas-identity-operations.md).

Maintainer review/merging remains separate. Follow the three-PR order above;
squash-merging cumulative predecessors causes later PRs to repeat earlier changes.
The complete 0.6.0 release, payment/invoicing/quotas and full SaaS Wizard/installed-plugin
acceptance remain OPEN. No completion percentage is inferred from the PR count.
