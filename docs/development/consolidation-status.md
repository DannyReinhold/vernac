# Vernac consolidation status

Status: **0.4.0 release baseline; next development version 0.5.0-SNAPSHOT**.
The [0.4.0 checklist](release-0.4.0.md) and [release notes](../maintainers/releases/0.4.0.md)
retain their release context. Source versions now advance separately from the published
0.4.0 language/API baseline; no additional language features are implied by this bump.

## Reviewed and implemented

| Area                    | Current scope                                                                                                                                                          |
|-------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Namespaces and imports  | Project-wide discovery, multi-file resolution, qualified names and namespace-derived Java packages                                                                     |
| Names and Unicode       | Shared default-name rules, explicit roles, collisions and Unicode-aware diagnostics and positions                                                                      |
| IDs and value objects   | Factories, validation, equality, getters, approved scalar types, optional fields and nullability contracts                                                             |
| Enum values             | Java enums, domain behavior and name checks; persistence codes stay outside the domain                                                                                 |
| Domain collections      | Explicit lists and sets, immutable collection structure, documented equality/duplicate operations and entity-ID access                                                 |
| Behavior and validation | Inline Java or external implementation, delegated access through self, read/modify interfaces and validation delegates                                                 |
| Entities and aggregates | Identity, create/reconstitute, containment rules, factories and technical persistence state                                                                            |
| Generated sources       | Manifest-owned Java output and orphan cleanup; class files and JARs remain build-tool responsibilities                                                                 |
| IntelliJ/LSP            | Project/file/namespace creation, Maven import, project diagnostics, completion, navigation and embedded Java support                                                   |
| PostgreSQL mapping      | Namespace-safe quoted names, recursive flattening, optional presence, scalar components and shared entity graphs                                                       |
| JDBC repositories       | Explicit repository declaration, byId/save/delete, declarative comparisons and ordering, complete graph loads, list order, reachability cleanup and optimistic locking |
| Usecases                | Named application operations, REQUIRED transactions, explicit results and external implementations                                                                     |
| Domain services         | Named methods, injected dependencies through self, per-operation preconditions and external implementations                                                            |
| Events                  | Immutable occurrences, shared EventId, validation, synchronous listeners, relational recording, deferred delivery and workers                                          |
| HTTP inbound adapters   | Typed path/query/header/body input, usecase invocation, shared mappings including explicit Result sources, ordered declarative endpoint/adapter error policies, typed rejection and integration selectors, safe defaults                                       |
| Outbound ports and ACL  | Java adapters, DTO/PATCH semantics, mappings, REST bindings, typed error rules and local HTTP walkthrough                                                              |
| Schema tooling          | Versioned source snapshots, initial/next migration candidates, explicit transformations, risk gates and Flyway replay checks                                           |

Reviewed areas have contracts and automated tests; depth and integration coverage
differ. The standalone persistence demo has also completed a real PostgreSQL run and
a subsequent Flyway migration in the developer's environment. This is useful evidence,
not exhaustive concurrency, platform, historical-data or deployment verification.

## Deliberate boundaries

- Validation failures and transaction rollbacks do not restore Java object state.
  Candidate-state atomic mutation remains a documented design, not an implementation.
- Repositories require a usecase transaction. They do not start transactions or lazy-load.
- Declarative root queries are implemented; handwritten JDBC/custom query extensions,
  nested collection predicates, multi-value equality remains deferred.
  Boolean OR/NOT/parentheses and literal/pattern text searches are implemented.
- Event recording, durable delivery and workers are implemented; event evolution
  and retry/idempotency responsibilities remain explicit operational concerns.
- Outbound ports/adapters are implemented, including the executable REST/ACL demo.
  The 0.3.0 HTTP inbound slice adds typed request binding, usecase calls and response ACL;
  other inbound transports remain planned.
- The two legacy example modules have been removed. The remaining examples and
  project templates have a source/documentation audit and focused tests; full release
  acceptance still requires database and installed-IDE checks. A green root build alone is
  not evidence that every generated starter project works.

## First 0.4.0 increment: multiple collections

Implemented: comma-separated lists/sets per ID, VO, enum, entity or aggregate.
Each name is independently optional, each behavior has its own receiver/imports,
and duplicate derived names fail. Query return types select their actual collection.
Compiler resolution, generation, Java projection, Java-to-Vernac navigation and LSP
completion/navigation share the complete set of declarations. [Pagination](../contracts/pagination.md)
now includes offset/counted and cursor JDBC queries; Result/HTTP integration is now implemented.

## Next work

The 0.3.0 consolidation removes the abandoned single-file analyzer/generation path,
legacy domain generator modes, implicit type guessing and per-type package syntax.
Single-file convenience APIs and project compilation now share resolution and generation;
schema and repository generation consume the same resolved model. Tests that asserted
obsolete public setters or SQL constants on domain classes now exercise the reviewed
access-view API and separate schema model instead. Their lifecycle, naming, identity
and flattening coverage is retained.

- [Deep IntelliJ integration](intellij-deep-integration.md): cross-language refactoring,
  conflict reporting, rich navigation/inspections and debugger investigation. **Planned,
  not implemented in this consolidation.**
- [Capabilities and usecase authorization](capabilities-and-usecase-authorization.md):
  reusable modules, explicit execution permissions and domain-specific limits.
- [DDD modeling follow-up](ddd-modeling-roadmap.md): aggregate boundaries, scoped uniqueness,
  reusable rules, named creation and domain variants; planning only.
- [Ubiquitous language](ubiquitous-language.md): implemented terms, type hover, glossary output and exported-type documentation; AI integration remains planned.
- Continue applying the [feature review checklist](feature-review-checklist.md), including
  shared compiler/tooling semantics and the quality of generated Java.

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

## Pagination foundation

Implemented technical request values, three materialized result contracts, explicit
named-list projection and sequential lazy query traversal. Generated list types have
the DomainList marker, preserving their immutable structure and concrete identity.
Runtime tests do not establish SQL snapshot/count/cursor behavior. See the
[remaining implementation sequence](pagination.md).

## Offset repository increment

Implemented list-only offset query declarations with explicit id tie-breakers,
count-free lookahead, single-statement matching-root count/page snapshots, full-graph
reconstruction, root-version checks and configurable per-load size limits. The query
interface supports one completed page or sequential lazy traversal inside the usecase
transaction. Cursor SQL is now implemented by the following increment; paginated usecase
Result/HTTP declarations are now implemented.

## Cursor repository increment

Implemented required-key list queries with explicit id tie-breakers, exclusive keyset
boundaries, decimal ordering-equivalence prefix comparisons, nanosecond components,
query/filter/schema-bound encrypted/authenticated v1 cursors and configurable durable
keys. The last returned root need not exist for continuation. Size/transaction/version
checks remain shared with offset loading. Result/HTTP declarations are now implemented.

## Completed application pages and HTTP

Implemented explicit offset/counted-offset/cursor result qualifiers, required named
value-list projections, independent Result components and adapter-local page DTOs.
Mappings preserve the variant/metadata and convert entries through existing ACL.
Scoped codecs validate counts/windows without global Jackson changes. Invalid cursors
have a safe 400/INVALID_CURSOR default. Tutorial/AI reference/FAQ, compiler diagnostics,
LSP field navigation and the Dispatch paging endpoint are included. Request headers
remain ordinary domain VOs; dynamic filters and backward cursor navigation are deferred.

## Domain diagram tooling

Implemented for 0.4.0: one shared resolved declaration graph exports Mermaid, PlantUML,
Markdown and versioned JSON per namespace. The IntelliJ action opens its Markdown
view using existing preview support. See the [tutorial](../tutorials/domain-diagrams.md)
and [contract](../contracts/domain-diagrams.md). Interactive exploration remains follow-up work.
