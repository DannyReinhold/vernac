---
title: Documentation map
---
# Find your path through Vernac

You do not need to read everything in order. Choose the outcome you want below.
Tutorials teach by doing; references help while writing; contracts settle exact
semantics; development notes explain how the implementation works.

**Version guide:** these pages describe the **0.5.0 release line**, including native
debugging, MVC/starter support and live Java editor APIs. Match compiler/runtime and
plugin versions. Source builds use **0.6.0-SNAPSHOT**; release builds select **0.5.0**.
If that version is not published yet, use the matching source-build instructions.
Historical release records are not the current feature reference.

## Choose an entry point

| I want to… | Start here | Continue with |
| --- | --- | --- |
| Learn DDD from the beginning | [What is DDD?](tutorials/what-is-ddd.md) | [DDD for small projects](tutorials/ddd-for-small-projects.md) |
| Understand what Vernac does | [Architecture, expressed](index.md) | [Follow one operation](tutorials/from-model-to-application.md) |
| Try the language without a database | [Your first domain model](tutorials/your-first-domain-model.md) | [Behavior](tutorials/behavior.md) |
| Start a small working web application | [Spring Boot starter](tutorials/spring-boot-starter.md) | [Server-rendered MVC](tutorials/inbound-mvc.md) |
| Explore a larger application | [Dispatch showcase](tutorials/dispatch.md) | [Tutorial learning path](tutorials/index.md) |
| Write correct syntax | [Language reference](language-reference.md) | [Specification and contracts](specification/index.md) |
| Give a coding agent a compact context | [AI language reference](ai-language-reference.md) | [AI build and migration tools](ai-tools-reference.md) |
| Diagnose unexpected behavior | [Practical FAQ](faq/index.md) | The relevant feature contract below |
| Develop a language plugin | [IntelliJ lessons learned](development/lessons-learned/index.md) | [Contributor implementation notes](development/index.md) |
| Build or release Vernac itself | [Contributing](development/index.md) | [Release operations](maintainers/releasing.md) |

## Model the domain and its meaning

Start with [default names](tutorials/default-names.md),
[closed values](tutorials/enum-value-objects.md) and
[collections](tutorials/collections.md). Then add
[behavior](tutorials/behavior.md), [usecases](tutorials/usecases.md) and
[domain services](tutorials/domain-services.md).

Keep the vocabulary beside the model with
[ubiquitous language](tutorials/ubiquitous-language.md), and inspect/export its
structure with [domain diagrams](tutorials/domain-diagrams.md).

For exact rules: [namespaces/imports](specification/namespaces-and-imports.md),
[field types/nullness](specification/domain-field-types.md),
[values](contracts/value-objects.md), [entities/aggregates](contracts/entities-and-aggregates.md)
and [shared Java implementation rules](contracts/implementation-contracts.md).
The [specification index](specification/index.md) connects the remaining contracts;
it is the authoritative route rather than a second competing rule book.

## Store, query and evolve data

| Task | Guided explanation | Exact contract or implementation |
| --- | --- | --- |
| Save/load an entire aggregate | [Database persistence](tutorials/database-persistence.md) | [PostgreSQL mapping](contracts/postgresql-persistence.md) |
| Search by values, absence or text | [Repository queries](tutorials/repository-queries.md) | [Query contract](contracts/repository-queries.md) |
| Return pages and cursors | [Pagination](tutorials/pagination.md) | [Pagination contract](contracts/pagination.md) |
| Change a deployed schema | [Flyway workflow](tutorials/flyway-workflow.md) | [Schema tools](tutorials/postgresql-migrations.md) |
| Understand representation limits | [Persistence FAQ](faq/index.md#persistence-and-migrations) | [Scalar limits](contracts/scalar-storage-limits.md) |

Migration tools produce candidates for review, not deployment approval. The Flyway
walkthrough explains the distinct states of source history, copied build resources
and the application database, including how to repeat a disposable experiment.

## Cross boundaries and reuse capabilities

- [Domain events](tutorials/domain-events.md): synchronous participation and durable deferred delivery.
- [Ports walkthrough](tutorials/ports-walkthrough.md): run an actual integration against a local fixture.
- [Java-backed ports](tutorials/outbound-ports.md) and [declarative REST](tutorials/declarative-rest.md): choose the appropriate implementation style.
- [DTOs/PATCH](tutorials/adapter-dtos.md), [mappings](tutorials/adapter-mappings.md) and [errors](tutorials/declarative-errors.md): define the anti-corruption boundary.
- [Inbound HTTP](tutorials/inbound-http.md) and [MVC](tutorials/inbound-mvc.md): expose usecases as JSON or server-rendered pages.
- [Versioned modules](tutorials/type-modules.md): exports, dependencies, activation, provider selection and schema evolution.

Follow these to the [integration contracts](specification/index.md#persistence-and-integration-contracts)
when you need a precise guarantee. The persistent-module example linked from the
module tutorial demonstrates independent migration histories and coordinated calls.

## Work naturally in IntelliJ

[Install and navigate](tutorials/intellij-plugin.md), then
[debug behavior in its source](tutorials/debugging.md).
The debugging chapter covers stepping, locals, expressions, conditions, logpoints,
validation and adapter/mapping contexts, as well as the generated-Java contributor view.

Building a plugin yourself? The [lessons-learned collection](development/lessons-learned/index.md)
connects language injection, source roots, live APIs, references, debugger scopes and
verification. It includes a SQLJ design checklist and distinguishes implemented paths
from pending installed-IDE acceptance and future work.

## Understand the project rather than use the language

[Contributor documentation](development/index.md) indexes compiler/tooling mechanisms,
design decisions and roadmaps. [Documentation maintenance](development/documentation.md)
explains which pages own rules and how examples/links are checked.
[Release operations](maintainers/releasing.md) covers publishing.

Implementation notes and proposals do not override the specification. They can contain
historical experiments or deferred work; check their status before treating them as
current behavior. Feature gaps belong on the linked roadmaps, not in guessed syntax.

[Documentation home](index.md) · [All tutorial chapters](tutorials/index.md) ·
[FAQ](faq/index.md) · [Specification index](specification/index.md)
