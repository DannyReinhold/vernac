---
title: Global account and membership metadata
---
# Account metadata

The D05 model exports nominal AccountId, MembershipId, InvitationId and a finite
AccountPermissions declaration alongside UserId. Account/user/technical TenantId remain
distinct types. These declarations grant nothing and add no automatic activation.

The optional JDBC module owns three private global aggregates. Account has an immutable
unique UUID tenant mapping and PERSONAL/ORGANIZATION kind. AccountMembership has immutable
account/user references, a unique canonical 73-character account/user scalar key, an
explicit owner fact, ACTIVE/REMOVED status and a bounded canonical role set. PersonalAccount
is keyed by UserId and references one unique AccountId; the trusted provisioning writer
sets its nominal id and subject to that same user. Repositories remain internal module
contracts. No public client receives unrestricted metadata repositories.

All related references have generated global-ID foreign keys to existing Account/Identity
roots. Role keys use the accepted application catalog, not foreign-key rows in a dynamic
role editor. Assignment encoding is sorted, duplicate-free and bounded at 32 qualified
keys. Unknown removed roles are checked by the subsequent catalog/policy assembly.

The twelfth generated migration adds only these tables and constraints; prior eleven
SQL/snapshot files retain their bytes. Generated schema-check, module replay and restricted
writer privileges apply. Account tenant fields have no mutation API. No tenant scope,
product grant, subscription or session is inferred from possessing these records.

Two real PostgreSQL proofs cover uniqueness, nominal distinctions, restart, FK orphan
and parent-deletion rejection and inability of the runtime role to drop constraints.
Role-set unit tests cover canonical round-trip and malformed/duplicate/oversized sets.
Owner-safe policy, access selection and atomic verification/provisioning follow; merely
saving these aggregates is not an authorized account-management operation.
