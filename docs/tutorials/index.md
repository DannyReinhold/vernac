---
title: Build an application, one architectural decision at a time
---
# Build an application, one architectural decision at a time

Start with a passing domain test. Then follow a delivery operation through behavior,
transactions, persistence, events and an external carrier. Each chapter answers a
practical question; focused examples isolate details when the full system would distract.

These tutorials describe **Vernac 0.5.0**. Use JDK 25 for the documented builds;
generated application code targets Java 21. Released consumers use 0.5.0 artifacts
from Maven Central after publication. Repository examples use 0.5.0-SNAPSHOT by
default: install matching local artifacts first, or select the release with
`-Dvernac.version=0.5.0` after publication.
Commands below include `mvn`; use the full command in the IntelliJ Maven run configuration used for these examples.
In PowerShell, quote a complete dotted `-Dproperty=value` argument when necessary.

Looking for one topic rather than a full learning path? Use the
[documentation map](../documentation-map.md).

## How to use the examples

The Task chapters are small, database-free exercises. The delivery chapters use
`examples/persistence-demo`; Dispatch adds a browser UI. Unless explicitly labelled
as an addition, a complete model is an alternative exercise: do not paste it into a
namespace that already defines the same types. Fragments belong inside the named
existing declaration. Revert intentional failures before moving to the next chapter.

Each chapter tells you where commands run and which extra dependencies it needs.
For a source checkout, use the [example build guide](https://github.com/DannyReinhold/vernac/blob/main/examples/README.md).

## New to DDD? Start with the ideas

1. [What is DDD?](what-is-ddd.md): values, identities, aggregates and usecases explained with a task board.
2. [DDD for a small project](ddd-for-small-projects.md): use the structure you need without inventing a large architecture.
3. Choose your first success: a [database-free domain test](your-first-domain-model.md) or a [working web application](spring-boot-starter.md).

Already know DDD? Start at the first domain model or use the
[documentation map](../documentation-map.md) to jump directly to your task.

## 1. Make the domain explicit

1. [Set up IntelliJ](intellij-plugin.md): create a project and navigate between Vernac and Java.
2. [Your first domain model](your-first-domain-model.md): values, identity and a tested operation.
3. [From model to application](from-model-to-application.md): see how the architectural pieces connect.
4. [Default names and explicit roles](default-names.md): choose a clear generated API.
5. [Closed domain values](enum-value-objects.md): model states without transport codes.
6. [Lists, sets and repeated values](collections.md): make multiplicity and identity intentional.
7. [Domain behavior](behavior.md): inline Java, private helpers and external implementations.

8. [Keep the meaning beside the model](ubiquitous-language.md): a shared glossary, type hover and vocabulary for AI-assisted work.

9. [See the architecture](domain-diagrams.md): a namespace diagram, vocabulary and export for documentation.

## Debug the business rule

[Debug your business behavior where you wrote it](debugging.md) walks through
breakpoints, stepping, variables, Java expressions, conditions and logpoints in a
small database-free application. Use matching 0.5.0 compiler and plugin artifacts,
or the matching SNAPSHOT source build.

## Start with a real web application

[From New Project to a working application](spring-boot-starter.md) creates a small
Thymeleaf/PostgreSQL task workspace from the 0.5.0 wizard. It demonstrates a complete
browser-to-usecase-to-database path, local Compose, migrations and deployment configuration.
The [MVC adapter tutorial](inbound-mvc.md) shows how to declare HTML pages, forms,
redirects and safe error redisplay directly in Vernac.

## 2. Turn behavior into an application operation

1. [Usecases](usecases.md): one operation, declared results and one transaction boundary.
2. [Domain services](domain-services.md): move a Stop between Tours using a shared domain rule.

## 3. Keep and evolve state

1. [Persist complete aggregates](database-persistence.md): shared entities, queries and concurrent changes.
2. [Repository queries](repository-queries.md): equality, absence, text patterns and boolean expressions.
   The [pagination tutorial](pagination.md) demonstrates generated queries, counts, independent usecase Result components and a paginated GET.
3. [Evolve with Flyway](flyway-workflow.md): review and apply a migration while retaining data.
4. [Schema tooling](postgresql-migrations.md): snapshots, backfills and disposable-database verification.

## 4. React to facts and cross system boundaries

1. [Domain events](domain-events.md): synchronous participation and durable deferred delivery.
2. [Run the ports and ACL walkthrough](ports-walkthrough.md): inspect real HTTP calls against a local fixture.
3. [A Java-backed outbound port](outbound-ports.md): the same architecture without HTTP.
4. [DTOs and PATCH](adapter-dtos.md): names, absence and explicit update intent.
5. [Mappings](adapter-mappings.md): translate transport records into validated domain values.
6. [Declarative REST](declarative-rest.md): bind requests and responses.
7. [Declarative error translation](declarative-errors.md): distinguish rejection from technical failure.
8. [HTTP runtime](http-adapter-runtime.md): use the lower-level Java API when needed.
9. [HTTP inbound adapters](inbound-http.md): expose a usecase with an explicit request/response contract.

[FAQ](../faq/index.md) · [Language reference](../language-reference.md) ·
[Specification](../specification/index.md) · [Documentation home](../index.md)

## Put the pieces together

[Run Vernac Dispatch](dispatch.md): an interactive application connecting the domain,
transactions, PostgreSQL, durable events and a carrier ACL.

[Reuse and evolve a type module](type-modules.md): publish domain values, import
them, create the initial application migration and upgrade a flattened external VO.
The chapter explains explicit activation, provider selection, dependency navigation and independent migration histories.
[Run the persistent module lifecycle](https://github.com/DannyReinhold/vernac/blob/main/examples/persistent-modules/README.md):
call two persistent modules from an application, then upgrade their shared value
with reviewed backfills while retaining existing data. Its separate API module and durable event receivers demonstrate the same activation graph.
