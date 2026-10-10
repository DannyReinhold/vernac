# Search complete aggregates

Repository searches return your domain aggregates, including all contained entities
and collections. They do not return arbitrary SQL projections.

This example uses the reviewed language and the development SNAPSHOT. Place it in
`src/main/vernac/example/search/tours.vernac`:

```vernac
namespace example.search;

id TourId;
value Title(String);
value Price(BigDecimal);
value Status = PLANNED | COMPLETED;
aggregate Tour[TourId](Title title, Title? reference, Price price, Status status)
    list Tours;

repository TourRepository for Tour {
    find Tours planned(Status status)
        where status = :status order by title;

    find Tours inPriceRange(Price minimum, Price maximum)
        where price >= :minimum and price < :maximum order by price desc;

    find Tours otherReference(Title reference)
        where reference != :reference order by title;

    find Tours withoutReference()
        where reference is absent order by createdAt;

    find Tours recentlyUpdated(Instant cutoff)
        where updatedAt > :cutoff order by updatedAt desc;

    find Tour? uniquelyTitled(Title title)
        where title = :title;
}
```

Generate Java with your usual Maven compile lifecycle. Provision the schema using
the [Flyway workflow](flyway-workflow.md). Adding a query alone does not change the
schema or require a migration. Adding a domain field does.

Inside an application/usecase transaction:

```java
Tours tours = repository.inPriceRange(
        Price.of(new BigDecimal("10.00")), Price.of(new BigDecimal("50.00")));
Optional<Tour> candidate = repository.uniquelyTitled(Title.of("Morning route"));
```

The result of `uniquelyTitled` is empty for no match. Two or more matches throw
`NonUniqueQueryResultException`; Optional does not silently choose a first row and
does not introduce a uniqueness constraint.

## Comparisons

Use `=`, `!=`, `<`, `<=`, `>` and `>=`. Combine predicates with `and`.
The left side names a root field; `:name` binds a method parameter. Vernac checks
the exact domain type, even if two different value objects both wrap String.
Optional parameters are not supported; use a separate query with `is absent`.

An absent field matches neither equality nor inequality against a supplied value.
`reference != :reference` means a *present* reference different from the argument.
To find missing values, use `reference is absent`.

BigDecimal equality includes scale: `1.0` and `1.00` are unequal. Range comparisons
and sorting use numeric magnitude, so these values can tie. Floating equality
distinguishes positive and negative zero and treats NaNs as equal; floating range
comparisons are deferred. Strings compare exactly; their sorting/range order uses
PostgreSQL `C` collation, not language-specific alphabetical order.

Time comparisons include nanosecond remainders. All ordered scalar capabilities
are listed in the [contract](../contracts/repository-queries.md). This increment
supports IDs, enums, and single-value objects with one required built-in scalar.
Nested paths, multi-value equality, collection predicates and LIKE are deferred.

## Ordering

Declare a list on the aggregate to preserve query order. Direction defaults to
ascending; multiple sort fields are separated by commas. Absent values sort last
in both directions. Equal sort keys have unspecified relative order: Vernac adds
no hidden UUID tie-breaker.

A set result with `order by` is an error. A list without `order by` produces a warning.
The stored positions of a *contained* list order that list; they cannot order
independent aggregate roots returned by a search.

## Verify the implementation

From the Vernac repository root, use these IntelliJ Maven arguments:

```text
-pl vernac-maven-plugin,vernac-lsp -am test -Dtest=RepositoryQueryTest,RepositoryQueryPostgresTest,ScalarQueryPostgresTest,RepositoryQuerySymbolsTest -Dsurefire.failIfNoSpecifiedTests=false
```

Use the disposable PostgreSQL test server and the existing environment variables
`VERNAC_PG_TEST_URL`, `VERNAC_PG_TEST_USER`, `VERNAC_PG_TEST_PASSWORD`.
Without the URL the database test methods are skipped. The generated repository
test creates and drops its own uniquely named database; the scalar test uses temporary
tables. Use the disposable test server, not an application database.

The generated-code test verifies shared child references, isolation between roots,
cardinality, transactions and concurrent updates/deletes between root selection and
relation loading. The scalar matrix tests equality and inequality against Java's
results, plus range predicates for representative ordered types, in pgjdbc text and
binary transport configurations.

## Literal text versus patterns

See [the runnable text-search walkthrough](database-persistence.md#9-try-literal-text-and-like-patterns)
for all four operations, including escaped LIKE patterns. For the precise rules
on empty strings, absent values and Unicode, see the
[text-search contract](../contracts/repository-queries.md#text-searches).

## Combine conditions

```vernac
find Tours selected(TourStatus status, Title title)
    where (status = :status or title = :title) and not (reference is absent)
    order by title;
```

Without parentheses, `not` binds before `and`, and `and` before `or`.
Negating a comparison does not include absent values: use an explicit `is absent`
branch when they should match. All branches receive the same field and parameter
checks as individual predicates. PostgreSQL tests contrast grouped and ungrouped
expressions, repeated negation, text patterns and absent values.
