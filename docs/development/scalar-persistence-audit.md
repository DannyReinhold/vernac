# Scalar persistence and query equality audit

Date: 2026-10-10. Scope: `ScalarMappings`, `ScalarCodec`, JDBC binding/reading in
`JdbcAggregateStore`, and generated VO equality. Query implementation is still pending.

## Decision and evidence boundary

Equality predicates must reproduce generated Java equality for supported persisted
values. Sorting has its own documented contract; it need not reproduce Java natural
ordering. Do not make application developers implement scalar-specific comparisons.

This audit includes code inspection, Java 25 executable probes and pgJDBC 42.7.5
conversion probes. It is not a PostgreSQL end-to-end verification. A local PostgreSQL
installation was unavailable; no database test is claimed here. Existing codec tests
exercise encode/decode without going through JDBC and cannot prove storage fidelity.

## Findings by family

| Types | Existing representation | Finding / required work |
| --- | --- | --- |
| boolean; byte, short, int, long; wrappers | BOOLEAN / bounded integers | Exact within Java ranges; equality can use SQL equality. Keep byte range constraint. |
| char / Character | INTEGER UTF-16 code unit | Preserves even an isolated surrogate as a char; do not convert to a text column. |
| UUID and Vernac IDs | UUID | Equality is suitable; do not add implicit UUID ordering. |
| String | TEXT | Exact equality needs deterministic collation. TEXT cannot hold NUL or malformed Unicode; reject unpaired surrogates before encoding to prevent replacement. |
| Currency | ISO code TEXT | Exact code equality; reconstruction depends on the JDK recognizing the currency code. |
| ZoneId | Identifier TEXT | Compare exact identifiers, not equivalent zone rules; reconstruction depends on an installed zone provider recognizing the ID. |
| BigInteger | Integral finite NUMERIC | Exact within PostgreSQL numeric capacity, which is not Java's arbitrary precision domain. Add explicit representability checks. |
| BigDecimal | NUMERIC plus original scale | Correct equality requires numeric value AND scale. Numeric order can ignore scale. Check PostgreSQL representability before binding. |
| float / Float; double / Double | REAL / DOUBLE PRECISION | Do not exclude searches wholesale. Preserve signed zero and implement wrapper-equivalent NaN/zero handling; verify actual JDBC text and binary paths. |
| Year, Month, DayOfWeek, ZoneOffset | Integer components | Exact representation; equality straightforward. Temporal meanings should not be guessed from integer storage. |
| LocalDate | DATE | SQL range differs from Java range; pgJDBC has infinity handling. Need a finite supported range and pre-binding checks. |
| LocalTime | TIME(6) plus nano remainder | Truncation before JDBC avoids rounding to 24:00. Probe at LocalTime.MAX passes conversion round trip. Compare both components. |
| LocalDateTime | TIMESTAMP(6) plus remainder | Precision layout good; range handling currently unsafe (see reproduced failure). |
| Instant | TIMESTAMPTZ(6) plus remainder | Precision layout good; current atOffset(UTC) also fails at Instant.MIN/MAX before JDBC. Define finite range explicitly. |
| OffsetDateTime | Instant, remainder, offset seconds | Compare all components; same instant alone is not Java equality. Check representability of both input and UTC conversion. |
| OffsetTime | Local time, remainder, offset seconds | Correctly avoids unsupported direct JDBC OffsetTime mapping; compare all components. |
| ZonedDateTime | Local date/time, remainder, offset, zone ID | Sufficient identity components for the current rules. `ofStrict` can reject old values after zone-rule changes. Do not silently reinterpret them. |
| Duration | Seconds BIGINT, nanos INTEGER | Preserves normalized Java representation, including negative/subsecond durations. Compare both. |
| Period | Years, months, days INTEGER | Preserve components; 12 months does not equal 1 year in Java. Do not replace with normalized SQL INTERVAL. |
| YearMonth, MonthDay | Integer tuples | Exact; compare all components. |
| Vernac enums | Mapped code TEXT | Reuse captured code mapping and exact text equality, never ordinal ordering. |

Optionality is separate from scalar equality. Absence uses the resolved mapping's
witness or marker. Present values with optional contents must never be confused
with an absent wrapper. Multi-value and collection predicates remain deferred.

## Reproduced failures and positive checks

Using the actual current ScalarCodec and pgJDBC 42.7.5 TimestampUtils:

```text
LocalDateTime year=-5000 wire=-infinity equal=false
LocalDateTime year=-6000 wire=-infinity equal=false
LocalTime.MAX wire=23:59:59.999999 equal=true
```

The two different finite Java timestamps collapse in the driver's text conversion.
This is already a persistence problem, not merely a future WHERE-clause issue.
The probe does not require a database and does not establish other wire paths.

Additional Java probes:

- `Instant.MIN.atOffset(UTC)` and `Instant.MAX.atOffset(UTC)` throw DateTimeException.
- UTF-8 encoding/decoding with replacement does not preserve an unpaired surrogate.
- The two Berlin 02:30 values during the 2026 autumn overlap have different offsets
  and are not equal, despite identical local date/time and zone identifier.
- Wrapper `+0.0` and `-0.0` are unequal.
- `Period.ofMonths(12)` and `Period.ofYears(1)` are unequal.

## Recommended mapping strategy

Keep native SQL types and the existing decimal-scale, nano, offset and zone
components. Do not replace every scalar with opaque bytes merely to cover the full
Java value domain: doing so loses useful SQL semantics and readability.

Before advertising exact equality, introduce explicit storage-boundary validation:

1. Validate finite temporal representability before any JDBC conversion. Apply the
   same policy when binding search parameters. Cover both stored local and UTC forms.
2. Reject NUL and malformed UTF-16 before TEXT encoding. Do not normalize valid text.
   This is a PostgreSQL storage restriction, not a ban on Unicode Vernac identifiers.
3. Validate BigInteger/BigDecimal numeric capacity without silent rounding. Account
   for original scale, negative scales, zero and extreme magnitudes.
4. Fail with a contextual persistence error identifying the mapped field/type;
   do not leak raw user values or let JDBC silently coerce them.
5. Reject database special temporal values not produced by the accepted mapping,
   rather than accidentally treating them as valid domain timestamps.

Exact finite bounds and exception names must be specified in the implementation
patch and tested at both endpoints. A narrower contract should be deliberate, not
an accidental side effect of driver version or conversion path.

For floating-point equality, first test the existing storage. A PostgreSQL-specific
candidate is binary float representation equality for non-NaN values, with all NaNs
explicitly treated as equal. This distinguishes signed zero without requiring a
new domain API. It is a candidate, not an implemented or benchmarked guarantee.
If actual transport fails to preserve equality-relevant bits, change the mapping
(e.g. canonical bit components) before adding search support. NaN payloads need not
be preserved because Java wrapper equality does not distinguish them. Numeric
sort order may leave signed zeros tied; document NaN placement separately.

## ZonedDateTime and historical zone rules

The stored local timestamp, offset and zone ID capture the original information.
However, `ZonedDateTime.ofStrict` validates it against today's ZoneRules. If rules
change, a formerly valid tuple can become invalid. Adding another database column
alone does not fix this Java reconstruction constraint.

Keep the original tuple and report an explicit failure; never silently select a new
offset or local time. For fixed historical timestamps, OffsetDateTime/Instant plus
an explicitly modelled zone identifier may be the better domain representation.
A long-term historical ZonedDateTime policy needs a separate decision (including
zone-rule versioning if required). Do not claim unconditional cross-JDK restoration.

## PostgreSQL acceptance matrix before query delivery

- Generated repository round trips, not just direct codec tests.
- Equality predicates with both positive matches and deliberate near misses.
- Driver prepareThreshold=0 and binary prepared execution, multiple session time zones.
- Floating zeros, subnormals, finite extrema, infinities, NaNs of different payloads.
- Dates before epoch, leap days, year zero, finite range boundaries and rejected values.
- Nano remainders 0/1/999; end of day; timestamps straddling a microsecond boundary.
- Same instant/different offsets; both overlap offsets; second-granularity offsets.
- Zone aliases; absent zone provider and changed-rule behavior explicitly documented.
- Decimal equal magnitude/different scale; negative scale and storage capacity edges.
- Strings with case differences, whitespace, composed/decomposed Unicode, non-BMP;
  NUL and isolated surrogates must fail before lossy encoding.
- Duration negatives and Period non-normalized/mixed-sign components.

## Follow-up order

1. Correct and test scalar storage boundaries and query equality prerequisites.
2. Implement the agreed basic declarative searches and their diagnostics.
3. Add LIKE-like operations: literal contains/startsWith/endsWith versus explicit
   wildcard patterns, escaping, and case sensitivity must be specified separately.
4. Add alternative/locale-aware sorting. Pattern searches have higher priority.
5. Revisit custom JDBC searches only when a concrete requirement calls for them.

## Primary sources

- https://jdbc.postgresql.org/documentation/query/
- https://github.com/pgjdbc/pgjdbc/blob/REL42.7.5/pgjdbc/src/main/java/org/postgresql/jdbc/TimestampUtils.java
- https://www.postgresql.org/docs/17/datatype-datetime.html
- https://www.postgresql.org/docs/17/datatype-numeric.html
- https://www.postgresql.org/docs/17/datatype-character.html
- https://www.postgresql.org/docs/17/collation.html
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Double.html
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/ZonedDateTime.html
