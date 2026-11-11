---
title: Declarative tenant isolation — required 0.6.0 foundation
---
# Declarative tenant isolation — required 0.6.0 foundation

Status: **accepted 0.6.0 scope addition; detailed contract proposed, not implemented**.
This supersedes the assumption that invoice/account filtering in an authorization
policy can provide normal SaaS isolation. Tenant isolation is required alongside the
[authorization contract](usecase-authorization-contract.md), not a later optional feature.
See the [delivery plan](saas-0.6.0-plan.md) and [release ledger](release-0.6.0.md).

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

## Small declaration; automatic aggregate boundary

Candidate syntax for review (not current language):

```text
id InvoiceId;
value InvoiceNumber(String);
tenant aggregate Invoice[InvoiceId](InvoiceNumber);
repository InvoiceRepository for Invoice;
```

`tenant` is a proposed modifier, not a chosen final keyword. The author declares the
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

Existing non-SaaS models without this marker must not silently change interpretation.
The SaaS assembly MUST require an explicit tenant/global classification for reachable
persistent roots, including module metadata, rather than treating an accidental
omission as global data. Decide the spelling of explicit global classification and
module opt-in before implementation. Global roots are still permission protected.
No implicit rule makes all users, reference data, plans or subscriptions tenant-owned.

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
keys. The final decision, runtime role and transaction-local context setup need tests;
this plan does not claim that enabling an RLS checkbox solves all paths.

## Storage, references and migrations

Use a generated technical tenant discriminator in shared tables, outside business
field naming and ordinary setters. The physical column/type spelling is still open.
Every tenant root, contained entity and owned list/set table must be attributable to
one tenant; foreign keys/uniqueness/indexes must prevent cross-tenant links. Decide
composite keys versus globally unique identities and tenant-scoped uniqueness from
actual generated DDL, including relationships, before writing the storage contract.

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
- Global administration is a separately authorized capability with explicit routing,
  not a hidden `ignoreTenant` flag exposed to normal repositories or policies.

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

D13 must resolve classification syntax, tenant identity/provisioning, transaction
binding, physical mapping, factory/provenance rules, RLS/raw-SQL scope and global
administration. It is now a prerequisite to freezing the resource-policy integration
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
