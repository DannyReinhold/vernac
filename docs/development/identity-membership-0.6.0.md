---
title: Identity, membership and roles — implemented module boundary
---
# Identity, membership and roles

D05 Option 1 was accepted by the project owner on 15 November 2026. Identity/Auth
is implemented in the cumulative review series through PR #79; the final
[evidence record](identity-auth-acceptance-0.6.0.md) identifies exact verified source
commits, checks and remaining release boundaries. Code on an open PR is not a
merged release. The whole 0.6.0 SaaS release remains NOT READY.

## Accepted boundaries

The optional extensions live under saas/ in this repository, use Apache-2.0 and
share the coordinated 0.6.0 version. They are separate Maven artifacts; compiler
and general runtime do not depend on SaaS identity or mail implementations.

| Boundary | Responsibility |
| --- | --- |
| Identity contracts | Nominal UserId/AccountId/MembershipId/InvitationId, eligibility port, strict finite app-owned roles |
| Identity JDBC | Controlled global metadata, credentials/epochs, admission/capacity, purpose-bound challenges, encrypted mail jobs, account policy and journeys |
| Spring security | Standard human principal, current-stamp actor resolver, bounded forms, HTTPS/CSRF login/logout/public journeys, JDBC sessions and fresh account selection |
| Shared mail | Ordinary selected MailProvider, Brevo integration or explicit bounded local simulation |
| Product | Generated usecase permissions, tenant-owned data, object-specific policy and explicit benefit/limit checks |
| Later billing | Plans/subscriptions/payment/invoices; no ownership promotion or authorization bypass |

UserId identifies a human, AccountId a workspace and TenantId its immutable
technical isolation scope. One account has one database-unique tenant mapping;
one user has at most one personal account. A membership has one canonical unique
account/user key. Generated global-ID foreign keys preserve the related private
metadata graph. These facts do not turn identity repositories into public APIs.

## Delivered journeys and policies

- Neutral CSRF-protected registration/recovery requests use bounded persisted
  admission and the ordinary encrypted durable mail/provider path.
- Explicit verification POST atomically consumes its purpose-bound challenge,
  enrolls credentials, activates the identity and creates the personal account,
  tenant mapping and first owner. Provisioning failure rolls back that whole unit;
  concurrent replay/restart cannot provision twice. Password work occurs first,
  outside the business writer. Verification/recovery creates no logged-in session.
- Spring authentication uses the accepted D14 address/password policy, bounded
  password work, current persisted epoch and a final eligibility recheck.
  Secure host-only JDBC sessions support two instances/restart, inactivity and
  absolute expiry, fixation protection, fresh CSRF and POST logout.
- Recovery/change advances the epoch. Disable/closure rejects later stamped access.
  Changes take effect at subsequent checks; already running work is not cancelled.
- A caller AccountId requests selection. Current identity, account/membership and
  immutable mapping are checked before opening the product TenantContext; product
  invocations still evaluate their generated permission and object policy. A raw
  caller TenantId grants nothing. Revocation never silently switches accounts.
- A strict versioned classpath JSON catalog maps qualified app roles to explicitly
  supplied permission declarations. Assignments union exact keys; no wildcard,
  negative grant, implicit hierarchy or arbitrary Spring-authority mapping exists.
  Unknown stored roles block startup and require a reviewed migration.
- Ownership is a separate finite metadata fact. Product roles and paid benefits
  cannot supply it. Competing member removal/ownership changes serialize; removing
  the last active owner fails, including identity-wide disable/closure across all
  owned active accounts. Explicit transfer promotes an existing active member.
- Invitations bind account, current eligible issuer, verified intended recipient,
  expiry, one-time verifier and permitted product roles. They never promote an owner
  or silently increase an existing active membership's roles. Expiry/revocation and
  bounded cleanup retain durable capacity accounting.
- The full browser adapter provides registration, recovery/password, workspace,
  owner-scoped member/role/invitation and own-identity lifecycle screens. Every state
  change is a bounded protected POST. Fragment scripts erase link bearers before
  explicit confirmation; safe failures do not expose backend messages or token data.

## Decisions and source contracts

| Decision | Accepted direction and implementation |
| --- | --- |
| D01 | Same repository/license/version with optional independent artifacts |
| D02/D03 | Nominal verified actor, generated access guard and immutable verified tenant selection |
| D05 | [Accepted complete Option 1](../contracts/saas-account-roles-d05-proposal.md), [role catalog](../contracts/saas-role-catalog.md), [metadata](../contracts/saas-account-metadata.md), [policy](../contracts/saas-account-policy.md) |
| D14 | [Credential policy](../contracts/saas-identity-credentials.md), standard Spring authentication, shared JDBC sessions and purpose-bound challenges |
| D10 identity scope | [Shared mail providers](../contracts/saas-mail-providers.md), [public journeys](../contracts/saas-public-identity-journeys.md), [invitations](../contracts/saas-account-invitations.md) |

The [review sequence](identity-auth-review-series-0.6.0.md) lists PRs in merge order.
The [account browser contract](../contracts/saas-account-browser.md) and
[operations contract](../contracts/saas-identity-operations.md) specify the public
assembly APIs, safe failures, monitoring, restart and key/catalog migration boundaries.
The [standalone HTTPS sample](../../examples/identity-auth/README.md) uses normal
.vapplication activation and a generated protected workspace operation. Its controlled
local preview requires an explicit local profile, loopback HTTPS and a separate
capability header; production registers no preview route.

## Release boundaries

Identity/Auth evidence contributes to A01/A02 and the identity portions of
A04/A11/A12/A13/A14/A17/A18/A20/A21. It does not complete the whole SaaS release:
billing/entitlements, quotas, invoices, payment/webhook routing, tenant product
journeys and the complete SaaS Wizard/installed-plugin checks retain their own
[release acceptance](release-0.6.0.md). A read-only workspace demo is not the full
paid-product application. Tests use disposable PostgreSQL and the controlled local
mail provider; no real Brevo mail is sent and no production deployment is authorized.
