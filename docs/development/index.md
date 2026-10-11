# Contributing to Vernac

User documentation starts at the [documentation home](../index.md). This section
is for language/tooling contributors. Build with JDK 25 and Maven 3.9+; generated
application code retains its Java 21 target.

```shell
mvn clean verify
```

Standalone examples have their own POMs and checks. PostgreSQL tests need explicit
disposable-database configuration and must not be counted as passed when skipped.
Release acceptance also includes installation of the packaged IntelliJ plugin.

## Implementation and decisions

- [Compiler architecture](compiler-architecture.md): shared resolution, names and generation.
- [Feature review checklist](feature-review-checklist.md): purpose, quality, diagnostics and tooling.
- [Generated output ownership](generated-output.md): reconciliation and failure boundaries.
- [Validation delegates](validation-delegates.md): access views and Java projection.
- [Entity changes and candidate-state design](entity-behavior-and-change-contexts.md): current direct mutation and explicitly deferred alternatives.
- [LSP workspace analysis](lsp-workspace-analysis.md), [embedded Java](intellij-java-behavior.md), [Java navigation](java-to-vernac-navigation.md).
- [Schema tooling](postgresql-schema-tooling.md) and [scalar mapping decisions](scalar-persistence-audit.md).
- [Event delivery](event-delivery.md) and [ports implementation](ports-adapters-and-acl.md).

## Project maintenance

- [Documentation ownership and validation](documentation.md)
- [Consolidation status](consolidation-status.md)
- [0.2.0 release plan](release-0.2.0.md)
- [Release operations](../maintainers/releasing.md)

Implementation notes explain mechanisms and trade-offs. They do not override the
[specification](../specification/index.md). Historical patch progress belongs in Git
history; keep unresolved design decisions with an explicit status and rationale.
