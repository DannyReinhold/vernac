# Declarative repository queries

## Status

Implemented in the project compiler, JDBC runtime and LSP. PostgreSQL integration
tests are opt-in and must be run against the supported PostgreSQL deployment.
Existing `byId`, `save` and `delete` remain available. Custom JDBC searches,
pagination and query projections remain deferred.

## Declaration and result contract

```vernac
aggregate Tour[TourId](Title title, TourStatus status,
                      DispatchReference? reference) list Tours;

repository TourRepository for Tour {
    find Tours matchingStatus(TourStatus status)
        where status = :status
        order by title asc;

    find Tour? matchingReference(DispatchReference reference)
        where reference = :reference;

    find Tours createdBefore(Instant cutoff)
        where createdAt < :cutoff
        order by createdAt asc;

    find Tours withoutReference()
        where reference is absent
        order by updatedAt desc;
}
```

The snippet assumes the referenced domain types have been declared. Method names
are chosen by the developer and never parsed to infer predicates. Fields on the
left refer to the aggregate model; `:name` refers to a declared method parameter.
Logical `id`, `createdAt` and `updatedAt` refer to root identity and metadata;
physical SQL names never appear in query declarations. Technical persistence
version is not a query field in this increment.

- A declared aggregate collection returns zero or more complete aggregates.
- `Tour?` generates `Optional<Tour>`: zero gives empty, one gives the aggregate,
  more than one gives a technical `NonUniqueQueryResultException` .
  Include repository and method in the diagnostic; do not expose parameter values.
- This cardinality expectation is not a uniqueness constraint and cannot prevent
  concurrent inserts. Unique modelling and schema constraints are a separate feature.
- `byId` retains its existing missing-aggregate exception contract.
- No non-optional singleton custom query in the initial increment.
- Collections must already be declared for the searched aggregate. Do not generate
  one implicitly. Unknown or incompatible result collections fail at the declaration
  with a useful explanation, not a later Java compilation error.
- A root appears at most once in a query result. Repeated entities inside its
  contained lists retain their original multiplicity and shared Java identity.
- A usecase supplies the transaction; repository operations remain MANDATORY.
  Load complete aggregate graphs with existing reconstitution validation and
  concurrent-change detection. Never return a partial result after a loading failure.

## Initial expression scope

Explicit `=`, `!=`, `<`, `<=`, `>` and `>=` comparisons combined with `and`, `or`, `not` and parentheses; `is absent` / `is present` for
optional root fields supported by this increment. Range predicates apply to the types with ordering support in the table below,
including root `createdAt` and `updatedAt` (Instant).
No implicit conversion between distinct Vernac types, even if their scalar
representations match. A non-optional parameter may compare to an optional field;
absence does not match that parameter, for either `=` or `!=`. Optional query parameters are deferred.

Value predicates address root IDs, enums and single-value objects directly
wrapping one required supported scalar. Multi-value equality, nested field paths, nested VO
wrappers, entity traversal and collection predicates are deferred with explicit
compiler diagnostics. Arbitrary Java types, expressions, SQL and projections are
not accepted. `is absent` refers to model presence, not to an arbitrary nullable
storage column; any later extension must reuse the storage plan's presence rules.

## Two kinds of order

A persisted contained list has owner-local positions. Loading that list orders by
its stored position. These positions do not define an order between aggregate roots
returned by an unrelated search.

| Query | List result | Set result |
| --- | --- | --- |
| Explicit `order by` | Preserve declared order | Compiler error |
| No `order by` | Compiler warning; order unspecified | Allowed; order unspecified |

Never append an implicit UUID, timestamp or other tie-breaker. Equal sort keys leave
relative order unspecified. The presence of `order by` means a declared ordering,
not a proven total order; no uniqueness proof is required in this increment.
For optional sort values, absence is always last, for ascending and descending
order. Direction defaults to ascending. A singleton result does not accept ordering:
it must diagnose multiple matches rather than hide them by choosing the first.

No pagination API is introduced yet. A later limit must count aggregate roots,
not joined entity rows, and address ties explicitly.

## Equality contract

For supported values in the persistence mapping's supported domain, a query matches
if and only if the reconstructed field value equals the parameter according to the
generated Vernac/Java `equals` implementation. Both false positives and false
negatives violate the contract. Use the same resolved types, column components,
enum codes and scalar codecs as persistence; do not reconstruct mappings from names.

Current `ValueObjectGenerator` uses `Objects.equals` for all fields, including boxed
primitive arguments. Consequently floating-point equality is wrapper equality,
not primitive `==`. `BigDecimal` equality includes scale. A comparison must cover
all identity-relevant components, not only the main column.

### Scalar capability table

This table concerns the scalar inside a single-value object. Primitive and boxed
forms share the policy. Ordering is deliberately a narrower capability than storage.
`=` describes equality; `!=` negates that comparison for present values.
Range comparisons use the same order as `order by`.

| Scalar | `=` implementation | `order by` and range comparisons |
| --- | --- | --- |
| boolean / Boolean | Boolean equality | Deferred |
| byte, short, int, long and wrappers | Exact integral equality | Numeric |
| char / Character | Stored UTF-16 code unit equality | Numeric code unit |
| String | Exact stored text, deterministic `C` collation | Explicit PostgreSQL `C` collation |
| UUID / Vernac ID | UUID equality | Deferred; no implicit ID ordering |
| BigInteger | Exact integral NUMERIC equality | Numeric |
| BigDecimal | Numeric equality AND stored scale equality | Numeric value; equal magnitudes may tie despite different scales |
| Currency | Exact currency code | Deferred |
| Year | Year component | Numeric year |
| Month / DayOfWeek | Stored numeric component | Deferred |
| ZoneId | Exact zone identifier, no alias normalization | Deferred |
| ZoneOffset | Offset seconds | Deferred |
| LocalDate | Stored date | Calendar order |
| LocalTime | Microsecond time AND nanosecond remainder | Lexicographic time and remainder |
| LocalDateTime | Microsecond timestamp AND nanosecond remainder | Lexicographic timestamp and remainder |
| Instant | Microsecond instant AND nanosecond remainder | Chronological, including remainder |
| OffsetDateTime | Microsecond instant AND remainder AND offset seconds | Deferred |
| ZonedDateTime | Local timestamp AND remainder AND offset AND exact zone ID | Deferred |
| OffsetTime | Local time AND remainder AND offset seconds | Deferred |
| Duration | Seconds AND nanos | Lexicographic seconds and nanos |
| Period | Years AND months AND days, without normalization | Deferred |
| YearMonth | Year AND month | Lexicographic year and month |
| MonthDay | Month AND day | Lexicographic month and day |
| float / Float, double / Double | Java wrapper equality: binary floating representation, with all NaNs treated as equal | Deferred |
| Vernac enum | Equality using the persisted code mapping | Deferred |

String sorting under `C` is a defined technical order, not linguistic sorting and
not a promise of Java `String.compareTo` order for all Unicode values. Equality
performs no case folding, whitespace trimming or Unicode normalization. Textual
components of composite scalars and enum codes require the same exact equality.
These rules do not expand PostgreSQL TEXT's supported character repertoire.

Floating-point equality uses `float4send` / `float8send` to distinguish signed zero
and explicitly matches NaN against NaN, independent of its payload. Float ordering
and range comparisons are deferred. This is not an approximate comparison.
See the [scalar mapping audit](../development/scalar-persistence-audit.md) and
[storage limits](scalar-storage-limits.md). Query parameters use the same codec
and representation checks as writes.

For metadata Instant range comparisons and ordering, compare the complete tuple
(microsecond timestamp, nano remainder). A predicate that only compares the first
column loses ordering within a microsecond. Apply sort direction to both components.

## Compiler and tooling requirements

One resolved query representation must drive semantic checks, SQL generation and
LSP diagnostics. Do not parse query method names or resolve types again in generators.
Check result kind, aggregate identity, field existence, parameter existence/type,
method-signature collisions, operator support and ordering support before generation.
Unsupported syntax or mappings must produce source-located Vernac diagnostics.

The editor must report list-without-order warnings and set-with-order errors,
complete fields/parameters where appropriate and navigate to their declarations.
Generated Java must have the agreed Optional/non-null API and existing NullMarked
policy. Parameter values are bound, never concatenated into SQL. Sort identifiers
come exclusively from resolved model bindings.

## Implementation acceptance tests

- Unknown collection and collection of the wrong aggregate fail clearly.
- Empty, singleton and multiple matches exercise both result contracts.
- Optional absence and presence, plus equality against an absent field.
- Same scalar wrapped by two different Vernac types is rejected.
- Ordered set fails; unordered list warns; ordered list preserves sort keys.
- Ties remain unspecified; no generated UUID ordering; absence is last both ways.
- BigDecimal `1.0` versus `1.00`, including negative scales.
- Strings differing by case, whitespace, normalization form and non-BMP characters;
  verify exact equality even with a database default collation that is not `C`.
- Instant and local time values within one microsecond, including before epoch;
  verify metadata range boundaries and both sort directions.
- Same instant with different offsets or zone IDs; distinct Period components.
- Floating equality distinguishes signed zeros and treats all NaNs as equal; verify JDBC round trips.
- Complete graphs, contained list positions, identity sharing and root deduplication.
- Concurrent updates/deletes during result loading never silently return mixed graphs.

Persistence integration tests must execute on PostgreSQL. String assertions on
rendered SQL alone cannot establish the equality contract. Document the supported
subset, examples, diagnostic remedies and ordering guarantees before release.

## References and next step

Implementation inspected: `ScalarMappings`, `ScalarCodec`, `StoragePlan` and
`ValueObjectGenerator` at baseline commit `96212bb`.

- [Java Double equality](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Double.html)
- [PostgreSQL collation semantics](https://www.postgresql.org/docs/17/collation.html)

The implementation acceptance tests include real generated Java compilation,
PostgreSQL equality/inequality across scalar families, selected range comparisons,
complete graph loading and interleaved update/delete detection. Run them as shown
in the [repository query tutorial](../tutorials/repository-queries.md).

Result membership is determined by the initial root SELECT. At READ COMMITTED,
concurrent inserts can be absent from this result; a change/delete of a selected
root detected during graph loading raises an optimistic-lock exception. There is
no promise of a global serializable snapshot. Root versions are checked after
loading the relations. Readers do not lock every matching root.

Relations are fetched in batches of up to 1000 aggregate IDs per table. Each root
has its own entity identity map, including when different roots contain equal
entity IDs. Optional singleton searches select at most two roots and reject
multiple matches before graph reconstruction.

Parameters must have explicit, unique, non-optional names and must be used. Names
beginning with `__vernac` are reserved for generated query locals. Generated helper
method names are also rejected. Java generics are not repository result syntax.
`find Tours all() order by title;` explicitly selects every root; no WHERE clause
is required when no restriction is intended.

The editor completes root fields and parameters and navigates their references.
Metadata fields navigate to the aggregate declaration. SQL identifiers and raw SQL
are never accepted as query expressions.

Text searches are supported as specified below. Alternative locale-aware sort
orders remain deferred.

## Text searches

```vernac
find Tours matching(String pattern) where title like :pattern order by title;
find Tours containing(String text) where title contains :text order by title;
find Tours beginning(String text) where title starts with :text order by title;
find Tours ending(String text) where title ends with :text order by title;
```

These operators require a String-backed single-value field and a required
`String` parameter. A fragment or pattern need not satisfy the field's domain
invariants. Equality and inequality still require the exact domain type.

`contains`, `starts with` and `ends with` treat percent signs, underscores and
backslashes literally. `like` matches the entire string: `%` matches zero or
more characters; `_` matches one PostgreSQL character, not one UTF-16 code unit.
In the actual parameter use `\%`, `\_` and `\\` for literal percent, underscore
and backslash. Java string literals require the usual additional escaping.
A trailing unpaired escape is an invalid PostgreSQL LIKE pattern.

Searches are case-sensitive with deterministic PostgreSQL `C` collation and
no Unicode normalization. Absent fields never match. An empty literal fragment
matches every present string; an empty LIKE pattern matches only the empty
string. Parameters are bound, never inserted as SQL text.

No schema migration is needed. Leading wildcard searches may scan many rows;
there is no index or performance guarantee. Case-insensitive searches, regular
expressions and configurable collations remain deferred.

## Boolean expressions

Predicates can be composed with `and`, `or`, prefix `not` and parentheses.
Precedence is `not`, then `and`, then `or`. Repeated `not` is allowed.
All branches are type-checked; SQL evaluation order and short-circuiting are not guaranteed.

```vernac
find Tours selected(TourStatus status, Title title)
    where (status = :status or title = :title) and not (reference is absent)
    order by title;
```

SQL three-valued logic applies: a comparison against an absent field is unknown,
including under `not`. Only true matches are returned. To include absent references,
write `reference is absent or not (reference = :value)`. `not (reference is absent)`
is equivalent to `reference is present`. Grouping changes neither result cardinality
nor sorting rules. Values remain bound parameters. No schema migration is needed.
