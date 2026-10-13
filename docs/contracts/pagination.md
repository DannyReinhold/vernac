# Pagination contract

Status: **runtime, offset and cursor JDBC repository pagination implemented in 0.4.0-SNAPSHOT**.
Paginated usecase results, adapter-local DTO page fields and HTTP response mappings
are also implemented. Technical request types remain Java APIs, not global domain
built-ins; modeled inputs continue to use application-defined domain values. Ordinary, unqualified
find declarations retain their existing collection/singleton API.

## Purpose and boundaries

Pagination limits one retrieval while preserving an explicit order. Repository
pagination loads whole aggregates; a usecase may eagerly project
those aggregates to immutable values. A completed page contains data, not an open
JDBC cursor or a deferred database computation.

The runtime lives in `org.vernac.runtime.pagination` and has no JDBC, Spring or JSON
dependency of its own. Technical wrappers are immutable Java records/classes;
contained aggregate instances remain ordinary mutable domain objects. No in-memory
rollback or cross-request snapshot is implied.

## Ordered collection types

Generated named lists implement `org.vernac.runtime.DomainList<T>`, which extends
Iterable<T> and exposes their existing unmodifiable `asList()` view. Generated sets
do not implement DomainList. This adds no public mutation and no extra copying of
elements. It lets a pagination result retain its concrete named collection type
while statically requiring ordered entries. Handwritten DomainList implementations
MUST obey the same structural immutability and reference-retention contract.

Page types have an element parameter and a named-list parameter, for example
`OffsetPage<Issue, Issues>`. Application code can normally use `var`. They expose
`items()` as Issues, rather than degrading it to Java List<Issue>.

## Requests

| Type | Supported construction | Meaning |
| --- | --- | --- |
| PageNumber | `PageNumber.of(long)` | Positive, one-based number |
| PageSize | `PageSize.of(int)` | Positive requested number of entries |
| OffsetRequest | `ofPage(long, int)` / `ofPage(PageNumber, PageSize)` | Convert a numbered page to a zero-based offset |
| OffsetRequest | `ofOffset(long, int)` / `ofOffset(long, PageSize)` | Non-negative arbitrary window offset |
| CursorRequest | `first(int)` / `first(PageSize)` | Start traversal |
| CursorRequest | `after(PageCursor, int)` / `after(PageCursor, PageSize)` | Continue; page size may change |
| PageCursor | `PageCursor.of(String)` | Preserve a nonblank token unchanged |

For size 20, page 3 and offset 40 are equivalent. Offset 30 starts halfway through
numbered page 2 and ends halfway through page 3. `pageNumber()` returns OptionalLong:
present exactly when the offset is divisible by the requested size. No floor rounding
or hidden page-number adjustment occurs. An aligned offset whose one-based page
number cannot fit in a long is rejected. Page/next-offset arithmetic detects overflow.

Invalid numeric input raises DomainValidationException. Required-null violations are
programming errors under the null-marked API. PageSize.checkMaximum(maximum) rejects,
rather than clamps, a request above an explicit positive application maximum.
Generated JDBC repositories bind `vernac.pagination.max-page-size` with default
1000 through constructor injection. Every page load, including lazy continuations,
MUST check this limit before issuing SQL. Non-positive configuration raises
PaginationContractException. Manual Java construction may supply the positive limit
as the repository constructor's second argument; the one-argument constructor uses
1000. The limit applies to paginated queries, not to existing unpaged finds.

These supplied technical types currently work in Java bodies through `java imports`.
They are not yet Vernac built-ins for modeled fields, usecase parameters or results.
An application-defined VO with the same purpose is a different type and requires an
explicit value projection. No recognition by spelling is permitted.

## Materialized result variants

| Result | Navigation | Counting |
| --- | --- | --- |
| OffsetPage<T, C> | offset, pageSize, optional pageNumber, hasNext, nextRequest | No count API |
| CountedOffsetPage<T, C> | Same navigation | totalElements, totalPages, derived hasNext |
| CursorPage<T, C> | pageSize, nextCursor, nextRequest | No count/page-number API |

Each result also exposes its request and concrete items collection. Constructors and
`of(...)` factories reject inconsistent metadata with PaginationContractException,
a VernacTechnicalException. These are loader/programming defects, not business refusals.
Entries MUST be non-null and their number MUST NOT exceed the requested size.
A continuation requires a full page. A cursor continuation equal to the incoming
token is rejected as failure to advance; this is not a complete cycle detector.

For a counted result, totalElements is the count before applying the offset/limit.
The returned count of entries MUST equal min(pageSize, max(totalElements-offset, 0)).
An empty result set has totalElements=0 and totalPages=0. An offset beyond the end
returns an empty list but retains the true total and requested page number. It is
not moved to the last page. Ceiling division MUST NOT overflow.

A counted loader MUST produce entries and total from the same data snapshot.
The result checks cardinality, but cannot establish database isolation itself.
Independent page requests are not required to observe one unchanged dataset.

PageCursor is an opaque transport value, **not proof of valid query context**. Its
factory only rejects blank input. It does not decode, sign, validate ownership,
check expiration or authorize access. Its toString deliberately omits the token;
value() exposes it for deliberate transmission. The JDBC cursor provider applies the context/encoding checks specified below;
manually supplied CursorQuery implementations must define their own token contract.

## Eager projection

All three variants provide:

```java
var summaries = page.mapItems(
    IssueSummaries::of,
    issue -> IssueSummary.of(issue.id(), issue.issueTitle(), issue.issueStatus())
);
```

The target named-list factory is explicit: Java cannot derive an arbitrary collection
class from the element mapper or an inferred plural name. Mapping is eager, loads no
additional pages, and preserves the result variant, request and count/cursor metadata.
The factory MUST retain all mapped element references in the same order. Reordering,
filtering, replacing entries, null mappings or a null collection are contract errors.
Select/filter entries in the query, not after pagination while retaining its old count.

## Query objects and lazy sequential traversal

OffsetQuery<T,C>, CountedOffsetQuery<T,C> and CursorQuery<T,C> are functional interfaces.
`page(request)` loads one completed page; `pages(request)` creates an Iterable of
pages. `pages(request).items()` flattens their entries in page/entry order.

Creating the page sequence, creating its iterator and calling the page iterator's
hasNext() perform no load. next() loads one page, checks that its request matches the requested window, and
obtains its continuation.
Flattened-item hasNext() may load the next page to establish whether an item exists.
There is no prefetch, parallel traversal, automatic retry or collection of all rows.
Repeated iteration starts a new traversal and repeats loads; results are not cached
or a snapshot. Iterators are not thread-safe. After a load/continuation failure, that
iterator is exhausted rather than implicitly retrying. Normal exhaustion raises
NoSuchElementException on next(). Mutation through iterator.remove is unsupported.

Generated offset/cursor queries and application/test loaders implement these interfaces. A JDBC-backed sequence must be consumed inside its active transaction.
It MUST NOT escape a usecase into a serializer or another thread. Each completed page
may be projected and returned; a lazy sequence may not. Traversing many pages within
one usecase still holds a long transaction.

## Planned transport and application integration

A usecase Result may need several independent paginated projections alongside normal
values. Pagination will be a component type, not a restriction to one top-level page.
Each component carries its own navigation and, when selected, count. Domain entities
and aggregates will remain forbidden as usecase response payloads.

The HTTP adapter must map each completed component to explicit DTO entries and
technical metadata. It must not serialize aggregates, lazy sequences, SQL internals
or arbitrary cursor payloads. None of this Result/HTTP syntax is accepted yet.

## Offset repository declaration and execution

```text
find offset Issues withStatus(IssueStatus status)
    where issueStatus = :status order by createdAt desc, id asc;
find offset Issues countedWithStatus(IssueStatus status)
    where issueStatus = :status order by createdAt desc, id asc counting;
```

The result MUST be an explicitly declared list of the repository's aggregate.
The compiler MUST reject optional singleton/set results, missing ordering, and an
order whose final field is not the root `id`. The direction of that final key is
explicit or defaults to ascending, like other keys. Ordering supports the existing
scalar rules, plus root id as this pagination tie-breaker. PostgreSQL UUID ordering
is used; it does not imply business chronology. Optional scalar sort keys are allowed
for offset queries and sort NULLS LAST in either direction. No hidden key is appended.
`counting` without `offset` MUST fail. Existing predicate typing, boolean grouping,
text search and exact-equality rules apply unchanged.

Generated methods return `OffsetQuery<Aggregate, NamedList>` or, with counting,
`CountedOffsetQuery<Aggregate, NamedList>`. They bind their explicit filters once.
Each page load MUST require the active usecase transaction, even if the query object
was obtained earlier. No query object/lazy sequence may bypass MANDATORY semantics.

A count-free load selects at most pageSize+1 root rows; only the requested entries
are reconstructed, and the extra root establishes hasNext. A counted load selects
matching roots and their count in one PostgreSQL statement using a materialized CTE
and a left-joined page. This retains the count for an empty/out-of-range window.
Counts MUST count roots, not joined collection or entity rows.

Children are then batch-loaded and root versions checked, as for ordinary finds.
A concurrent update/delete of a selected root can cause an optimistic locking failure
rather than a mixed-version graph; there is no implicit retry or transaction isolation
change. Writers MUST use the aggregate persistence mechanism, which updates the root
version for graph changes. Direct SQL bypassing that contract invalidates this check.
A concurrent insertion after the root-window statement does not change that page's
total, even though a later page can see it. READ COMMITTED does not provide a frozen
multi-page dataset: inserts/deletes or changed sort keys may shift later offsets.

Counting necessarily examines matching roots. Its opt-in cost and PostgreSQL's
statement plan are not a performance guarantee. Applications should use appropriate
indexes and sensible page sizes for their actual data volumes.

## Cursor repository declaration and boundary

```text
find cursor Issues following(IssueStatus status)
    where issueStatus = :status order by createdAt desc, id asc;
```

The declaration returns CursorQuery<Aggregate, NamedList>; `page(CursorRequest.first(20))`
loads a CursorPage. The same declared-list and explicit final-id rules apply as to
offset queries. **Every cursor sort field MUST be required**, not optional; the
compiler rejects nullable sort keys. Filters may still refer to optional fields.
All existing supported ordered scalar families and root id are permitted.
`counting` is supported only for offset queries and MUST fail on a cursor query.

The loader selects pageSize+1 roots, reconstructs only pageSize complete graphs,
and emits a nextCursor only if a lookahead root exists. An empty/final page has none.
The token stores the last **returned** root's ordered storage components; no database
lookup of that root is needed to continue. Deleting it MUST NOT invalidate a valid
boundary. New rows inserted after the boundary may appear; rows inserted before it
are not revisited. Moving sort keys can cause repetitions/omissions. There is no
cross-request snapshot, implicit retry, backward navigation or absolute page number.

Continuation is exclusive lexicographic comparison. Each key uses its declared
ascending/descending direction. Multi-component scalars use the same physical keys
as ORDER BY (including nanosecond remainders). Prefix equality MUST follow ordering
equivalence: BigDecimal scale participates in ordinary equality filters, but not in
numeric ordering or cursor-prefix equality. Strings retain PostgreSQL C collation.
The final UUID key breaks equal sort tuples using PostgreSQL UUID order.

## Cursor format, query binding and configuration

The provider's format is `v1.` plus unpadded Base64url of a 12-byte random nonce and
AES-256-GCM encrypted payload (128-bit authentication tag). The payload contains only
explicitly supported scalar storage components; never Java object serialization or
arbitrary class instantiation. Boundary contents are encrypted, not merely encoded.
Each token authenticates a deterministic context digest covering codec version,
fully qualified repository method identity, the complete owned SchemaModel fingerprint,
root table, predicate SQL, ordered key descriptors/directions and canonical bound
filter components. Parameter order is canonicalized, decimal scale and floating-point
zero signs retained. Changing any binding MUST invalidate the token. A schema change
elsewhere in that model also invalidates it deliberately and conservatively.
Page size is excluded: the next request may use a different permitted size.

Malformed, modified, wrong-key, wrong-filter/query/model or unknown-format cursors
raise InvalidCursorException (a DomainValidationException) with a generic message.
Neither token nor key is included in that message. Input token/payload handling has
a 1 MiB representation limit; excessively large boundary/filter components are
unsupported and fail explicitly. This is a technical cursor representation limit,
not a restriction on ordinary domain String values or unpaged/offset retrieval.

`vernac.pagination.cursor-key` accepts standard Base64 of **32 random bytes**. The
generated JDBC constructor injects the property; manual construction with cursor
queries can supply it as the third argument after jdbc and maximumPageSize. Invalid
configured key encoding/length raises PaginationContractException without echoing
its value. A blank property selects one cryptographically random **process-local**
key, shared by default repositories. This is convenient for local development;
cursors then become invalid after a process restart and across separate replicas.

Production deployments that need continuation across restarts/replicas MUST supply
the same durable key through their secret/configuration management. Do not commit it
or expose it to clients. Key replacement intentionally invalidates outstanding
cursors; transparent rotation/multiple-key acceptance and expiration are not supplied.
A token is **not authorization** and is not bound implicitly to a logged-in user:
the application MUST reapply permission/tenant constraints on each call. Tenant and
permission filters bound into a query participate in its context like other filters.
Copies of a valid token are replayable within their matching query context.

Size limits, active-transaction checks, shared child identity and final root-version
verification apply as to offset loads. A concurrent selected-root update/delete may
raise an optimistic-lock failure. Do not return lazy traversal outside its transaction.

## Application results and transport pages

Usecase result positions accept `offset ValueList`, `counted offset ValueList` and
`cursor ValueList`, either as the single result or as individual tuple components.
They map respectively to OffsetPage<Value, ValueList>, CountedOffsetPage<Value, ValueList>
and CursorPage<Value, ValueList>. The declared list MUST contain IDs, VOs or enums;
aggregate/entity entries, sets, scalars without a named list, optional pages and mut
components MUST fail. Query aggregate pages MUST be eagerly projected with mapItems
before returning them. PageSequence/query objects are not permitted result types.

```text
returns (Title heading, counted offset Summaries openItems,
    counted offset Summaries closedItems, cursor Summaries activity)
```

Every page is independently requested/materialized. A Result retains its existing
record/of factory, non-null required components and ordinary optional-field rules.
Default component names still derive from the underlying type; repeated collections
require distinct explicit names. A single page is returned directly; no implicit
Result record is created. The qualifier is an explicit type contract, not inferred
from Java expressions. These qualifiers are unavailable in persisted model fields,
ports or usecase input signatures. Application PageNumber/PageSize/CursorToken values
remain nominal VOs; explicitly project their values into runtime request factories.

Adapter schemas accept `offset list<T>`, `counted offset list<T>` and `cursor list<T>`
for fields, where T is an approved scalar or local DTO schema. An outer page field
may follow the ordinary optional-field rules. Page entries MUST NOT be nullable,
containers or PATCH instructions; PATCH page fields and cyclic DTO containment fail.
Page formats are fixed technical envelopes, not globally registered domain types.

| Wire variant | Fields |
| --- | --- |
| offset | items, offset, pageSize, hasNext; pageNumber only for aligned offsets |
| counted offset | offset fields plus totalElements and totalPages |
| cursor | items, pageSize; nextCursor only when continuation exists |

Metadata names stay in lowerCamelCase; outer declared field naming/aliases apply
normally. No internal request, query object, domain aggregate or Optional wrapper is
serialized. Generated codecs explicitly encode/decode page envelopes without global
ObjectMapper changes. Unknown additional JSON fields remain tolerated; wrong scalar
shapes, null entries and inconsistent metadata fail as DtoContractException. Reading
a cursor DTO does not authenticate its token; only the query provider does that.

A page assignment maps the complete source page, optionally `using <entryMapping>`.
The named mapping applies to each entry, preserves its position/multiplicity and
copies the metadata unchanged. Existing exact scalar/VO unwrapping applies when no
named mapping is needed. Different page variants, implicit page-to-list flattening,
DTO-to-domain page reconstruction and traversing page internals in declarative
mapping paths MUST fail; use explicit Java for deliberately different semantics.
Domain-page to DTO-page and DTO-page to DTO-page projection are supported. Root HTTP
responses remain named local schemas; a single usecase page can be a field in one.
The JsonPages/transport records serve the generated scoped codecs, not an implicit
application-wide Jackson module or permission mechanism.

Serialization MUST perform no additional repository work. A returned page already
contains immutable projected entries. Multiple board components do not imply one
shared frozen dataset: each query keeps its own selection/count contract, and the
existing usecase transaction's isolation applies across calls. Failed selected-root
version checks still raise optimistic-lock failures; no automatic retry is added.

HTTP defaults distinguish invalid cursors as 400/INVALID_CURSOR with a safe generic
message. Ordinary domain validation stays 422 and optimistic-lock conflicts 409.
No cursor/key/exception detail is included. A changed query/model/key requires the
caller to start a new traversal. CursorPageDto.toString reports continuation presence
without copying the token into logs.
