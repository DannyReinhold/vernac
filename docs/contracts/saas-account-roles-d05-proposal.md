---
title: D05 proposal — account roles and atomic personal-account provisioning
---
# D05 review proposal

Status: PROPOSED for review, not accepted configuration or implemented workflows.
The [Identity roadmap](../development/identity-membership-0.6.0.md) explicitly keeps
catalog ownership and protected role-assignment operations behind D05 approval.
This PR supplies the concrete decision for review. Existing D14 credential/session
policy is unchanged; independent authentication work can proceed.

## Catalog ownership

The application owns one version-controlled UTF-8 JSON catalog, explicitly named
by a classpath resource setting. Modules publish permission declarations, not
application role assignments. No remote URL, database-editable catalog, wildcard,
negative grant, implicit hierarchy or automatic classpath-wide discovery is supported.

Proposed format:

```json
{
  "format": 1,
  "roles": [
    {
      "key": "example.workspace.reader",
      "permissions": ["example.workspace.DocumentPermissions.READ"]
    },
    {
      "key": "example.workspace.editor",
      "permissions": [
        "example.workspace.DocumentPermissions.READ",
        "example.workspace.DocumentPermissions.EDIT"
      ]
    }
  ]
}
```

Keys are qualified, stable identifiers. Startup rejects unknown format/fields,
duplicate role keys, duplicate permission keys, unknown permission references and
over-limit input. Permission names resolve against the application's available
reviewed permission vocabulary; arbitrary Spring authority strings never resolve
as grants. The loaded catalog is immutable for the application lifetime. Limits,
resource loading and module-vocabulary resolution require executable acceptance
tests before the loader can be called complete.

A catalog update is a code/configuration review and restart. Removed roles with
persisted assignments fail closed and require a reviewed assignment migration;
the system must not silently reinterpret, discard or broaden stored assignments.
Role labels/descriptions can be localized separately; labels are not authority.

## Owner and assignments

Account ownership is an explicit membership fact, distinct from product permissions.
The built-in Owner role grants a finite published set of account-management keys:
inspect account/membership, manage permitted membership roles, issue/revoke invitations,
and explicitly transfer ownership. Product capabilities require explicit catalog
grants; owner status supplies no wildcard or entitlement bypass.

Each membership has zero or more explicitly permitted product roles. Grants are their
union. An owner may assign only roles enabled for that account's application catalog;
a submitted role name cannot define a new role. Ownership transfer is a separate
authenticated, CSRF-protected operation. Invitations cannot carry owner promotion.

All mutations that can remove an active owner first lock the account metadata row.
Inside that same writer transaction, recheck identity, account, actor membership,
allowed operation and surviving active-owner count. Concurrent departures cannot both
remove the last owner. An identity-wide disable/closure must honor the same invariant
for all owned active accounts in deterministic account-ID order, or require ownership
transfer/account closure first. This cross-account behavior is part of D05, not cleanup.

## Global metadata and personal provisioning

AccountId, UserId and technical TenantId remain distinct nominal types. Account,
account-to-tenant mapping, personal-account owner and membership are controlled global
access metadata. Public clients never get unrestricted repositories/byId access.

Proposed uniqueness:
- Each account has one immutable, database-unique technical tenant mapping.
- A personal-owner record keyed by UserId references one database-unique AccountId.
  Thus one identity cannot create a second personal account after rollback/restart.
- One account/user membership uses a private unique scalar AccountMemberKey containing
  the canonical AccountId UUID, slash, and canonical UserId UUID. The fixed-width
  73-character representation is injective, contains no hash or random derivation,
  and validates against both nominal fields. This uses the existing scalar unique
  support; it does not assume an unimplemented composite-unique DSL syntax.
- All related writes use the same restricted application writer and transaction manager.
  Foreign keys and generated schema-check coverage are required for the final model.

Verification POST validates the challenge and the creation password, then commits
credential enrollment, challenge consumption, pending-registration completion,
identity activation, personal account, tenant mapping and first owner membership in
one physical writer transaction. Expensive password work occurs beforehand; state is
revalidated inside the writer. No authenticated session is created.

The first owner's account-management capabilities are explicit. Product grants come
from a reviewed initial-role choice in application configuration; missing/unknown
configuration fails startup rather than guessing administrator rights.

A duplicate/replayed verification cannot provision twice. Competing attempts must
serialize through the existing challenge/identity boundary and database uniqueness.
Any failure rolls back the whole activation/provisioning unit; committed mail capacity
and independently spent admission/encryption attempts retain their existing contracts.

Provisioning access metadata does not fabricate a product TenantContext. Future
initial product data must use a separately reviewed tenant-aware operation; it cannot
bypass the existing actor/tenant pin.

## Required acceptance before implementation is declared complete

- Catalog rejection of malformed/duplicate/unknown/over-limit input and unknown keys.
- Exact role union, no Spring-authority/owner/paid-plan bypass, current metadata checks.
- Two real application contexts competing to remove the last owner; both cannot succeed.
- Atomic verification/provisioning rollback, retry, restart and concurrent replay.
- Distinct nominal IDs and immutable tenant mapping; no caller-selected tenant authority.
- Role removal/update handling with existing assignments and no silent privilege changes.
- Invitation acceptance bound to verified intended identity, no owner invitation,
  idempotent existing membership and no role escalation.
- Restricted-runtime PostgreSQL uniqueness/foreign-key enforcement, migration replay and
  schema-check. No manual untracked product DDL.

Review decision: accept catalog ownership/format, the finite owner model and assignment
rules, the scalar membership-key representation, and atomic personal-account provisioning.
After acceptance, implement these as focused model, policy and public-flow PRs. This
proposal alone does not close registration, membership, invitations or D05.
