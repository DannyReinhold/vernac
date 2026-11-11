---
title: Keep each tenant's notes private
---
# Keep each tenant's notes private

A SaaS may store many customers' data in the same database. A repository must never
return another customer's objects just because their IDs are known. Vernac's 0.6.0
development compiler can establish this boundary for local aggregate persistence.

This tutorial covers that persistence boundary, **not a finished SaaS starter**.
Registration, membership verification, tenant modules and durable tenant events
are still being implemented. Use matching 0.6.0 development artifacts.

## Declare the data boundary

```vernac
namespace example.notes;

id NoteId;
id PlanId;
value Text(String);

tenant aggregate Note[NoteId](Text) list Notes;
global aggregate Plan[PlanId](Text);

repository NoteRepository for Note {
    find Notes all() order by text;
}
repository PlanRepository for Plan {}

usecase AddNote(Text) returns NoteId uses NoteRepository behavior {
    execute {
        var note = Note.create(text);
        self.noteRepository().save(note);
        return note.id();
    }
}
```

Each Note belongs to one tenant. Plans deliberately remain shared reference data.
You still call byId/save/search normally; no tenant argument or handwritten tenant
predicate appears in the model.

Put the application definition beside the POM:

```text
application example.notes {
    tenancy explicit;
}
```

Now a bare persisted aggregate is a compiler error. Both a module producer and an
application can enable the rule for their own persisted aggregates; a consumer's
setting never reinterprets imported types.

## Generate and review the database candidate

From the application project, inspect the expected schema, then create a migration:

```shell
mvn vernac:schema
mvn vernac:migration "-Dvernac.migrationDescription=initial_notes"
```

The quotes make the property argument usable in PowerShell too.
Review the generated SQL and snapshot. Tenant tables contain a technical @tenantId
UUID, tenant-qualified relationships and PostgreSQL RLS policies. Global Plan tables
do not acquire that boundary. IDs and domain getters remain ordinary domain values.

The candidate is a starting point for review. You alone are responsible for checking,
testing and approving it before application; force and schema-check provide no
deployment guarantee. See [migration workflow](postgresql-migrations.md) for the full
workflow, including disposable replay.

## Separate migration and runtime database identities

A runtime login must be a non-owner, non-superuser without BYPASSRLS, owner-role
membership, schema CREATE or TRUNCATE privileges. Provision reviewed USAGE and normal
DML grants for the application tables. Generated migration SQL does not create users
or embed credentials.

Migration credentials are separate and intentionally privileged to perform schema
changes and required backfills across tenants. FORCE RLS also affects a normal
table owner; use an appropriate reviewed migration role/bypass strategy rather than
assuming ownership alone can see all rows.

Spring Boot can use separate credentials:

```yaml
spring:
  main:
    lazy-initialization: false
  datasource:
    url: ${DB_URL}
    username: ${DB_RUNTIME_USER}
    password: ${DB_RUNTIME_PASSWORD}
    hikari:
      auto-commit: true
  flyway:
    url: ${DB_URL}
    user: ${DB_MIGRATION_USER}
    password: ${DB_MIGRATION_PASSWORD}
```

Tenant repository beans verify the database after initialization. Constructing them
inside a request transaction, using an incompatible autocommit baseline, an unsafe
role or a changed policy fails clearly. Existing unscoped apps do not gain these role
requirements simply by updating Vernac.

## Establish trusted selection before the usecase

Authentication and membership verification must determine which tenant the current
actor can use. Never pass a raw request header into openVerified.

For a controlled integration test, a trusted fixture can select its own known identity:

```java
var testTenant = TenantId.of(UUID.randomUUID());
try (var selection = TenantContext.openVerified(testTenant)) {
    var noteId = addNote.execute(Text.of("A note for this test tenant"));
}
```

Here addNote is the generated Spring bean; its execute method opens/joins the normal
REQUIRED transaction. The fixture is explicitly trusted. This code neither registers
a tenant nor authenticates a user; production needs the verified identity integration.

The first tenant repository operation binds selection to the transaction's actual
JDBC connection. Different tenant repositories share that binding. Global repository
operations stay global even within the same transaction. Closing the scope does not
permit switching a bound transaction to another tenant.

## What happens on mistakes?

- No selected tenant: repository access fails; it never falls back to global access.
- A foreign or missing ID: byId gives the same normal not-found outcome.
- Saving a loaded foreign root or child: ownership rejects it before graph writes.
- Reusing a cursor under another tenant: the cursor is invalid.
- Removing tenant in explicit mode: compilation fails. Explicitly changing to global
  reaches a sensitive migration transition, currently blocked even with force.
- Adding tenant to existing unscoped tables: a reviewed data assignment is required;
  automatic classification conversion is not implemented yet.
- Recorded/deferred events in tenant context: fail clearly until tenant envelopes and
  delivery routing are integrated. Synchronous immediate listeners are supported.

The [integration record](../development/tenant-persistence-integration.md) lists exact
implemented paths and remaining work. This boundary is one foundation of SaaS,
not a substitute for authorization or a claim that all 0.6.0 features are complete.
