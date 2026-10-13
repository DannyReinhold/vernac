# Pagination: agreed direction and implementation sequence

Status: runtime foundation, offset/counted and cursor JDBC repository queries implemented.
Usecase result qualifiers and scoped HTTP/DTO page mappings are now implemented.
Technical request headers, dynamic filters and backward cursors remain deferred.
The offset/cursor declarations below are accepted.

## Decisions already agreed

- Offset and keyset/cursor navigation are both explicit choices.
- Results retain named **list** collections of complete aggregates. Sets are invalid.
- Offset supports a count-free variant and a counted variant from the first SQL rollout.
- Counting is explicit, not an automatic count query for every retrieval.
- Page numbers are one-based; offsets zero-based. Both factories are provided.
- Arbitrary offsets need no invented rounded page number. Aligned offsets expose one.
- Typed PageNumber/PageSize avoid mandatory scalar extraction at ordinary call sites.
- Query objects bind filters and offer page(request) or lazy pages(request).
- mapItems explicitly supplies the destination named-list factory and eagerly projects.
- A usecase may assemble several independently paginated projections with other values.
- Pagination does not add arbitrary technical types to aggregate/VO state.
- Filters stay explicit; dynamic optional filters are a separate later increment.

The [runtime contract](../contracts/pagination.md) is authoritative for implemented APIs.

## Implemented repository declarations

```text
repository IssueRepository for Issue {
    find offset Issues withStatus(IssueStatus status)
        where issueStatus = :status order by createdAt desc, id asc;
    find offset Issues countedWithStatus(IssueStatus status)
        where issueStatus = :status order by createdAt desc, id asc counting;
    find cursor Issues following(IssueStatus status)
        where issueStatus = :status order by createdAt asc, id asc;
}
```

The method returns the corresponding query object; pagination is not another declared
business parameter. Examples of the target Java API:

```java
self.issueRepository().withStatus(issueStatus)
    .pages(OffsetRequest.ofPage(1, 100)).forEach(page -> inspect(page.items()));
self.issueRepository().following(issueStatus)
    .pages(CursorRequest.first(100)).items().forEach(issue -> inspect(issue));
```

Offset-qualified repository methods now return query objects. Existing unqualified
finds still return collections directly. Cursor-qualified methods likewise return generated CursorQuery providers.

## Sorting and cursor rules

Pagination requires explicit order by with root id as its final tie-breaker, in either
direction. Do not append an invisible key. Cursor fields initially must be required,
non-optional supported ordered scalars; optional keys are deferred. Root metadata
createdAt/updatedAt is eligible. The same complete sort tuple defines the SQL order
and exclusive continuation boundary, with each declared direction respected.

The token must carry the saved boundary values, rather than require the previously
returned root to still exist. Deleting that root must not invalidate otherwise valid
continuation. Moving a row's sort key between requests can still cause omission or
repetition; neither offset nor cursor guarantees a frozen multi-request snapshot.

A cursor must bind to query identity/version, sorting, model/codec compatibility and
canonical filter values. A token from another query or different filters must fail
clearly. Page size may change without invalidating the boundary. Context checks do
not authorize a request: the usecase must reapply permissions/tenant conditions.

Do not confuse Java equality with ordering equivalence. For example, BigDecimal
1.0 and 1.00 are unequal as domain values but tie under numeric ordering. Keyset
prefix comparisons must follow ORDER BY equivalence, not reuse a scale-sensitive
ordinary equality predicate and skip an equal-magnitude row. String collation and
nanosecond/time components must likewise match the actual ordered storage keys.

The provider now uses authenticated/encrypted v1 tokens with a query/model/filter
context digest. The normative contract specifies its AES-GCM format, configured
32-byte key, process-local default, restart/replica behavior and representation limit.
Runtime PageCursor by itself remains only a carrier.

## Counts and graph consistency

A counted request must keep root selection/count and complete graph loading coherent.
Two separate READ COMMITTED statements do not automatically share a snapshot. The offset implementation uses a single materialized matching-root CTE plus count/page
left join, then existing batch graph reconstruction and root-version verification.
It preserves the existing REQUIRED usecase isolation and raises a conflict if a
selected root changes; it does not silently retry. An offset past the end still
needs the real total, so a naive COUNT(*) OVER() on only returned rows is insufficient.

Use limit+1 or an equivalent explicit technique for count-free hasNext/cursor presence.
Do not return the lookahead root as an entry. Do not reconstruct a phantom aggregate
from a metadata-only count row. Count all matching roots, not joined entity rows.
Retain existing complete-graph, shared-identity and optimistic-lock guarantees.

The configured maximum must be applied by every loader, including lazy continuations.
Generated repositories now bind `vernac.pagination.max-page-size` (default 1000).
They reject excessive requests before SQL; manual construction can supply another
positive limit.

## Multi-component usecase/HTTP results

The agreed shape is a Result containing ordinary values and one or more page components:
project name, open-issue page, closed-issue page, etc. Entries are immutable named
value lists. Preserve each page's metadata through eager projections and explicit DTO
mapping. Do not force all usecase results into one top-level Page.

Offset versus counted-offset versus cursor must remain visible in the static contract.
The implemented qualifiers are offset, counted offset and cursor. The counted
variant is distinct from uncounted offset; do not infer it from a Java body or guess
it from a called repository.
Request types need explicit permitted positions; they are not generic domain built-ins.

A GET loads one page per component, maps its entries and returns a completed result.
The next GET supplies the next number/cursor. It never returns a lazy JDBC sequence.

## Remaining increments and acceptance

1. **Implemented:** offset/counted repository syntax, typed query objects, full-graph
   loading, per-load limits and single-statement counts including out-of-range offsets.
2. **Implemented:** cursor/keyset SQL, authenticated encrypted query/filter/model binding,
   mixed directions, storage-component boundaries and deletion-independent continuation.
3. **Implemented:** required value-list Result components and scoped DTO/GET mappings;
   request headers remain ordinary domain inputs with explicit runtime projection.
4. **Implemented:** complete compiled board tutorial, GET/JSON execution tests and
   Dispatch paging endpoint. A broader standalone Issue Tracker UI remains optional.

Each increment includes contracts, diagnostics, completion/navigation, plugin What's
New, reference/AI reference, relevant FAQs and executable examples. SQL increments
require real PostgreSQL tests with deterministic transaction coordination; skipped
database tests are not acceptance. Runtime tests alone cannot establish SQL isolation.
