---
title: Declarative tenant isolation — required 0.6.0 foundation
---
# Declarative tenant isolation — required 0.6.0 foundation

Status: **accepted 0.6.0 scope/baseline; technical contract proposed, not implemented**.
This supersedes the assumption that invoice/account filtering in an authorization
policy can provide normal SaaS isolation. Tenant isolation is required alongside the
[authorization contract](usecase-authorization-contract.md), not a later optional feature.
See the [delivery plan](saas-0.6.0-plan.md) and [release ledger](release-0.6.0.md).

## Technical design now available

The [technical tenant contract](tenant-technical-contract.md) proposes the concrete
mechanics: UUID TenantId, transaction-bound context, @tenantId discriminator, unchanged
root UUID primary keys plus tenant-safe owned keys, generated SQL predicates and
PostgreSQL RLS, object provenance, module migrations and worker routing. Those choices
supersede the corresponding open alternatives below for review; no implementation or
TENANT test pass is implied. The next step is an executable Spring/PostgreSQL spike.

## Tenant, actor, account and organization

A tenant is the application's data-isolation boundary. It is not a UserId, role,
subscription, organization or invoice recipient. A personal account can own one
tenant; an organization can have a tenant with several members. Exact account-to-tenant
provisioning/cardinality is a D13 decision; do not hard-code all IDs to the same UUID.
The SaaS module supplies verified access/provisioning, not required organization fields
in every application's model. A user may be entitled to enter several tenants.

User A creates an invoice in tenant T. Another user B with membership in T and the
necessary right may read it; B operating in tenant U cannot read it, regardless of
knowing its ID or having an EXPORT role in U. Personal use works with the same model.
An application may also have deliberately global reference/control-plane data.

These are independent requirements:

- **Isolation:** does this row/object belong to the current tenant?
- **Authorization:** may this actor perform this operation within that tenant?
- **Additional resource rule:** is this particular document assigned/shared/unlocked?

A tenant filter does not prove membership or grant rights; an Allow does not remove
the tenant filter. Account billing and provider-side customer identity do not decide
which product rows are readable.

## Agreed provisioning and membership baseline

- A user is a global identity. A tenant is a stable personal/shared data boundary.
  A membership links a user to a tenant with tenant-local roles; no organization
  entity is required. One user can have different roles in several tenants.
- The normal personal self-registration flow verifies the new user, then provisions
  the first tenant, its initial tenant-administration membership and the Free offer.
  Define atomic/idempotent provisioning and recovery so retries cannot create extra
  tenants, memberships or subscriptions; exact implementation is still pending.
- Accepting an invitation joins the existing tenant and does not force creation of
  another personal tenant. Additional tenant creation and invitations use explicit
  protected usecases, not unrestricted side effects of login/account selection.
- Verified tenant selection precedes product operations. Leaving a tenant or disabling
  a membership removes user access, not the tenant's shared data. Subscription changes
  retain the tenant identity; data retention/deletion is a separate lifecycle.
- Initial administrative membership manages that tenant under defined rights. It is
  not a platform-wide override or an automatic grant of every business permission.
- **No platform administrator/superuser capability ships in the initial SaaS scope.**
  There is no human-facing cross-tenant browse/impersonation UI or hidden isolation
  bypass. Necessary provisioning, migrations and durable workers use narrow technical
  entrypoints with explicit routing; they are not an all-powerful account role.

This baseline is accepted conceptually. Role names/rights, account/tenant storage
relationships, verification/provisioning boundaries and retention details still need
their respective implementation contracts and tests. No implementation completion is
implied by this agreement.

## Tenant lifetime versus subscription lifetime

Keep TenantId, account identity and SubscriptionId distinct. Recommended initial SaaS
assembly: a stable tenant for a personal/shared workspace, a current base offer
(free/trial/paid as agreed later), and optionally separately defined add-ons. This is
a proposed billing composition, not a requirement that every tenant already has a
paid subscription or that all modules support multiple simultaneous subscriptions.

A subscription grants benefits to a configured beneficiary, normally that tenant in
the simple SaaS assembly. It can determine the allowed capacity/usage, but the tenant
owns the isolated product data independently of renewal, upgrades or provider changes.
Do not create a new tenant on plan change, erase data on failed payment/cancellation
or require new tenant data migrations merely because SubscriptionId changes.

Quota/capacity allocations need an explicit owner, source and lifetime: for example,
a tenant's export budget for one defined usage period, funded by its current offer.
Do not blindly key usage only by provider subscription ID or reset it on upgrades/
resubscription. Combining base offers/add-ons, billing versus calendar periods, unused
allowance and cancellation behavior remain D05–D07 decisions. Users in a shared tenant
normally consume its common allocation unless a separate per-user budget is defined.

For multiple products/workspaces or a payer covering several tenants, model the
beneficiary relation explicitly; do not infer universal one-tenant-per-subscription
cardinality. Exact initial support is a reviewed SaaS-module decision.

## Small declaration; automatic aggregate boundary

Agreed declaration form for the next implementation (not current language):

```text
id InvoiceId;
value InvoiceNumber(String);
tenant aggregate Invoice[InvoiceId](InvoiceNumber);
repository InvoiceRepository for Invoice;
```

`tenant` is the agreed modifier; it is not implemented yet. The author declares the
isolation characteristic once, on the aggregate. The aggregate need not declare a
business `accountId`/`organizationId` field or receive a tenant input in every factory.
Repositories and contained entity/value/relationship tables inherit the root boundary.
Value types remain reusable across tenants; their type definitions are not tenant data.

Existing usecase/repository API intent remains:

```java
var invoice = self.invoiceRepository().byId(invoiceId);
self.invoiceRepository().save(invoice);
self.invoiceRepository().delete(invoice);
```

No `inAccount()` or repeated predicate is required for basic tenant safety. Explicit cross-tenant transfers are not ordinary
save operations and are outside the initial automatic isolation mechanism.

Omitting `tenant` retains existing unscoped persistence semantics: that aggregate's
repository neither requires nor checks a tenant context, even if a context is active.
No second `global` keyword or mandatory explicit-global declaration is required.
A separate usecase permission requirement can still require actor/scope context;
unscoped persistence is not anonymous/public authorization.

The SaaS assembly/release audit must inventory unmarked roots and flag accidental
shared sensitive data for review, but must not reject a model merely because an
intentionally global aggregate omits `tenant`. No marker is inferred from a name,
namespace, module activation or having a TenantId/AccountId business field. Global
roots still need suitable permissions and deliberate exposure. This distinction must
remain visible in exported module metadata and generated schema plans.

## Trusted execution and transaction context

Authenticate the actor, verify that it may enter a requested tenant, and establish a
typed immutable tenant context. Do not accept an arbitrary header/path/session UUID
as proof. The tenant choice may originate from a route, login/account selection or
technical job, but each entry has an explicit trust/verification path.

Nested usecases inherit the tenant and actor. Tenant switching within one physical
transaction is forbidden; no ordinary Access setter or admin-role bypass. Background
workers and provider callbacks establish a tenant from verified stored routing and
explicit technical authority, never from an unverified payment payload alone.

Bind tenant context to the physical transaction and all supported JDBC access. Missing,
ambiguous or changed tenant context fails closed before tenant reads/writes. A pooled
connection/thread MUST NOT retain a prior tenant after success, rollback or failure.
Database bootstrap/provisioning and deliberately global control-plane operations need
separate documented paths; they do not run by selecting a null/default tenant.

## Repository and runtime guarantees

| Path | Required isolation |
| --- | --- |
| byId | ID plus current tenant; foreign and missing IDs have the same not-found result |
| find / predicates / all-or-any query expressions | Tenant condition AND the complete user predicate; OR must not escape the tenant condition |
| Offset/cursor pages and totals | Tenant-scoped items, count, continuation and cursor binding; reject cursor replay under another tenant |
| Insert/save new root | Stamp technical tenant ownership from trusted context, never caller-controlled field data |
| Update/optimistic version | Match current tenant, root identity and version; do not turn an invisible row into an upsert in another tenant |
| Delete/orphan cleanup | Constrain root, child, collection and reachability operations to the same tenant |
| Batched graph loading | Include tenant in every root/child/relationship lookup and identity map |
| Existing Java object saved elsewhere | Detect stored ownership mismatch; never silently retag/move a loaded root or child |

Foreign-ID not-found behavior must not reveal whether another tenant has a row.
Saving a new arbitrary/reconstituted instance is not automatically a safe way to
import another tenant's state: define provenance/ownership rules for all factories,
reconstitution, unsaved roots and update-versus-insert paths. No implicit tenant
transfer is provided. Global ID uniqueness, if retained, must not create misleading
cross-tenant conflict diagnostics. These details are blocking D13 decisions.

Application-authored SQL is outside a generated-query-only guarantee. Define supported
raw-JDBC/administrative paths and their boundaries explicitly. Evaluate PostgreSQL
row-level security as database defense in depth before claiming isolation for raw SQL.
RLS has owner/superuser/BYPASSRLS exceptions and does not replace tenant-safe foreign
keys. The technical proposal requires RLS plus generated predicates and separate runtime/
migration roles; transaction-local context setup still needs executable tests;
this plan does not claim that enabling an RLS checkbox solves all paths.

## Storage, references and migrations

Use a generated technical tenant discriminator in shared tables, outside business
field naming and ordinary setters. The technical proposal chooses UUID "@tenantId".
Every tenant root, contained entity and owned list/set table must be attributable to
one tenant; foreign keys/uniqueness/indexes must prevent cross-tenant links. The technical
proposal retains global root UUID primary keys and adds tenant-qualified owned
keys/foreign keys. Verify actual generated DDL, including relationships.

Ordinary aggregate references remain IDs, but existence/reference operations cannot
use an ID alone to cross a tenant. Deliberate references to global data need explicit
semantics; global roots cannot automatically own tenant children. Shared VO definitions
and imported module types do not carry a tenant merely because they are imported.
Exported aggregate persistence metadata must carry tenant/global classification.

Adding tenant ownership to existing rows requires reviewed backfill/provisioning and
application/module migration ordering. Do not assign all historic data to an arbitrary
first user or generate a fabricated tenant silently. Foreign application tables remain
outside Vernac ownership. Verify initial DDL, upgrade candidates, replay/schema-check
and fresh module consumers; preserve the migration review/responsibility contract.

## All SaaS paths, not only repositories

- Scope permissions, limits, capacity, quota ledgers and reservation idempotency keys
  appropriately; no one tenant can exhaust or inherit another tenant's allocation.
- Persist trusted tenant routing for tenant-owned events/outbox deliveries; workers
  may discover global work through a privileged runtime coordinator, but execute each
  handler under its tenant context. Define global events separately. Preserve tenant
  identity across retries/restarts without storing bearer tokens.
- Carry verified tenant attribution through payment reconciliation, subscriptions,
  customer invoices and mail jobs; do not trust provider metadata without verifying
  its stored local association. A subscription payer need not be the sole tenant member.
- Protect downloadable invoice/export files, storage keys, caches and UI page loaders;
  database filtering alone cannot secure an unscoped download URL or cached result.
- Keep tenant identifiers and sensitive business data out of ordinary logs. Use the
  approved safe correlation design rather than logging raw context or row data.
- No platform-admin feature is required in this release. Necessary technical runtime
  coordination has narrow explicit authority, never a hidden `ignoreTenant` flag
  exposed to normal repositories or policies.

## Corrected invoice example

With a tenant-aware Invoice, the normal policy checks EXPORT for the actor/current
tenant and the normal repository performs the isolation. It does not need to load
Invoice solely to compare an organization/account field. `on invoiceId` is reserved
for additional resource-specific rules, such as assigned reviewer or draft status,
when the application actually needs them. Those reads use tenant-aware repositories.

An ordinary permission can therefore suffice:

```text
usecase ExportInvoice(InvoiceId)
    returns ExportLocation
    requires permission InvoicePermissions.EXPORT
    uses InvoiceRepository, InvoiceExporter
behavior {
    execute {
        var invoice = self.invoiceRepository().byId(invoiceId);
        return self.invoiceExporter().export(invoice.invoiceNumber());
    }
}
```

This refers to surrounding proposed permission/tenant declarations and a configured
export adapter. It is not a newly supported executable example. `byId` is automatically
scoped; a different tenant's ID produces ordinary not-found, not a leaked document.
Account/tenant policy composition remains explicit but cannot disable this boundary.

## Release acceptance and next contract work

The [technical contract](tenant-technical-contract.md) now proposes the D13 transaction,
storage, provenance, RLS and worker mechanics. Review/feasibility proof and module
integration remain open. The `tenant` spelling, omitted-marker semantics and
no-platform-admin scope are agreed. It is now a prerequisite to freezing the resource-policy integration
and to implementing the SaaS persistence-dependent slices, not a post-release option.

Mandatory tests include two personal tenants and one multi-member tenant; all generated
CRUD/query/pagination paths; OR predicates/count/cursor isolation; shared child graphs;
cross-context object save; reference/uniqueness constraints; concurrent allocation;
connection/thread reuse; nested calls; worker/callback restart; files/caches; fresh
and upgraded app/module schemas; missing context and unauthorized tenant selection.
Use actual PostgreSQL and the final runtime database role, not only mocked predicates.

Add a mandatory TENANT acceptance suite and release gate; no tests are implemented by
this document. See A19/A20 in the release ledger. Security/tenancy and language-tooling
coverage are cross-cutting requirements of every subsequent implementation PR.

Primary technical reference for the RLS decision:
[PostgreSQL 17 row security](https://www.postgresql.org/docs/17/ddl-rowsecurity.html).
