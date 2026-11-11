# Explicit persistence classification

Status: **0.6.0 development: classification rule and local tenant persistence implemented;
module/event integration and historical conversions remain in progress**. Released 0.5.x compilers do not support
these additions.

## Source-controlled project rule

An application MAY declare the following beside its POM:

```text
application example.invoicing {
    tenancy explicit;
}
```

A reusable module MAY declare the same rule in its own publication definition:

```text
module example.shared {
    tenancy explicit;
    export example.shared.PlanId;
}
```

The files are respectively vernac-application.vapplication and vernac-module.vmodule.
The declaration MAY appear before, between or after existing entries. Each file MUST
contain at most one tenancy declaration. Unknown modes MUST be rejected. There is no
environment variable, Spring profile, IDE preference or Maven override for this rule.
If both definitions exist, an explicit declaration in either enables the rule.

## Aggregate meaning and validation

An ordinary aggregate retains its existing unscoped semantics when the rule is absent.
A global aggregate is an explicit spelling of those same semantics; it MUST produce
the same generated Java and relational schema.

```vernac
namespace example.catalog;
id PlanId;
global aggregate Plan[PlanId]();
repository PlanRepository for Plan {}
```

With tenancy explicit, every **locally declared aggregate targeted by a local
persistence repository** MUST have explicit classification. Export visibility does
not exempt private persisted aggregates. Entities inherit their owning graph's
eventual boundary; this rule does not introduce entity modifiers.

A bare aggregate targeted by such a repository MUST produce a compiler error at the
aggregate declaration. The diagnostic MUST name the aggregate and explain that
global is appropriate only for intentionally unscoped data. No implicit tenant,
user, organization or subscription is assigned. A type without a persistence
repository is outside this initial rule.

The complementary spelling tenant aggregate selects tenant-bound factories, the
complete local owned schema graph and tenant-aware generated JDBC repositories.
It requires a trusted TenantContext and a Spring JDBC transaction for repository
operations. A selected tenant alone never changes a global aggregate's meaning.
Do not mark private tenant data global to bypass classification or context checks.

See the [current integration boundaries](../development/tenant-persistence-integration.md).
This is not yet a complete SaaS feature: persistent tenant-module activation,
durable tenant event delivery and reviewed historical classification conversions
remain blocked or OPEN.

Imported definitions retain their published contract. The consuming application's
rule MUST NOT reinterpret them or require edits to their read-only sources. Aggregate scope and entity ownership metadata are preserved by module-contracts-10.

## Compiler and editor consistency

Project analysis reads the application/module definitions and applies the shared
validation rule. Maven compile and schema paths use that project analysis. The LSP
supplies open configuration snapshots so unsaved application/module text overrides
disk for editor diagnostics; builds continue to use saved source. Unsaved model
classification changes participate in the same analysis. Invalid or unreadable
configuration must not silently disable validation.

This is a project validation rule, not authentication, membership verification,
runtime context selection, RLS installation or authorization.

## Wizard and migration boundary

The future separate SaaS starter MUST write tenancy explicit into its application
definition. Existing simple application/library templates remain unchanged. The
Wizard does not enable it yet: the SaaS starter waits for complete tenant support.

Accidentally deleting tenant from a persisted declaration fails this
rule before migration generation. A deliberate change to global then reaches the
[tenant migration safeguards](../development/tenant-technical-contract.md#tenant-classification-changes-in-mavenflyway-candidates).
Global does not authorize a boundary-removing migration by itself. Existing unscoped
aggregate to global aggregate changes no schema and needs no migration.

## Acceptance evidence

Parser/compiler tests cover settings and duplicate detection in both file types,
unchanged global Java/schema output, private persisted aggregates, unpersisted
types and external-source exemption. LSP tests cover unsaved configuration changes,
unsaved global declarations and navigation. Local tenant syntax, graph propagation and generated SQL are implemented. Module
metadata, event routing, identity provisioning and conversion paths remain OPEN.
