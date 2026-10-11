# Vernac consolidation status

Status: 2026-10-11. Target release: 0.2.0. This separates reviewed functionality from older implementations
and future plans. It is not a claim that every language feature is production-ready.

The authoritative remaining work and release gates are in the
[0.2.0 consolidation plan](release-0.2.0.md). Larger extensions start after release.

## Reviewed and implemented

| Area | Current scope |
| --- | --- |
| Namespaces and imports | Project-wide discovery, multi-file resolution, qualified names and namespace-derived Java packages |
| Names and Unicode | Shared default-name rules, explicit roles, collisions and Unicode-aware diagnostics and positions |
| IDs and value objects | Factories, validation, equality, getters, approved scalar types, optional fields and nullability contracts |
| Enum values | Java enums, domain behavior and name checks; persistence codes stay outside the domain |
| Domain collections | Explicit lists and sets, immutable collection structure, documented equality/duplicate operations and entity-ID access |
| Behavior and validation | Inline Java or external implementation, delegated access through self, read/modify interfaces and validation delegates |
| Entities and aggregates | Identity, create/reconstitute, containment rules, factories and technical persistence state |
| Generated sources | Manifest-owned Java output and orphan cleanup; class files and JARs remain build-tool responsibilities |
| IntelliJ/LSP | Project/file/namespace creation, Maven import, project diagnostics, completion, navigation and embedded Java support |
| PostgreSQL mapping | Namespace-safe quoted names, recursive flattening, optional presence, scalar components and shared entity graphs |
| JDBC repositories | Explicit repository declaration, byId/save/delete, declarative comparisons and ordering, complete graph loads, list order, reachability cleanup and optimistic locking |
| Usecases | Named application operations, REQUIRED transactions, explicit results and external implementations |
| Domain services | Named methods, injected dependencies through self, per-operation preconditions and external implementations |
| Events | Immutable occurrences, shared EventId, validation, synchronous listeners, relational recording, deferred delivery and workers |
| Outbound ports and ACL | Java adapters, DTO/PATCH semantics, mappings, REST bindings, typed error rules and local HTTP walkthrough |
| Schema tooling | Versioned source snapshots, initial/next migration candidates, explicit transformations, risk gates and Flyway replay checks |

Reviewed areas have contracts and automated tests; depth and integration coverage
differ. The standalone persistence demo has also completed a real PostgreSQL run and
a subsequent Flyway migration in the developer's environment. This is useful evidence,
not exhaustive concurrency, platform, historical-data or deployment verification.

## Deliberate boundaries

- Validation failures and transaction rollbacks do not restore Java object state.
  Candidate-state atomic mutation remains a documented design, not an implementation.
- Repositories require a usecase transaction. They do not start transactions or lazy-load.
- Declarative root queries are implemented; handwritten JDBC/custom query extensions,
  nested collection predicates, multi-value equality and pagination remain deferred.
  Boolean OR/NOT/parentheses and literal/pattern text searches are implemented.
- Event recording, durable delivery and workers are implemented; event evolution
  and retry/idempotency responsibilities remain explicit operational concerns.
- Outbound ports/adapters are implemented, including the executable REST/ACL demo.
  Inbound transport generation remains post-release work.
- The two legacy example modules have been removed. The remaining examples and
  project templates have a source/documentation audit and focused tests; full release
  acceptance still requires database and installed-IDE checks. A green root build alone is
  not evidence that every generated starter project works.

## Next work

Follow [the 0.2.0 plan](release-0.2.0.md): repository/template cleanup, remaining
consistency review, all documentation and examples, a usable complete application,
release automation including Marketplace, then merge to main and release.
All work remains on feature/redefining_the_language until the acceptance gates pass.
Apply the [feature review checklist](feature-review-checklist.md) throughout.

## Migration exercise and constraint cleanup

Migration versions use UTC `yyyyMMddHHmmssSSS`. For example, `20261009171331551`
means 2026-10-09 at 17:13:31.551 UTC. Ordering is chronological; the number is not an
epoch timestamp. The committed initial example version is a fixed baseline identifier.

An optional single-column wrapper needs neither an absence check nor a shape check
when that same column alone witnesses presence. Such checks would be tautologies.
The generator omits these specific cases, including nested single-column wrappers.
It retains outer-owner guards, multi-component scalar checks, presence markers,
enum-code checks and range checks. This is a narrow structural simplification, not
a general SQL expression optimizer.

Existing migration files and snapshots are not rewritten. If an existing model's
redundant constraints disappear from the target schema, the next migration candidate
can drop them normally. Review that candidate like any other migration.

Git rollback does not roll back Flyway or the database. After reverting the tutorial
V2 files, the database still contains V2. Restore matching source history or deliberately
reset only the disposable demo database before repeating a fresh-baseline exercise.

## Declarative repository query increment

The initial query implementation includes exact scalar equality/inequality, range
comparisons for ordered types, root metadata, Optional cardinality checks, batched
graph loading and LSP field/parameter support. See the
[query contract](../contracts/repository-queries.md). Real PostgreSQL acceptance
remains an explicit opt-in test step; local generated-Java compilation alone is
not proof of database semantics. Literal text searches and LIKE predicates are
implemented; alternative locale-aware sort orders remain deferred.
