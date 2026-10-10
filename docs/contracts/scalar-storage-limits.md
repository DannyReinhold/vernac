# Scalar storage limits

Vernac domain values retain their Java semantics. The JDBC boundary additionally
checks whether selected scalar representations can be stored without known loss.
These are persistence restrictions, not domain invariants.

`ScalarCodec.encode` checks the value before creating JDBC parameters. Generated
scalar mappings add the column path to a `VernacTechnicalException`. Messages do
not include the rejected value. No truncation, replacement characters, rounding,
or Unicode normalization is performed to make an invalid value fit.

| Representation | Supported boundary |
| --- | --- |
| String / TEXT | No U+0000; well-formed UTF-16, including supplementary characters. Requires a UTF-8 database. Case, combining sequences and trailing spaces are preserved. |
| char / INTEGER | All Java UTF-16 code units, including isolated surrogates; this is not TEXT. |
| BigInteger / NUMERIC | At most 131072 decimal digits. |
| BigDecimal / NUMERIC + scale | At most 131072 integer digits and 16383 fractional digits in the supplied representation; scale from -131071 through 16383. Trailing zeros count. These conservative checks also apply to zero. |
| LocalDate, LocalDateTime | Local calendar years 0001 through 9999 inclusive. |
| Instant | UTC years 0001 through 9999 inclusive. |
| OffsetDateTime | Both local and UTC calendar years must be within 0001 through 9999. Original offset is stored separately. |
| ZonedDateTime | Local calendar years 0001 through 9999. Local timestamp, offset and zone are stored separately. |
| LocalTime, OffsetTime | Full Java time-of-day precision, including 23:59:59.999999999; offsets stored separately where applicable. |

Timestamp bounds are deliberately narrower than PostgreSQL's range, avoiding
BC/calendar and JDBC infinity conversions. Nanoseconds remain supported through
the remainder column; this is not a reduction to microsecond precision.
`Year`, `YearMonth`, `Duration` and `Period` use numeric components and are not
subject to the native timestamp year restriction.

The decimal check does not expand a large exponent. Scale is preserved as part
of Java BigDecimal equality; `1.0` and `1.00` remain different values.

ZonedDateTime reconstruction uses the current JVM time-zone rules. A previously
stored local-time/offset combination can become invalid after a TZDB change;
Vernac does not silently change its meaning to make reconstruction succeed.

These checks are not a universal database validation layer: server resource
limits, maximum field sizes, custom constraints, encoding configuration and
external changes can still produce JDBC errors. Read-side validation of arbitrary
externally modified rows is not added by this change. Native infinity dates are
not part of the supported contract. Domain reconstruction still checks invariants.

Float and double retain their existing mappings. Exact equality predicates for
repository searches are a separate next step: PostgreSQL numeric equality alone
cannot distinguish positive and negative zero as boxed Java equality does.

## Verification

From the repository root, use these Maven arguments in IntelliJ:

```
-pl vernac-maven-plugin -am test -Dtest=ScalarCodecTest,ScalarStorageLimitsTest,ScalarStoragePostgresTest -Dsurefire.failIfNoSpecifiedTests=false
```

The PostgreSQL tests require `VERNAC_PG_TEST_URL`, `VERNAC_PG_TEST_USER` and
`VERNAC_PG_TEST_PASSWORD` in the Maven run configuration. Use the disposable test
server. They create session-local temporary tables, not application tables.
Without the URL the two database tests are explicitly skipped.

The transport tests repeat prepared statements with `prepareThreshold=0` and `1`
and binary transfer enabled, covering both pgjdbc transport configurations.
They check signed zero, NaN, infinities, smallest/largest positive float/double,
negative epochs, timestamp endpoints and nanosecond remainders. They are not yet
an exhaustive database roundtrip suite for every mapped scalar family.

References:
- https://www.postgresql.org/docs/17/datatype-numeric.html
- https://www.postgresql.org/docs/17/datatype-character.html
- https://jdbc.postgresql.org/documentation/query/
