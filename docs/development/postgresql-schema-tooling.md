# PostgreSQL schema tooling implementation

## Boundaries

`SchemaBuilder` consumes the resolved multi-file project. Repository targets are
resolved in their source-file import scope. It builds `SchemaModel`, not SQL strings
inside domain generators. Entity containment uses aggregate-scoped state and relationship tables.

`ScalarMappings` describes every approved built-in explicitly. `SqlNames` owns naming
and quoting. `MigrationPlanner` diffs models into a reviewable SQL candidate.
`SchemaHistory` serializes a linear, fingerprinted source history. No absolute source
paths or local timestamps occur in model content; migration filenames alone carry time.
`schema`, `migration` and `schema-check` are explicit Maven goals without default
lifecycle bindings. No production database is touched by generation.

The reviewed project pipeline generates repositories through `JdbcRepositoryGenerator`.
`StoragePlan` is captured during schema building, including the exact columns, optional
presence witnesses, owner keys, table filters and dependency depths. `JdbcMapping`
contains explicit typed getter/factory bindings; `JdbcAggregateStore` executes JDBC
without reflection. Compile and schema goals read the same history and enum-code overrides.
Query syntax remains subsequent work.

## Candidate planning

Create schemas without deleting existing schemas. Create table shells before adding
foreign keys. Drop affected constraints before altering/dropping columns or tables;
recreate constraints only after the target table shapes exist. Primary key changes
are rejected for explicit handling. Do not emit CASCADE drops.

Required new columns are added nullable, backfilled with an explicit reviewed SQL
expression and then marked NOT NULL. Known numeric widening is automatic. Other conversions require explicit USING expressions.
Drops and conversions are force-gated. Enum code removal is flagged because existing
rows may be incompatible; force does not transform those rows automatically.

The candidate planner is intentionally conservative. It does not guess renames,
backfills or business transformations. A successful empty-database replay does not
prove a migration works with real data, lock contention or deployment overlap.
The developer is solely responsible for review, testing and approval before applying
any candidate. Every candidate and generation command repeats this responsibility.

## Model history

Format and mapping versions are explicit (currently 1). JSON output has canonical
map/list order. SHA-256 fingerprints identify model content. Each snapshot records
its predecessor and resulting fingerprint plus the SQL filename. Files are created
with CREATE_NEW under a local generation lock. Failed paired writes remove only the
new SQL created by that attempt. This is not a filesystem crash-transaction guarantee.

A stale `.generation.lock` after an interrupted process requires checking that no
generation is running before removing it. Never commit this transient lock.

History reading detects missing SQL, corrupted snapshots, incompatible versions and
branch divergence. Reviewed SQL may change while unpublished; its resulting shape is
verified by PostgreSQL replay, while Flyway governs applied migration checksums.

The current builder preserves enum codes from the previous snapshot. Complete explicit
JSON overrides support intentional renames without domain persistence helpers.
Changing the mapping algorithm in the future requires a deliberate mapping-version
upgrade and migration, not an incidental compiler upgrade rewrite.

## Verification and limitations

Unit tests cover naming, Unicode shortening, scalar coverage, recursive presence,
collections, enum codes, delta planning, force gates and source history conflicts.
Maven tests exercise initial and next candidate generation with real parsed source.
Opt-in PostgreSQL integration tests run with `VERNAC_PG_TEST_URL`,
`VERNAC_PG_TEST_USER`, `VERNAC_PG_TEST_PASSWORD`; the test role needs CREATEDB.
The tests create temporary databases and check migration replay, owned drift, shared entity references, list/set keys and cross-aggregate foreign keys.

The production `schema-check` goal uses similarly isolated databases and standard
Flyway SQL migrations. External application tables are ignored. Catalog comparison
is structural; domain reconstitution, representative historical data, permissions,
operational performance and concurrent transactions require separate verification.
The standalone persistence demo now tests JDBC round trips and rollback behavior. Java-based Flyway migrations are not loaded
from the application's classpath in this first tooling increment.

## Entity graph mapping

SchemaBuilder interns entity state tables by fully qualified type **within one root**.
The expansion stack detects recursive containment; the completed-table map permits
shared references. Entity collection tables are attached to the owner's state table,
not to a root traversal path. Otherwise a shared parent's children would be persisted
more than once. Cross-namespace types keep their identity in the logical table name;
the existing UTF-8 length rule applies unchanged.

The legacy generator is not used by the project pipeline. The persistence demo's
opt-in database tests exercise the reviewed generated adapter: shared Java identity,
conflicting snapshots, removing one versus all references, list order and rollback.
Scalar codec tests additionally cover the supported Java families and precision edges.
