# Build an application, one architectural decision at a time

Start with a passing domain test. Then follow a delivery operation through behavior,
transactions, persistence, events and an external carrier. Each chapter answers a
practical question; focused examples isolate details when the full system would distract.

These tutorials target **0.2.0-SNAPSHOT**. Use JDK 25 and matching local artifacts.
Commands with `mvn` are terminal commands; omit `mvn` in IntelliJ's Maven command window.
In PowerShell, quote a complete dotted `-Dproperty=value` argument when necessary.

## 1. Make the domain explicit

1. [Set up IntelliJ](intellij-plugin.md): create a project and navigate between Vernac and Java.
2. [Your first domain model](your-first-domain-model.md): values, identity and a tested operation.
3. [From model to application](from-model-to-application.md): see how the architectural pieces connect.
4. [Default names and explicit roles](default-names.md): choose a clear generated API.
5. [Closed domain values](enum-value-objects.md): model states without transport codes.
6. [Lists, sets and repeated values](collections.md): make multiplicity and identity intentional.
7. [Domain behavior](behavior.md): inline Java, private helpers and external implementations.

## 2. Turn behavior into an application operation

1. [Usecases](usecases.md): one operation, declared results and one transaction boundary.
2. [Domain services](domain-services.md): move a Stop between Tours using a shared domain rule.

## 3. Keep and evolve state

1. [Persist complete aggregates](database-persistence.md): shared entities, queries and concurrent changes.
2. [Repository queries](repository-queries.md): equality, absence, text patterns and boolean expressions.
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

[FAQ](../faq/index.md) · [Language reference](../language-reference.md) ·
[Specification](../specification/index.md) · [Documentation home](../index.md)
