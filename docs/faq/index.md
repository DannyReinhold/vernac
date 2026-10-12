# Questions you will encounter while building

## Is Vernac a replacement for Java or Spring?

No. Vernac declares architectural intent and generates Java that participates in
the Java/Spring toolchain. Business code remains Java. You can invoke generated
classes from handwritten code, provide external behavior implementations and add
ordinary Spring controllers. See [the architectural walkthrough](../tutorials/from-model-to-application.md).

## Can I use only the domain model?

Yes. A repository is explicit, not generated for every aggregate. The core runtime
is separate from JDBC, JSON and HTTP support. Select the modules your application
needs; the [basic tutorial](../tutorials/your-first-domain-model.md) needs no database.

## Why does Title have string () instead of value ()?

Field names follow one type-based rule. `value Title(String);` derives `string`.
Write `value Title(String value);` to choose `value` explicitly. Adding another
field does not rename the first. Conflicts require explicit meaningful names.
[Explore naming](../tutorials/default-names.md).

## Why can I pass null but receive Optional from a getter?

Optional factory/public input parameters accept nullable values. Domain getters
express absence as Optional so callers must handle it deliberately. Required inputs
reject null. This is not permission to return null instead of Optional.empty ().
DTO and usecase-result records have separately defined Optional components.
[Read the field contract](../specification/domain-field-types.md).

## Why did my object change even though validation failed?

Modify behavior currently changes live state and validates at the outer boundary.
There is no in-memory rollback. Abort the failed operation; do not save or reuse
that graph as if it were restored. Database rollback does not repair Java objects.
[Failure semantics](../language-reference.md#validation-failures-and-object-state).

## Is a list of entities an immutable value?

Its structure is immutable; its elements remain entities. Therefore it cannot be
stored inside an immutable VO. Repeated references may point to the same instance.
`duplicates()` includes the first occurrence too; use `duplicates().distinct()`
for one representative per repeated equality group. [Collections](../tutorials/collections.md).

## Why does repository byId throw, but collection by return Optional?

Repository byId expects an existing aggregate identity and raises
AggregateNotFoundException when absent. In-memory collection membership can simply
be missing. Optional singleton repository queries express a different expectation;
multiple matches raise NonUniqueQueryResultException and do not create uniqueness.
[Query contract](../contracts/repository-queries.md).

## Does a successful save mean the transaction committed?

No. Repositories require a surrounding transaction. The usecase's Spring interceptor
commits after the method completes. A later error or commit failure can roll back
SQL after save returned. A directly constructed usecase has no Spring interception.
[Transactions](../contracts/usecases.md#spring-and-transactions).

## Why does the database retain a column after I revert Git?

Git changes files, not database history. Reverting an experimental migration/model
does not undo an already applied migration. Reconcile history deliberately. Reset
only a disposable database after checking its connection and stored data; production
requires an explicit migration strategy. Also clean target to remove copied old SQL.
[Flyway workflow](../tutorials/flyway-workflow.md).

## What does schema-check prove?

It replays migrations into disposable databases and compares the resulting owned
schema with the expected model. It does not prove compatibility with production data,
locking behavior, deployment order or runtime performance. Review and approval remain
your responsibility. `force` allows candidate generation despite detected data-loss
risk; it is neither approval nor a guarantee. [Schema tooling](../tutorials/postgresql-migrations.md).

## Are events Java-serialized or delivered exactly once?

No. Persisted payloads use relational mapping; each deferred listener gets a separate
job. Retries mean external effects need their own idempotency policy. Changes to a
referenced VO can affect stored event reconstruction even if the event declaration
is unchanged. [Events and evolution](../contracts/events.md).

## Does a database rollback undo an HTTP call?

No. A timeout can also occur after the remote operation succeeded. REST adapters do
not retry automatically. Business rejection, technical category and local/remote/unknown
responsibility are separate pieces of information. Decide safe retry at an application
boundary. [Error translation](../tutorials/declarative-errors.md).

## Why does an API response with extra fields still work?

Generated DTO decoding ignores unknown properties, including nested ones. Required
fields still reject absence/null and wrong types. Optional absence differs from an
empty collection. PATCH additionally distinguishes unchanged, set and clear.
[DTO tutorial](../tutorials/adapter-dtos.md).

## Why does the editor understand my model but Maven cannot find artifacts?

The IDE plugin bundles editor tooling, not artifacts installed into your Maven
repository. For this development branch, install matching 0.3.0-SNAPSHOT artifacts.
Check the Maven runner JDK/settings and local repository. Use JDK 25 for these examples.
[IDE setup](../tutorials/intellij-plugin.md).

## What happens when I remove a definition?

The compiler reconciles its owned generated Java files using a manifest. It does
not delete arbitrary class files or JARs. `mvn clean` clears old compiled output.
Do not put handwritten files inside generated output directories.
[Output ownership](../development/generated-output.md).

## Is a green build with skipped database tests enough?

Not for database claims. PostgreSQL tests are opt-in; supply the documented disposable
server configuration and inspect skipped counts. Database-free HTTP and domain tests
exercise different
boundaries. [Test the delivery example](../tutorials/database-persistence.md#7-run-the-database-tests).

[All tutorials](../tutorials/index.md) · [Reference](../language-reference.md)
