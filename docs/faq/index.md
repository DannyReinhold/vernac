# Practical questions about building with Vernac

You have a model compiling. Now you want to understand its generated API, connect it
to an application, or explain an unexpected result. Start with the situation closest
to yours below. These answers describe Vernac **0.3.0**; source checkouts use matching
**0.4.0-SNAPSHOT** artifacts for the next development line.

New to the language? Follow [your first domain model](../tutorials/your-first-domain-model.md)
first. For exact requirements, use the [specification](../specification/index.md).

## Getting started and using Java

### I already have a Java/Spring application. What would Vernac replace?

Vernac generates the repeated structure around your architectural decisions: typed
IDs, validated values, aggregate APIs, usecase transaction wrappers, repositories
and adapters. Your business behavior remains Java, either inline in a Vernac
`behavior` block or in a handwritten implementation class.

You can introduce a small domain model into an existing application. Handwritten
Java can construct its values, call its operations and inject generated Spring
beans. You do not have to replace every controller or rewrite the application.
[Follow one operation through the architecture](../tutorials/from-model-to-application.md).

### Do I need Spring, PostgreSQL and Docker just to try value objects?

No. The basic template generates a Task model and runs an ordinary Java test. It
needs neither a running Spring application nor a database. Repositories are
explicit declarations; declaring an aggregate alone does not request persistence.

The core, JDBC, JSON and HTTP runtimes are separate modules. Add infrastructure as
you need it. [Start with the database-free example](../tutorials/your-first-domain-model.md).

### The editor recognizes my model, but Maven cannot resolve Vernac. Why?

The IntelliJ plugin bundles its language server; it does not install the Maven
compiler/runtime artifacts. Check the application's `vernac.version`, the Maven
runner's JDK/settings and the selected Maven repository.

A released 0.3.0 consumer downloads matching artifacts from Maven Central **after
publication**. A source checkout using 0.4.0-SNAPSHOT requires a local installation:
run `mvn clean install` from the Vernac repository root with JDK 25. `verify` tests
and packages artifacts but does not install them. Restarting IntelliJ also discards
terminal-only environment settings. [Check the plugin setup](../tutorials/intellij-plugin.md).

### Why does `value Title(String);` expose `string()` rather than `value()`?

All ordinary fields follow the same type-based naming rule. `String` becomes
`string`, `Title` becomes `title`, and primitive `int` becomes `intValue`. A one-field
value object has no special naming rule, so adding a second field does not rename
the first getter.

Choose the API explicitly when another name is clearer:

```vernac
value Title(String text);
```

This gives `Title.of("Morning tour").text()`. Use `String value` if you specifically
want `value()`. IDs are different: their fixed UUID accessor is always `value()`,
and the identity of an entity/aggregate is always `id()`.
[Explore default names and conflicts](../tutorials/default-names.md).

### Why can an optional input be null while its getter returns Optional?

An optional field may be absent at construction. Factories accept `@Nullable T`
for that input, then expose `Optional<T>` through the getter so reading code handles
absence deliberately. Required inputs reject null.

For `value Profile(Title, Title? nickname);`, Java can write:

```java
Profile profile = Profile.of(Title.of("Alex"));
String display = profile.nickname().orElse(profile.title()).string();
```

Use `Optional.empty()` for an optional behavior result; an Optional itself must
never be null. DTO and usecase-result records also expose Optional components,
while their `of(...)` factories accept nullable ordinary optional arguments.
[Read the nullness rules](../specification/domain-field-types.md).

## Domain state and transactions

### My modify method failed validation. Why does the object still contain changes?

A `modify` behavior changes the live object. Vernac checks invariants after successful
completion of the outermost synchronous modification; it currently has no in-memory
rollback. If behavior or validation throws, assignments already made may remain.

Stop the failed operation and decide how to recover. Do not save that graph as if
it had been restored. A database transaction can roll back SQL, but cannot repair
Java objects or undo an HTTP call. Candidate-state mutation is a documented proposal,
not a current guarantee. [Understand failure semantics](../language-reference.md#validation-failures-and-object-state).

### A collection is immutable. Can I still change an entity inside it?

Yes. Immutability protects the container's membership/order, not the state of an
entity it contains. `stops.plus(stop)` returns a new collection; it does not change
`stops`. Calling business behavior on an existing Stop can change that Stop.

This is why an entity collection cannot be a field of an immutable VO. Value/ID/enum
collections can. Within a loaded aggregate, several relationships may refer to the
same entity instance, so a change is visible through each of those references.
[Explore collection structure and identity](../tutorials/collections.md).

### Why does `duplicates()` include the first occurrence as well?

It answers “which original elements belong to a repeated equality group?”, rather
than “which later insertions were redundant?”. For `Names.of(a, b, a)`, the result
is `[a, a]`, in original order. This preserves all instances for investigation,
including entities with equal IDs but different state.

Use `duplicates().distinct()` for one representative per repeated group. A set or
`distinct()` retains the first equal instance; it does not decide which entity state
is newer. [See the collection examples](../tutorials/collections.md).

### Why does repository `byId` throw when collection `by` returns Optional?

The operations express different expectations. `repository.byId(id)` requests an
aggregate expected to exist; absence raises `AggregateNotFoundException`.
`stops.by(stopId)` searches membership in an in-memory collection, where a missing
member is an ordinary result.

Declare a singleton repository query with `find Tour? ...` when absence is expected.
It returns Optional, but multiple matches still raise `NonUniqueQueryResultException`.
That declaration does not add a database uniqueness constraint or choose the first row.
[Read the query contract](../contracts/repository-queries.md).

### `save()` returned successfully. Is my change already committed?

Not necessarily. A repository requires a surrounding transaction (MANDATORY).
A generated usecase starts or joins one (REQUIRED), and Spring commits after the
transactional method completes. A later exception or commit failure can still roll
back SQL written by `save()`.

Call the usecase through its injected Spring bean. Constructing it with `new`, or
using self-invocation to bypass a Spring proxy, does not activate transaction
interception. Domain services coordinate rules but do not start their own transaction.
[See the usecase contract](../contracts/usecases.md#spring-and-transactions).

## Persistence and migrations

### Why are there quoted names, dotted VO columns and `@...` columns in the SQL?

They encode the model without reserving extra domain words. The namespace becomes
the quoted PostgreSQL schema; each aggregate has its own root table. A single-value
VO is stored under its field name, while a multi-value VO expands to paths such as
`"price.amount"` and `"price.currency"`. Technical columns use an `@` prefix.

Child entities have aggregate-scoped state tables. Separate relationship tables
preserve shared references, and lists store `@position`. `@version` protects the
whole graph against stale writes. Read the generated schema rather than guessing
physical names. [Follow a complete database round trip](../tutorials/database-persistence.md).

### Do database constraints repeat my Java validates rules?

Vernac does not translate arbitrary `validates` expressions into SQL. Generated
constraints protect the storage representation: required data, references within
an aggregate, valid enum codes, list positions and coherent optional values.

For example, an absent nested VO must not leave partial data behind. A domain rule
such as “this tour must fit its vehicle” still belongs in the model. Constraints are
part of the schema model, so changes can produce a migration candidate; review its
ordering and data implications before applying it.
[See structural constraints](../contracts/postgresql-persistence.md#structural-constraints-and-migrations).

### I reverted a migration experiment in Git. Why is the database still different?

Git restores files; Flyway has already changed the database and recorded that
migration's version/checksum. Source, copied SQL under `target/classes`, and the
running database are three separate states.

First verify the actual datasource URL and database name. Connecting to the server's
`postgres` database does not show the application's tables or Flyway history. Then
choose a deliberate recovery: restore matching source history, use a forward
migration for shared data, or reset **only a disposable demo database**. Run Maven
`clean` to remove copied SQL that no longer exists in source.
[Follow the Flyway workflow and recovery checklist](../tutorials/flyway-workflow.md#repeat-the-exercise-without-mixing-database-histories).

### What does schema-check establish before I deploy a migration?

It replays the committed Flyway SQL into one new disposable database, creates the
expected schema in another, and compares Vernac-owned structures. It detects a
migration chain whose resulting structure does not match the model; it ignores
foreign application tables. It does not apply SQL to your application database.

Both databases start empty. Therefore it cannot establish that a backfill is correct
for historical data, locks are acceptable, or old/new application versions can run
together. Test those conditions separately and review every migration. `force`
merely allows candidate generation despite recognized data-loss risks; it approves
nothing and executes nothing. [Understand migration tooling](../tutorials/postgresql-migrations.md).

## Events and integration boundaries

### If two listeners consume one event, do they share retries or acknowledgements?

Each deferred listener has its own delivery job pointing to the same stored event
occurrence. One listener's success does not acknowledge another listener's job.
Workers use leases/fencing to coordinate concurrent delivery and recover from crashes.

Delivery is at least once, not an exactly-once guarantee for external effects.
A handler can fail after an external system has accepted a request, so retries need
an application-specific idempotency policy. Payloads use relational tables, not Java
object serialization. Changing a nested VO is event evolution too: test whether
stored events can still be reconstructed. [Explore events and retries](../tutorials/domain-events.md).

### Does a database rollback undo an external API call? Can I simply retry a timeout?

No. PostgreSQL cannot roll back a carrier's booking, filesystem write or email.
A timeout also does not prove failure: the remote operation may have succeeded
before its response was lost. Generated REST adapters do not retry automatically.

Use the typed failure category and responsibility information to decide who should
investigate. Decide retry/idempotency at the application boundary according to the
operation's contract. [Understand error translation](../tutorials/declarative-errors.md).

### Why is an extra response field accepted, but a missing field fails?

Generated DTO decoders tolerate unknown JSON properties, including nested ones, so
a provider can extend a response without breaking consumers. Your declared required
fields remain required: absence, null and wrong JSON types are errors.

An absent optional field differs from an empty list. PATCH adds a third distinction:
unchanged, set, or clear. These are protocol decisions, not automatic aggregate
updates. [Try the DTO/PATCH tutorial](../tutorials/adapter-dtos.md).

### Can I generate an HTTP endpoint directly, and where does its transaction belong?

Yes. Declare `adapter ... inbound http`, bind path/query/header/body values to an
existing usecase, and map the result to a local DTO. The controller performs transport
conversion; the usecase owns the transaction. Outbound `port` declarations describe
capabilities your domain calls, rather than APIs exposed to callers.

Malformed wire input defaults to 400, domain validation to 422, missing aggregates
to 404, and optimistic conflicts to 409. Public error bodies contain a safe code and
message. Response mapping happens after the usecase returns: if it fails, the usecase
may already have committed. Do not automatically repeat a command because its HTTP
response failed. [Build an inbound endpoint](../tutorials/inbound-http.md).

## Builds and generated files

### What happens to generated Java when I remove a Vernac definition?

The compiler records ownership in a manifest and removes orphaned generated `.java`
files after successful generation. It does not delete arbitrary `.class` files or
JARs: Maven owns compiled output, and `mvn clean` clears it.

Keep handwritten code out of generated directories. After removing or renaming a
type, use a clean build before interpreting stale compiled classes as current output.
[Read the output ownership contract](../development/generated-output.md).

### My build is green, but database tests were skipped. What have I actually tested?

Only the tests that ran. Database-free domain, generated-Java and loopback HTTP
checks are valuable, but cannot prove PostgreSQL behavior. Supply the documented
disposable-server configuration, enable any example-specific profile, and inspect
skipped counts before claiming database coverage.

The release workflow requires its named database and plugin suites to execute and
rejects skipped reactor reports. [Run the database tests](../tutorials/database-persistence.md#7-run-the-database-tests)
and [review the release gates](../development/release-0.3.0.md).


### How can I show a numbered page without loading every matching aggregate?

Declare a paginated repository query with a named **list** result and a complete
explicit order, for example `find offset Issues open() order by createdAt desc, id asc;`.
Its generated query object accepts `page(OffsetRequest.ofPage(3, 20))`; only that
window is returned, and every entry is a fully reconstructed aggregate. Existing
unqualified find methods keep returning their whole collection.

Add `counting` before the semicolon when the UI needs “page 3 of 27”. The returned
CountedOffsetPage exposes totalElements and totalPages. An offset past the last row
returns an empty items list with the original total, not an invented last page.
Without counting, OffsetPage provides hasNext without computing the total.

### Does pagination make repeated requests observe one unchanged dataset?

No. A counted page's root selection and count share one SQL statement snapshot, and
child reconstruction verifies root versions. Concurrent changes to selected roots
may raise an optimistic-lock conflict. Separate requests/traversal steps may observe
new data, so inserts/deletes can shift offsets. Vernac does not silently change your
transaction isolation or retry. Use stable sorting with explicit id as the final
tie-breaker; cursor queries avoid offset shifting for unchanged sort keys, but do not freeze the
dataset either.

### Can I return the lazy page iterator from a usecase to an HTTP serializer?

No. Its next load needs the usecase transaction. Consume `.pages(request).items()`
inside that transaction, or load one materialized page and project its entries eagerly.
Every JDBC load checks the active transaction and rejects requests above the configured
maximum (`vernac.pagination.max-page-size`, default 1000). Pagination components in
Vernac Result declarations and HTTP mappings are supported. The
[pagination tutorial](../tutorials/pagination.md) shows the currently executable API.

### When should I use a cursor instead of a numbered page?

Use `find cursor` for successive sections of an explicitly ordered list, such as an
activity feed or sequential processing. Start with CursorRequest.first(size), then
send the returned nextCursor with the same filters. The last returned root may be
deleted: its saved boundary still works. A cursor has no absolute page number or
count; choose `find offset ... counting` for “page 3 of 27”. Neither freezes future
requests against concurrent changes.

### Why does my cursor stop working after a restart or on another server?

Without configuration, Vernac encrypts/authenticates cursors with a random process-local
key. Set `vernac.pagination.cursor-key` to the same secret (Base64 of 32 random bytes)
across restarts and replicas to retain continuations. Changing the key, filters,
repository method or owned schema invalidates old tokens; start a new traversal.
Never use tokens as authorization and never commit the key. See the
[cursor configuration tutorial](../tutorials/pagination.md#continue-after-the-last-ordered-key).

### Can one HTTP response contain two separately paged lists and other information?

Yes. Declare a tuple result such as `returns (Title heading, counted offset Summaries
openItems, counted offset Summaries closedItems, cursor Summaries activity)`. Each
component keeps its own request position and metadata. Project whole aggregate pages
into a named value list before Result.of. The adapter maps these into corresponding
`counted offset list<ItemDto>`/`cursor list<ItemDto>` fields; an entry mapping converts
the values while metadata is preserved. Each load is independent, rather than a
promise of one frozen snapshot across all board components. The
[pagination tutorial](../tutorials/pagination.md) shows the full model and GET.

### Why does the result require values rather than pages of aggregates?

An aggregate is mutable domain state. Returning immutable summaries makes the
application boundary explicit and lets serialization finish without accessing the
repository. Use page.mapItems(Summaries::of, mapper), then declare the matching result
variant. Sets lose order, optional pages confuse absence with an empty window, and
lazy sequences would perform database work after the usecase transaction; all are
rejected as result contracts.

### Do I need special pagination types in my domain model?

No. Technical request factories live in the runtime Java API. You can define ordinary
PageNumber/PageSize/CursorToken VOs with your own validation and pass them to a usecase.
Unpack their getters explicitly into OffsetRequest/CursorRequest. Their spelling has
no magic and does not reserve those names globally. The result uses a page qualifier,
which is a completed application result, not a field type for a persisted aggregate.

[All tutorials](../tutorials/index.md) · [Language reference](../language-reference.md)

## How can an HTTP endpoint report a business failure without exposing internal details?

Use an inbound `errors` policy. A status-only rule keeps the existing safe code and
message; two explicit strings provide an application-specific public code and message.
Rules on an endpoint run before shared adapter rules. Unmatched errors retain defaults.
Technical integration failures can be selected by their existing responsibility and
category: a remote timeout can become 503, while an invalid upstream response can
become 502. The HTTP reply does not erase the original integration diagnostics.
See [inbound error policies](../contracts/inbound-http.md#declarative-inbound-error-policies-040).
