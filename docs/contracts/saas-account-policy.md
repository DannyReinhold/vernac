---
title: Fresh account access and owner-safe administration
---
# Account access policy

Explicitly construct `IdentityAccounts` with the canonical writer, trusted clock,
IdentitySessionTrust, immutable RoleCatalog, an explicitly configured initial product
role set (possibly empty) and the application's object-policy PermissionEvaluator.
Missing configuration, mismatched DataSource/manager or unknown persisted role keys
fails startup. Owner-management permission keys are reserved and cannot be smuggled
into product-role definitions. The catalog remains application configuration.

Public metadata operations resolve the current nominal stamped Spring principal and
recheck active identity, account and membership. Organization creation, role assignment,
removal/leave, explicit ownership transfer, account closure and identity-wide disable/
closure use one short physical writer. They reject inherited actor/tenant/transaction
contexts rather than switching a product invocation's identity or scope. Expose these
through the CSRF-protected global browser adapter, not inside a product tenant usecase.

The writer first takes the existing global metadata/budget lock, then the account row
lock before any owner count/mutation. Removal cannot eliminate the last active owner.
Transfer promotes an already active member and demotes the current owner atomically;
role assignment never changes ownership. Identity-wide disable/closure locks all owned
active accounts in deterministic PostgreSQL account-ID order and requires surviving
active owners before changing identity state/epoch. Close/transfer accounts first when
necessary. These are finite account-management capabilities, not product or paid-plan
bypasses. Initial bounds are 100 active memberships per identity (closed-account records
still count until membership removal) and 1,000 members per account; these are technical
metadata bounds, not paid plan quotas. Invitation acceptance enforces the member bound.

`select(AccountId)` validates current membership and the immutable stored tenant mapping
before opening a TenantContext scope. The caller closes it. There is no raw-request
TenantId selection or fallback account after revocation. Possessing an AccountId is not
a membership proof. Later product usecases still use the existing actor/tenant guard.

As a PermissionEvaluator the service rechecks the nominal current actor, active account,
identity/membership and exact assigned role union. Product grants also require the
explicit application object policy; it does not infer document ownership from a nominal
resource ID. Finite Owner keys apply only to that account (and AccountId resource when
present). Payment status, Spring authorities and technical principals never supply grants.
Policy reads join the current transaction and perform no reservations/business writes.
Revocation applies at later checks under normal transaction visibility, not instantaneous
cancellation of already performed external effects.

An internal personal-provisioning callback joins the existing verification writer and
creates Account, initial owner membership and the immutable PersonalAccount record. It
neither opens a product tenant scope nor creates a session. The following journey slice
proves atomic composition with credentials/challenge/activation and rollback/restart.

Five mandatory actual PostgreSQL tests prove two-context last-owner concurrency, fresh
role union/object policy/selection and no implicit product grants, identity-wide disable,
removed-role startup rejection and untrusted/nested scopes, and explicit transfer without
role-based owner promotion. No owner policy is implemented by a bare Spring authority.
