# Java repository extensions

## Supported boundary

Generated JDBC repositories implement `JdbcRepositoryExtensionSource<Root, RootId>`
in addition to their ordinary domain repository interface. `jdbcExtensions()` returns
an explicit infrastructure capability; the domain interface stays free of JDBC types.
No repository `behavior` or `implemented by` syntax is introduced. Prefer ordinary
[declarative queries](repository-queries.md) when they express the query.

Inject the generated bean by its existing bean name and the source interface, not by
its concrete adapter class. This also works with Spring JDK transaction proxies.
Obtaining the capability requires no transaction. Every read, count and write requires
the normal application transaction, correct runtime DataSource and verified scope.
Generated usecases provide that transaction; an HTTP-selected tenant alone does not.

For an application with `Invoice`, `InvoiceId` and `Invoices`, a handwritten Java
helper can contain the following methods (the fixture below executes their equivalents):

```java
private final JdbcRepositoryAccess<Invoice, InvoiceId> invoices;

InvoiceSearch(JdbcRepositoryExtensionSource<Invoice, InvoiceId> source) {
    this.invoices = source.jdbcExtensions();
}

List<Invoice> matchingTitle(String title) {
    var query = JdbcRootQuery.of(
        "SELECT id FROM invoice WHERE title = :title", Map.of("title", title));
    return invoices.list(read -> read.roots(query));
}

long countMatchingTitle(String title) {
    return invoices.count(JdbcRootQuery.of(
        "SELECT id FROM invoice WHERE title = :title", Map.of("title", title)));
}

CountedOffsetPage<Invoice, Invoices> page(OffsetRequest request) {
    var query = JdbcRootQuery.of("SELECT id FROM invoice", Map.of());
    return invoices.page(read -> read.countedOffset(query, request, Invoices::of));
}
```

SQL table/column names are the generated physical schema's names: inspect the generated
migration rather than inferring arbitrary mappings from this short example. Qualify
injection when several generated sources share a root type. Bind domain value objects
as their native persisted values; do not hand a nominal object to JDBC.

## Root selection and complete hydration

The SELECT supplies one UUID column named `id`. Vernac wraps it as an identity filter
on its canonical root query. Framework-owned outer predicates and PostgreSQL RLS enforce
scope before loading, including `count` and `exists`. A missing developer tenant predicate
does not remove these checks. Scope initialization checks the physical transaction and
restricted database role before any query. Extra application-owned tables need their own
explicit protection; this API cannot infer an ownership model for them.

Vernac loads complete roots through the existing storage plan, including flattened value
objects, ordered lists, shared entity identities, optional fields and exact scalar types.
Do not write RowMappers, reconstitute roots manually or supply technical metadata.
Duplicate selected IDs do not duplicate canonical results. Root order is **ID ascending**,
or ID descending with `query.descending()`; ORDER BY inside the selection does not change
this order. Arbitrary custom sort keys and projections are outside this increment.

`JdbcRootQuery` copies bounded named parameters (maximum 64): native immutable scalars,
null and flat lists up to 1,000 values. Strings, SQL and aggregate parameter size are
bounded. Mutable SQL timestamps are normalized. Sets, nested collections and arbitrary
Java objects fail with `INVALID_QUERY`. Values must be bound, never interpolated from
HTTP input. The SQL itself is trusted developer code, not a parser-verified sandbox.

## Result shapes and lifetime

- `single(read -> read.byId(id))` preserves missing-root failure.
- `optional(read -> read.optional(id))` gives absence for missing/foreign roots.
- `list`, `set` and `collection` validate every returned root; `collection` accepts the
  generated immutable named list (`Invoices.of(...)` in the example).
- `page` accepts only a page created by the same read context's `offset`,
  `countedOffset` or `cursor`. Offset does not count; countedOffset does. Cursor requires
  an explicit `JdbcCursorCodec`; configure a persistent key for restart continuity.
- `count` and `exists` return framework-computed scoped root facts, not arbitrary
  caller-provided scalars.

Flat collection reads are bounded by the generated repository's maximum page size
(default 1,000). More results fail with `RESULT_LIMIT_EXCEEDED`; use pagination instead
of silently truncating. Page size checks and cursor binding reuse the normal
[pagination contract](pagination.md), including schema, filter, tenant and ordering.

A synchronous read context is valid only during its callback, on its original thread
and physical transaction. Do not retain it or cross REQUIRES_NEW. Results are eager.
Before returning, the boundary checks exact loaded object identities and complete
unchanged storage images. NEW, DETACHED, cached, foreign or manually reconstructed
objects cannot pass. Nor can invented pages/totals, duplicate roots or roots modified
inside the read callback. Invalid state is rejected, never adopted or repaired.

The checks protect supported API operations; arbitrary application Java can still
access other facilities. This is not an adversarial Java sandbox or proof that unrelated
custom SQL is safe. Do not replace a generated repository with an unguarded implementation
and assume it inherits these guarantees.

## Behavior and writes

Read a canonical root, return it from the read boundary, invoke its normal domain
behavior, then call `access.save(root)` in the same normal transaction. `delete(root)`
also delegates to canonical persistence. Root expected versions, graph ownership,
metadata and child writes retain ordinary optimistic locking; a stale root cannot write
its children independently. The extension provides no raw JDBC handle or child-only
write shortcut.

A read root has RESTORED provenance, positive version and known ownership. Public
reconstitution produces DETACHED state; creation produces NEW state. These are distinct,
not tenant fields to stamp manually. Failed database transactions do not rewind Java
objects: discard mutated roots after rollback instead of promising their reuse.

Storage failures exposed by this boundary have a safe classification without SQL,
parameters or driver causes. Normal tenancy, absence, validation, pagination and stale
write failures keep their existing contracts. `jdbcExtensions` is a reserved technical
method name and cannot also name a declarative repository query.

## Executable evidence

`TenantGeneratedPostgresTest` compiles the real generated adapters and
`persistence/tenants/TenantProbe.java.txt` helper. Its extension cases cover all result
shapes, full aliased graphs, omitted tenant predicates, scoped counts, foreign absence,
NEW/DETACHED/foreign/dirty returns, escaped contexts, wrong DataSources, JDK proxies,
parallel pooled tenant requests, and stale canonical writes. The dedicated
`repository-extensions.yml` workflow requires actual PostgreSQL execution and rejects
skipped tests. Local fixture compilation without PostgreSQL is not that evidence.
