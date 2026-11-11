---
title: Technical tenant isolation contract — 0.6.0 proposal
---
# Technical tenant isolation contract — 0.6.0 proposal

Status: **target technical contract; local tenant persistence is partially implemented**.
Implementation status and remaining limits: [local integration](tenant-persistence-integration.md).\nBaseline: 2026-11-11. This refines the agreed [tenant/product baseline](tenant-isolation-0.6.0.md)
and D13 of the [SaaS plan](saas-0.6.0-plan.md). MUST describes the proposed implementation,
the final required behavior, not a claim that every section is implemented. Acceptance A19/A20 remains OPEN. Merging this design
allows the next executable feasibility PR; it does not release tenant support.

## 1. Declaration and technical identity

The `tenant` aggregate modifier marks its complete owned persistence graph.
Omitting `tenant` retains current behavior: no tenant is required, checked or silently
applied by that repository, even during a tenant transaction, unless the project
requires explicit classification. global aggregate explicitly declares the same
unscoped semantics. tenancy explicit in .vapplication or .vmodule requires each
own persisted aggregate to choose tenant/global; it does not change aggregate's
meaning. See the [classification contract](../contracts/explicit-tenancy.md) for
the implemented increment and remaining tenant support. Permissions remain independent.

Introduce runtime `TenantId`, an immutable UUID-backed technical value, in the general
tenancy runtime namespace. It is not UserId, AccountId, SubscriptionId or a business
aggregate ID. It is not auto-imported as an ordinary model field type or universally
reserved simple name. The SaaS provisioning module creates and persists tenant identities;
the general runtime verifies/propagates an already authorized identity. No organization
aggregate, issuer/customer relationship or one-subscription-per-tenant rule is implied.

Generated domain factories, ordinary getters, Read/Write/Access views and equality/hashCode
remain unchanged. A tenant discriminator is technical metadata, not a declared mutable
field, implicit factory argument or part of generated toString. UUID-based domain IDs
retain their current equality semantics and global root-table uniqueness.

## 2. Trusted context and physical transaction binding

Separate **verified execution selection** from **database transaction binding**:

1. A trusted web/worker adapter authenticates an actor, verifies selection/membership
   and establishes immutable execution context. A raw path/header/session UUID is not
   sufficient. Product code cannot replace it through ordinary self access.
2. Spring's existing REQUIRED proxy opens or joins the physical transaction.
3. On the first tenant-aware operation, a tenancy resource coordinator binds the verified
   TenantId to this transaction/DataSource, validates the runtime role/session state,
   and initializes PostgreSQL transaction-local context before any tenant SQL.
4. Each subsequent tenant operation verifies the transaction binding still matches the
   execution selection. Unscoped operations do not require a binding or create one.
5. Completion unbinds runtime resources on commit/rollback; execution scopes restore their
   previous value in finally/close. Connection reuse cannot inherit tenant authority.

Implement with Spring-managed transaction resources/synchronization and the actual
transaction-bound JDBC connection, not a process-global variable, singleton field,
connection checkout default, or unrelated connection. An execution ThreadLocal alone
is not a transaction-binding mechanism. The first implementation supports the existing
single-DataSource, synchronous JDBC transaction architecture; XA/reactive propagation
is not implied. Document/reject unsupported transaction managers/configurations.

REQUIRED nested usecases inherit the binding. A request to switch tenants while a
physical transaction is active fails before tenant SQL; it is not a new scope or a
role override. A transaction may perform both unscoped control-plane and tenant work,
but has at most one tenant once bound. A bootstrap transaction may bind its newly
provisioned tenant once, after authorized tenant/membership creation, before first tenant
SQL; retries and initial activation must be idempotent. This is not switching tenants.

A runtime worker's REQUIRES_NEW phase has its own binding. Implement suspension/resumption
of tenancy resources with Spring transaction resources; a suspended outer binding is
not reused as the new physical connection's state. A new transaction may inherit the
same verified selection and must initialize its own connection. Switching selection
while an outer tenant transaction is suspended is not a supported application bypass.
The spike must prove cleanup/restoration on failure; otherwise fail the configuration.

## 3. SQL and schema shape

All tenant-owned root/entity/value-collection/relationship tables receive:

```sql
"@tenantId" UUID NOT NULL
```

Use the existing reserved technical naming mechanism. This field is excluded from
business flattening and parameter-derived query expressions. Values are always bound
parameters/technical row metadata, never interpolated SQL or an author's where clause.

**Root keys:** retain existing PRIMARY KEY ("id"). Add UNIQUE ("@tenantId", "id")
for tenant-qualified references. This preserves global UUID identity semantics; the
same root UUID is not independently reusable under a second tenant. Tenant-scoped
business uniqueness, if explicitly supported later, includes the discriminator;
this change does not invent uniqueness for existing single-result find methods.

**Owned graphs:** prefix existing owned-table primary keys with @tenantId, retaining
aggregate/owner/entity IDs and list positions. Include the discriminator on both sides
of every generated owned foreign key and its supporting unique key. All entity and
relationship rows therefore belong to the same tenant as their aggregate/owner.
Preserve existing deferred-link semantics and list/set ordering differences.
Technical value-row UUID uniqueness can retain its current global uniqueness.

Root tenant assignment is immutable. No UPDATE statement sets @tenantId. Generated
read and write SQL always uses `@tenantId = :__vernacTenant` in addition to the full
existing ID/version/predicate. The internal parameter cannot be shadowed by a user
query parameter. User boolean expressions are parenthesized as one conjunct:

```text
WHERE "@tenantId" = :__vernacTenant AND (the_complete_user_predicate)
```

Do this centrally in the shared storage/query plan so empty predicates, OR/NOT,
counts, version rechecks, child batches, reachability/deletion and cursor seeks cannot
accidentally omit it. Never infer the constraint from a method name or require authors
to write `inAccount` in place of byId. Unscoped aggregate-owned tables receive no new column/policy; event routing metadata
is addressed separately below.

## 4. PostgreSQL row security and connection contract

Use **both generated predicates and RLS** for tenant tables in the supported PostgreSQL
SaaS deployment. RLS is defense against accidental unscoped SQL, not a replacement for
explicit predicates, domain authorization or tenant-aware foreign keys.

Generate ENABLE ROW LEVEL SECURITY, FORCE ROW LEVEL SECURITY, and one owned policy
with USING and WITH CHECK equivalent to:

```sql
"@tenantId" = NULLIF(current_setting('vernac.tenant_id', true), '')::uuid
```

Before tenant SQL, parameterize `set_config('vernac.tenant_id', tenantText, true)` on
the same connection; `true` makes it transaction-local. No session-level SET, SQL string
concatenation or global search_path trick. Missing/empty context matches no tenant rows;
malformed values fail technically. The repository additionally raises a clear missing
context error instead of silently pretending a missing context is an empty database.

The runtime pool MUST use a role that is not superuser, BYPASSRLS or table owner and
cannot change schemas, policies, roles or table ownership. Give only required DML;
no TRUNCATE/DDL privilege. Use a separate schema/role-bootstrap migration role and connection
for schema changes; it must not become the runtime pool identity. Creating tenant
data during registration uses the restricted runtime path, not schema-owner credentials. FORCE RLS remains
generated even though runtime is not owner. The coordinator verifies supported role/
policy invariants at startup and transaction context state before tenant access.

Reject a pre-existing session tenant setting outside the managed local binding; do not
adopt stale pool state or reset-and-trust it silently. After completion, the connection
must return to an empty/unset baseline; prove this across success, rollback, exceptions,
suspension and repeated pool borrows. A boot-time role/schema failure is a configuration
error, never an opt-out to unfiltered operation. There is no silent RLS-off fallback for
tenant-marked PostgreSQL tables. Unscoped existing applications do not need these roles.

RLS does not constrain the table owner/superuser/BYPASSRLS roles or all operations such
as TRUNCATE, and referential integrity checks bypass row policies. Application SQL
running under runtime privileges benefits from the policy, but malicious Java with
those credentials can try to alter custom session settings. This is not a security
sandbox against arbitrary trusted application code or database administrators.
Explicit custom SQL and administrative scripts must follow their documented boundary;
they cannot advertise automatic tenant context before the coordinator initializes it.

## 5. Repository operation semantics

| Operation | Required behavior |
| --- | --- |
| byId | Select only current-tenant graph; invisible and missing root both raise existing AggregateNotFoundException |
| find, singleton, offsets/counts | Filter roots before cardinality/count/window evaluation; never count another tenant or filter after loading |
| Cursor page | Include tenant identity and tenant/global model classification in authenticated cursor context; cross-tenant reuse yields InvalidCursorException |
| Batch graph load | Bind one tenant, filter every owner/entity/link table and final root-version recheck; graph maps include tenant/owner/type/ID identity where appropriate |
| Insert | Create only a new root with immutable ownership bound to current tenant; no ON CONFLICT tenant transfer |
| Update/save | Match tenant + id + expected version; zero matches retain optimistic-lock semantics without querying other tenants to explain why |
| Delete/orphan cleanup | Same ownership/version validation and tenant predicates for all relationship and row deletes |

No global existence probe is added to distinguish foreign rows. A root-key collision
on insertion is translated to a safe generic persistence conflict without echoing raw
PostgreSQL DETAIL, SQL parameters, tenant IDs or constraint contents. UUIDs are normally
created internally; no permission to clone a foreign identity follows from that fact.

Cursor version/context changes invalidate incompatible old cursors explicitly; do not
silently accept a cursor whose old context did not bind tenant identity. All replica
keys and current freshness checks still apply. No automatic change to transaction
isolation, ordering or count-snapshot guarantees is introduced.

## 6. Java object provenance and factories

The state table below applies to tenant-marked roots. Unmarked roots retain unscoped
origin regardless of an ambient tenant and do not acquire tenant checks. New standalone
entities remain unassigned until adopted by a root; reconstructed/previously adopted
entities keep their root boundary. Entity creation does not guess a root from its type.

Extend technical persistence state with ownership/origin without adding it to domain
interfaces or equality. Track contained entity instances as well as the root so a loaded
child from tenant A cannot be saved inside a new root of B. Immutable scalar VOs do not
carry ownership merely because they appeared in a tenant graph.

| Object origin | Initial ownership | Allowed persistence |
| --- | --- | --- |
| create during a verified tenant execution | Bound to that tenant | Only that tenant |
| create without a tenant context | New/unassigned | First persist binds to tenant or unscoped owner; never reassign afterwards |
| Tenant JDBC reconstruction | Bound from stored @tenantId through trusted adapter restoration | Only matching tenant and persisted identity/version |
| Unscoped JDBC reconstruction | Explicitly unscoped origin | Not silently imported as a tenant-owned root/child |
| Public manual tenant-root reconstitute | Detached/reconstructed, not equivalent to new | Not inserted into tenant storage by treating version zero as NEW |

Manual entity reconstruction remains unscoped for legacy global use and cannot be\nadopted into a tenant graph. Trusted JDBC restoration preserves its stored boundary.\n\nKeep normal create and public reconstitute argument lists. A stored row's tenant is
attached by trusted adapter metadata, not accepted as a caller-supplied business field.
Distinguish NEW versus RECONSTITUTED explicitly rather than deducing it only from
PersistenceState.version. Plain manually reconstituted tenant objects are not writable
until an explicit scoped reattachment validates a current-tenant existing root and
version; the initial slice need not expose that convenience API and may require load
and modify instead. No unreviewed migration/import route is generated automatically.

A mismatched or unresolved ownership state fails before graph SQL. Technical state is
an architectural safeguard, not an unforgeable capability against malicious Java.
Ownership attachment is one-way, including after a failed save/rollback; the existing
contract does not restore in-memory object state. Do not clear tenant provenance on
rollback so that the same object can be retagged elsewhere. Ordinary aggregate objects
remain not thread-safe; concurrent first use must not result in two different owners.

For new contained entities, assign the root's boundary; restored children retain their
boundary. A graph crossing bound tenant/unscoped boundaries is rejected. Reusing entity
*types* in different roots remains possible because storage is root-owned; reusing an
already bound entity instance across tenant boundaries is not.

An ID-valued field is still a value, not automatically a declared relational association.
Do not guess a target aggregate from its name or add FK/existence checks to every UUID.
An author's explicit referenced-object lookup is tenant-scoped; storing a foreign ID
value never causes routing to its tenant. Tenant-safe FKs cover generated owned entity/
collection relationships. Further cross-aggregate reference guarantees require an
explicit relationship contract, not a falsely advertised inference mechanism.

## 7. Modules and lifecycle migrations

Add explicit tenant/unscoped classification to exported aggregate, storage-plan and
required usecase-context metadata. Published compiled code owns its classification;
a consumer cannot relabel it on import. Changes are persistence/security-relevant,
not private-body fingerprint noise. Increment the metadata/storage format as needed,
reject unsupported required formats clearly and keep older unscoped-module semantics
explicit rather than guessing. Source/library attachment does not activate a tenant.

Keep one shared schema/history per application/module as today, not one schema/history
per tenant. Module Flyway histories and owned namespace/table names remain independent.
Module migrations install tables, tenant columns, keys/indexes, policies, grants/role
requirements and checks once; provisioning a new tenant creates data, not DDL.

Extend schema model, JSON snapshots, diff/candidate tooling, replay and live schema-check
for tenant metadata, keys and owned RLS policy/ENABLE/FORCE state. Missing/mismatched
protection is owned-schema drift and must be reported, not ignored as an extra index.
Do not automatically install/change database users or embed migration-role credentials
in generated SQL. Supply documented role/bootstrap provisioning and validate privileges.

For unscoped-to-tenant conversion, generate a candidate only with an explicit reviewed
mapping/backfill for existing rows. Order parent/child data backfill, constraints,
NOT NULL and protection coherently. No first-user/default-tenant assignment. Reversing
tenant classification is a data-boundary collapse and requires explicit destructive/
sensitive-change approval in migration tooling; force alone does not invent a merge
strategy. Keep the existing migration author review/responsibility comments.

### Tenant classification changes in Maven/Flyway candidates

The Maven schema and migration goals MUST compare persisted tenant classification,
not only the current set of columns. Adding or removing the source-level tenant
modifier is an explicit security-relevant schema transition, even if the table name
and business fields remain unchanged. Preserve the classification in versioned
snapshots; do not infer it only from the current compiler model or silently rewrite
history. Apply the same rules to module-owned histories and application-owned tables.

| Transition | Required candidate behavior |
| --- | --- |
| Unscoped to tenant | Require an explicit existing-data assignment/backfill strategy; carry it consistently through roots and every owned table, then establish keys, constraints and RLS. No implicit tenant selection. |
| Tenant to unscoped | Stop normal candidate generation with a specific isolation-removal diagnostic. Explain that formerly separated tenants' data becomes accessible through the same unscoped repository. Require explicit sensitive-change acknowledgement and any necessary collision/data-resolution strategy before producing a candidate. |
| Tenant unchanged | Preserve discriminator, tenant-qualified relationships and ENABLE/FORCE RLS/policies. A missing safeguard is drift, not an innocuous difference. |

An existing generic force mechanism may serve as acknowledgement if the tooling
clearly identifies the isolation-removal operation. It MUST NOT substitute for a
required mapping/backfill or collision-resolution strategy. Removing isolation is
sensitive even when no row or column is lost; a data-loss-only classifier is insufficient.

The blocking diagnostic and, after acknowledgement, both Maven WARNING output and
the generated SQL comments MUST explicitly name the aggregate/table graph and the
removed tenant boundary. A generic destructive-operation or review disclaimer alone
is insufficient. For example:

~~~sql
-- WARNING: TENANT ISOLATION REMOVAL for example.billing.Invoice and its owned tables.
-- Formerly isolated tenants' data will become accessible through the unscoped repository.
-- Review access control, data combination and application deployment compatibility.
-- You alone are responsible for reviewing, testing and approving this migration.
-- Force/acknowledgement does not guarantee correctness or safety.
~~~

Candidates remain reviewable files, never automatically applied by these goals.
Retain the standard responsibility header alongside operation-specific warnings.
Removing the modifier in source does not authorize tooling to silently remove
protection. Already deployed application compatibility and migration ordering require
review; the generator cannot guarantee a safe rolling deployment.

Required regression proofs include both source modifier transitions, root/child/
collection propagation, missing backfill, normal-mode isolation-removal refusal,
acknowledged generation with specific CLI and SQL warnings, snapshot round trip and
real PostgreSQL replay/schema-check for the reviewed transitions. Test that force
alone cannot invent missing assignment or resolution instructions.

## 8. Events, callbacks and global runtime coordination

Keep trusted routing separately from public business event payloads. A durable event
emitted inside a tenant transaction inherits its tenant in internal storage/delivery
metadata; a global emission has an explicit global origin, never an absent tenant
silently adopted to fix a failed tenant operation. Tenant context is not a public
mutable event property and no bearer credentials are persisted.

Payload rows may use nullable technical @tenantId where an event type legitimately
supports both global and tenant-origin emissions; the storage plan records that mode.
Policies permit current-tenant payloads and deliberately global payloads, not arbitrary
other-tenant payloads. Global payloads are not a place for tenant-sensitive data. Payloads
are immutable after recording. The detailed event storage format must expose these
modes in metadata and be checked by the spike, not bypass RLS for loading events.

A narrowly scoped internal delivery coordinator can claim routing rows across tenants.
Those rows contain technical event/listener/tenant/claim identifiers, not readable
business payloads. It has no general bypass capability on tenant aggregate tables.
Claim/processing/failure phases retain the current worker transaction/claim-token
protocol. Before loading a tenant payload, each processing/retry transaction establishes
that delivery's verified technical tenant context. A pooled worker cannot carry A into B.
Global deliveries use the explicit global path and cannot load tenant payloads by ID.

Payment callbacks verify their provider and consult trusted stored local routing, then
start tenant work under a narrowly authorized system actor. Do not switch tenants
inside an already tenant-bound REQUIRED transaction. Global routing/control-plane
lookup can precede binding, with no tenant payload access. Subscription/usage changes,
invoicing and mail jobs then carry the verified tenant through their own durable work.
There is no human platform-admin, impersonation endpoint or `ignoreTenant` repository API.

Files, download handlers and caches must use the same tenant attribution and permission
checks; a safe database query cannot fix an unscoped storage URL. Their module contracts
remain later work but are mandatory A20 acceptance, not optional runtime conveniences.

## 9. Failures, logging and visibility

- Request-level missing login/unauthorized tenant selection uses the authorization
  contract's 401/403 and safe account selection flow before entering tenant work.
- Repository missing/mismatched transaction context is a technical 500 with stable
  TENANT_CONTEXT_REQUIRED / TENANT_CONTEXT_MISMATCH classification, not a free/global
  fallback, empty successful result, customer quota error or arbitrary SQL failure.
- Cross-owner Java object persistence raises technical TENANT_OWNERSHIP_MISMATCH before
  SQL. No actual/expected tenant identifiers or object data appear in public messages.
- Foreign root reads retain ordinary safe not-found; stale updates use existing safe
  optimistic-lock conflict. Invalid tenant-bound cursors retain invalid-cursor handling.
- RLS/configuration/ownership failures are not ordinary DomainValidationException and
  must not be swallowed by a generic 422/domain-rejection mapping. Keep SQL causes in
  controlled diagnostic classification without logging raw provider/database messages.

Operational events contain safe codes, operation name and correlation reference. No
raw tenant/actor IDs, SQL values, credentials or business content in default logs.
Treat technical routing tables and protected local simulators as controlled operational
interfaces, not log dumps. Include seeded-sensitive-value assertions for negative paths.

## First feasibility record (partial)

The [first executable spike](tenant-isolation-spike.md) covers real transaction/resource,
RLS/role and guarded-invocation mechanisms with handwritten fixtures. It does not
implement production tenancy or finish the proof inventory below. A19/A20 stay OPEN.

## 10. Required feasibility proof and implementation order

Inspect the actual current code paths before implementation: JdbcAggregateStore uses
ID/owner-only SQL, PersistenceState currently stores only version, JdbcCursorCodec
binds query/model/parameters but not tenant, and JdbcEventWorker has separate claim/
processing/failure transactions. None currently implements this proposal.

The next PR is an isolated executable JDBC/Spring spike, not a production-grammar
change. Prove the following under PostgreSQL 17 with distinct migration/runtime roles:

| Test ID | Required proof |
| --- | --- |
| TENANT-01 | Bound/missing/forged selection, exact transaction-bound connection, pooled thread/connection cleanup |
| TENANT-02 | REQUIRED nesting; same-tenant REQUIRES_NEW suspension/resumption and rollback cleanup; reject switching |
| TENANT-03 | RLS SELECT/INSERT/UPDATE/DELETE/WITH CHECK; owner/BYPASSRLS misconfiguration rejected; no unscoped-global regression |
| TENANT-04 | Root/child/link FK isolation; unchanged UUID equality/root uniqueness; opaque collision and optimistic conflict |
| TENANT-05 | byId/find/OR/count/offset/cursor/root-version and batch-load isolation; replay cursor under another tenant fails |
| TENANT-06 | NEW/reconstituted/restored object provenance, child reuse, rollback-retained ownership and cross-context save |
| TENANT-07 | Coordinator claim versus tenant payload processing, mixed global/tenant deliveries, retries/restart and callback routing |
| TENANT-08 | Fresh/upgrade module/schema snapshots, RLS drift/schema-check, explicit historical backfill and role/bootstrap instructions |

A thin test fixture may exercise the intended storage mechanisms before production
integration, but must be labelled a spike. Do not mark A19/A20 VERIFIED until production
parser/generator/runtime paths, module consumers and whole-SaaS journeys use them.

If the spike falsifies a choice, revise this contract explicitly before broad generation.
Then deliver shared schema/runtime/query handling, provenance/context, module/events,
compiler/IDE/reference updates and full E2E acceptance in reviewable PRs. No automatic
merge/publication is authorized. Exact tenant/account module coordinates and wider SaaS
policies remain separate decisions; tenant storage mechanics no longer depend on them.

Primary implementation references (not evidence of executed tests):
- [PostgreSQL 17 row security and privilege exceptions](https://www.postgresql.org/docs/17/ddl-rowsecurity.html)
- [PostgreSQL 17 transaction-local SET semantics](https://www.postgresql.org/docs/17/sql-set.html)
- [Spring transaction resource synchronization](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/transaction/support/TransactionSynchronizationManager.html)
