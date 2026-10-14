# Versioned modules: 0.4.0 completion record

The agreed module scope is implemented. This document records its boundaries and
regression entrypoints; the normative rules remain in the [module contract](../contracts/modules.md).
The lifecycle includes ordinary compiled JAR reuse, not just metadata imports.

## Delivered capabilities

| Area | Supported behavior |
| --- | --- |
| Publication | One `.vmodule` per artifact, explicit export closure, original qualified identities, compiled implementations and optional source attachments |
| Consumption | Maven-selected direct/transitive dependencies, internal/public separation, duplicate identity rejection, no dependency regeneration |
| Compatibility | Versioned data-only descriptors, structural public contracts, bounded additive compatibility, actionable incompatible-dependency failures |
| Composition | `.vapplication`, fixed/profile-dependent provider selection, explicit activation and transitive functional dependencies |
| Domain APIs | IDs, VOs, enums, multiple collections, entities/aggregates, repositories, services/usecases, events and outbound adapters |
| Persistence | Producer-owned verified bundles, isolated histories, dependency-ordered module migrations before application migrations, installed-schema validation |
| External values | Consumer-owned flattened columns and independent reviewed migration/backfill when an imported value changes |
| Transactions | One application DataSource/transaction manager; REQUIRED usecases and mandatory repository participation |
| Events | Producer payload storage, application delivery infrastructure, active internal receivers, synchronous failure propagation and durable per-recipient retry |
| HTTP | Internal module controllers and scoped advice, explicit registration, transitive usecase activation, no accidental exposure through scanning |
| IDE | Application/module file types and creation, export/binding/activation completion, type/source/contract navigation, logical module overviews and changed-index refresh |

## Regression entrypoints

Run ordinary tests with current Vernac tools and Java 25. The compiler-generated
application code targets the supported Java release declared by the project.

```text
mvn -pl vernac-maven-plugin,vernac-lsp -am clean test
mvn -f examples/persistent-modules/pom.xml clean install
```

PostgreSQL tests require `VERNAC_PG_TEST_URL`, `VERNAC_PG_TEST_USER` and
`VERNAC_PG_TEST_PASSWORD` for a disposable server on which the test user can create/drop
isolated databases. A skipped database test is not database verification.

| Regression | Main evidence |
| --- | --- |
| TypeModuleTest / TypeModuleExecutionTest | Separate producer/consumer JARs, public contracts, provider bindings, internal functions, HTTP activation and scanned inactive routes |
| ModulePersistenceTest / ModuleMigrationPostgresTest | Bundle integrity, history coordination, restart, cross-owner rollback, partial failure/retry, drift and required-value upgrade/backfills |
| ModuleMigrationPostgresTest (event scenarios) | Shared event runtime, recipient activation, durable rollback/retry and restart |
| ApplicationDefinitionSymbolsTest / ModuleDefinitionSymbolsTest / EventModuleSymbolsTest | Activation overview navigation, dependency source/fallback navigation, index replacement and incompatible-metadata diagnostics |
| DocumentationExamplesTest | Executable tutorial/reference examples and entrypoint consistency |
| ModuleLifecycleBootTest | Real Boot initialization, persistent module/usecase flow, shared event worker and HTTP 201/400/422 responses |

The standalone `examples/persistent-modules` reactor contains Billing, Receipts, a
transport-only API module and an application. It demonstrates one coherent runtime,
then a deliberate exported-value upgrade with separate backfills in each data owner.
The application remains a separate example, not a child of the Vernac reactor.

## Operational boundaries

- Migration candidates require developer review, testing and approval before use.
  Empty-database replay cannot guarantee production-data, locking or deployment safety.
- A failed later migration history does not roll back previously completed histories.
  Retry resumes from installed history; published SQL must not be rewritten.
- Transactions cannot undo external communication or Java-object mutations. Deferred
  delivery remains at least once; external effects need an explicit idempotency policy.
- Public-contract fingerprints are structural checks, not behavioral or unrestricted
  handwritten-Java binary-compatibility proofs.
- Build the consumer after dependency changes. The IDE uses its generated dependency
  index; it does not choose Maven versions or publish artifacts itself.
- Read-only dependency views expose neither local editable definitions nor permission
  to import internal implementation types. Module activation itself is not authorization.

## Follow-up work outside this scope

The [capabilities/authorization plan](capabilities-and-usecase-authorization.md) builds
on these modules: reusable generic subdomains, provider simulators and usecase
permission/quota policies. Ready-made payment, identity or mail capabilities are not
part of the module mechanism itself.

Deeper cross-language/cross-artifact refactoring and debugger integration remain in the
[IDE plan](intellij-deep-integration.md). Also outside this scope are distributed
transactions, multiple application DataSources, tenant-specific adapter selection and
automatic event/data-evolution conversions.
