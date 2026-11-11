---
title: Invoice policy example — superseded isolation assumption
---
# Invoice policy example — superseded isolation assumption

Status: **withdrawn as a normal SaaS tenant-isolation design**, 2026-11-11.

The initial example added AccountId to Invoice and used an InvoiceAccess service
with `inAccount(InvoiceId, AccountId)` to decide whether the invoice belonged to the
current account. This was an ordinary query, not an isolation mechanism: existing
byId/save/delete/query paths remained unscoped. It also assumed an account/ownership
model that had not been agreed. No implementation of it is being shipped.

The replacement is [declarative tenant isolation](tenant-isolation-0.6.0.md), now a
mandatory 0.6.0 foundation. A marked aggregate's normal repository operations enforce
a verified tenant boundary automatically. The developer does not add an organization
predicate or resource policy to every usecase for that purpose.

Resource policies are still appropriate for additional rules such as document sharing,
assignment or state. They can read data through tenant-aware repositories and require
explicit typed registration; they do not supply or disable the basic isolation boundary.
The earlier proposed Java-binding API was illustrative, not an implemented API or a
chosen production registration mechanism. Revisit it after D13 defines tenant context.

See the [authorization proposal](usecase-authorization-contract.md) and
[release acceptance ledger](release-0.6.0.md). A19/A20 and D13 remain OPEN until the
full repository, schema, context, worker and output paths are specified and verified.
