---
title: PostgreSQL tenant coordinator — explicit runtime integration
---
# PostgreSQL tenant coordinator — explicit runtime integration

Status: **production coordinator implemented; generated persistence integration remains OPEN**.

This follows the [runtime foundation](tenant-runtime-foundation.md). It moves the
physical-connection mechanism from the experiment into vernac-runtime-jdbc, while the
experiment continues to provide handwritten schema, SQL and authentication fixtures.

## Entry points and lifecycle

Create one JdbcTenantCoordinator for the application's supported DataSource and the
complete set of tenant-owned tables. Table names are PostgreSQL regclass strings;
quoted namespace/table names work. They are bound parameters, never interpolated SQL.

Call verifyRuntimeRole() after migrations and before serving tenant traffic.
It borrows a connection without establishing tenant authority. Missing tables fail.
For every configured root/child/relationship table it rejects superusers, BYPASSRLS,
effective table owners, schema CREATE privilege, TRUNCATE privilege and missing
ENABLE/FORCE ROW LEVEL SECURITY.

This check does not yet validate the exact policy expression, foreign-key shape,
discriminator type or complete table inventory. Those require the upcoming shared
schema/storage plan. It does not create roles, schemas or policies and is not an
automatic Spring Boot configuration in this increment.

Before **every** tenant-aware storage operation, call requireBound(). It:

1. Requires live verified TenantContext and a synchronized Spring transaction.
2. Acquires the actual transaction-bound connection for the configured DataSource.
   Autocommit or a transaction belonging to another DataSource fails.
3. On first binding, rejects a nonempty session tenant, verifies role/table safeguards,
   and parameterizes transaction-local set_config('vernac.tenant_id', tenant, true).
4. On later operations, verifies tenant, connection and database setting still match.
5. Suspends/resumes its resource with Spring REQUIRES_NEW, and releases only its own
   metadata on commit/rollback. Spring retains ownership of the JDBC connection.

Use the same coordinator for these calls; separate coordinator instances are not
interchangeable registrations for the same physical transaction. The supported
architecture remains synchronous Spring JDBC with one application DataSource.
XA, reactive transactions and cross-DataSource atomicity are not provided.

Failures use safe TenancyException codes without retaining SQL/driver causes,
connection URLs, credentials, tenant values or request payloads. No logging is added.
This coordinator does not authenticate or check membership.

## What changes for applications?

Existing generated repositories do not invoke this coordinator yet and stay
unscoped. Merely adding the dependency, selecting a tenant or constructing a
coordinator does not secure arbitrary SQL. The generated tenant marker, central
predicates and schema plan are separate remaining increments.

Trusted custom infrastructure can explicitly use the API after configuring the
restricted runtime role and reviewed RLS schema. Domain code should not manage
the database binding. Normal generated usecase transactions remain REQUIRED.

## Verification

The standalone PostgreSQL project now uses this production coordinator together
with the production TenantContext and TenantOwnership. Its test-only boundary
contains authentication/membership fixture logic, not duplicate JDBC binding.

Fourteen real PostgreSQL/Spring tests cover role rejection, child-table RLS,
privileges, missing schema configuration, transaction/DataSource mismatch, REQUIRED,
REQUIRES_NEW, rollback, pooled connection reuse, parallel requests, stale session
context and setting tampering, as well as the existing ownership/storage scenarios.
The CI gate requires every named proof with no skips.

## Next bounded increment

Introduce the shared tenant storage/schema representation, policy validation and
generated schema changes; then connect generated repository/query operations to
the coordinator and central predicates. Counts, pagination, graphs, modules and
events remain explicit release requirements, not implied by this low-level PR.
