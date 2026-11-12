---
title: Optional JDBC identity directory
---
# Optional JDBC identity directory

Status: implemented in **0.6.0-SNAPSHOT** as a narrow persistence foundation. This
is not a login, registration or email-verification implementation. See the
[identity foundation](saas-identity-foundation.md) and the
[remaining lifecycle work](../development/identity-membership-0.6.0.md).

## Explicit adoption

Add `org.vernac:vernac-saas-identity-jdbc` using the same version as the compiler and
runtime. This separate Apache-2.0 artifact depends on the identity contracts and
Vernac JDBC Spring Boot starter. It does not pull in the identity Spring Security
bridge; add that artifact separately when adopting the standard principal/resolver.
The compiler and general runtime have no dependency on these SaaS modules.

The logical provider module is `vernac.saas.identity.jdbc`. Its only export is
`org.vernac.saas.identity.persistence.StoredIdentityDirectory`. The internal
aggregate and repository are not public Vernac contracts. The port and nominal
UserId remain exports of the separate `vernac.saas.identity` module.

Select the provider in the application's `.vapplication` file:

```vernac-application
application consumer.identity {
    authorization explicit;
    activate vernac.saas.identity.jdbc;
    bind org.vernac.saas.identity.IdentityDirectory
        to org.vernac.saas.identity.persistence.StoredIdentityDirectory;
}
```

The test compiles this composition against the actual packaged module. Importing
types alone does not activate database access or install a filter chain. Application
composition provides the generated repository, adapter and transaction-manager
dependency. Configure the normal application datasource and module-migration
credentials according to the [module contract](modules.md); runtime credentials
need data access, not DDL privileges or access to the migration histories.

## Lookup and transaction semantics

`IdentityDirectory.status(UserId)` returns the current persisted status, or empty
when the identity does not exist. It does not return credentials or grant tenant
membership, roles, permissions or subscriptions. Missing/disabled/closed identities
do not become authenticated actors merely because a principal contains their ID.

The JDBC adapter uses Spring TransactionTemplate with REQUIRED propagation and
read-only requested for a new transaction. An existing transaction is joined;
read-only is a hint, not a new isolation boundary or access-control mechanism.
Reads inside a transaction see its own uncommitted writes and share its rollback.
Outside a transaction a short lookup transaction is opened and completed. The
adapter calls its internal repository directly, never an actor-guarded usecase.
There is no cache of eligibility in the principal and no replacement of tenant
context. Existing isolation semantics still apply: this is not a guarantee that
another transaction's change becomes visible inside every ongoing snapshot.

Database/provider failure must not be interpreted as absence. The standard
IdentityActorResolver converts such failures to the sanitized
AUTHORIZATION_UNAVAILABLE classification without retaining raw JDBC exceptions.
Direct callers of the directory must also treat a technical exception as failure.

## Internal persistence boundary

Identity state is global access metadata, stored in
`"org.vernac.saas.identity.persistence"."Identity"`, keyed by the nominal UserId.
It uses the ordinary generated optimistic version and creation/change timestamps.
It is not a tenant-owned product aggregate and does not establish ownership of
any application object. Its module-owned migration history is separate from the
application history; normal explicit module activation performs migration before
component use and validates it again on restart.

The internal lifecycle offers pending-to-active activation, active-to-disabled
disabling and closure. These operations do not prove email verification; a future
trusted workflow must perform that proof before activation. No write endpoint,
registration factory or generally callable management service is exported. The
internal generic factory/reconstitution APIs are implementation mechanisms, not
registration policy. Applications must not reach into generated internal Java
classes to bypass the module boundary.

The initial migration is generated using the ordinary migration goal and committed
with its schema-history snapshot. As with every generated candidate, the developer
alone is responsible for reviewing, testing and approving it before application.
Automation and successful empty-database replay do not guarantee production safety.

## Verification and remaining work

Post-package integration tests verify export visibility, consumer compilation,
actual Spring Boot module initialization, independent migration/restart, restricted
runtime privileges, REQUIRED transaction participation/rollback, live actor eligibility,
optimistic locking, sanitized lookup failure and recovery after privileges return.
The PostgreSQL proof requires VERNAC_PG_TEST_URL, VERNAC_PG_TEST_USER and
VERNAC_PG_TEST_PASSWORD; CI and release checks reject missing or skipped proof.

The [credential policy](saas-identity-credentials.md),
[credential primitives](saas-credential-primitives.md) and
[private challenge lifecycle](saas-identity-challenges.md) are now available. Persisted
password credentials, complete registration/verification/recovery, login/session
infrastructure, memberships, roles and the final SaaS application remain unfinished. This provider does not close those
release acceptance items.


The private [identity storage-capacity foundation](saas-identity-capacity.md) now adds a
second generated module migration and controlled internal storage-budget primitives.
The export/provider contract above is unchanged; activating this module alone does not
initialize budgets, install registration endpoints or implement the remaining anti-abuse
lifecycle requirements.

The [private challenge lifecycle](saas-identity-challenges.md) adds the third generated
module migration and actual-record budget accounting for one-time challenges. No public
flow or automatic cleanup schedule is enabled by module activation.

The [private global ingress gate](saas-identity-ingress.md) adds the fourth generated
module migration. Explicit initialization/admission remains separate from module
activation and must precede business transactions.
