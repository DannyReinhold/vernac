# Contributing to Vernac

Start with the repository [contribution guide](https://github.com/DannyReinhold/vernac/blob/main/CONTRIBUTING.md) for issue reports,
local verification and pull requests.

User documentation starts at the [documentation home](../index.md). This section
is for language/tooling contributors. Build with JDK 25 and Maven 3.9+; generated
application code retains its Java 21 target.

```shell
mvn clean verify
```

Standalone examples have their own POMs and checks. PostgreSQL tests need explicit
disposable-database configuration and must not be counted as passed when skipped.
Release acceptance also includes installation of the packaged IntelliJ plugin.

## Learn from the IntelliJ integration

[Lessons learned](lessons-learned/index.md) collects reusable engineering guidance
on DSL/Java injection, resolution versus completion, native references, debugging,
editor recovery and test boundaries. It includes a design checklist for a future
SQLJ plugin. These are contributor notes, not additional language guarantees.
For a guide across all documentation, use the [documentation map](../documentation-map.md).

## Implementation and decisions

- [Compiler architecture](compiler-architecture.md): shared resolution, names and generation.
- [Feature review checklist](feature-review-checklist.md): purpose, quality, diagnostics and tooling.
- [Generated output ownership](generated-output.md): reconciliation and failure boundaries.
- [Shared Java implementation contract](../contracts/implementation-contracts.md): nullable parameters, implementation targets and erased overload checks.
- [Validation delegates](validation-delegates.md): access views and Java projection.
- [Entity changes and candidate-state design](entity-behavior-and-change-contexts.md): current direct mutation and
  explicitly deferred alternatives.
- [LSP workspace analysis](lsp-workspace-analysis.md), [embedded Java](intellij-java-behavior.md), [Java navigation](java-to-vernac-navigation.md).
- [Schema tooling](postgresql-schema-tooling.md) and [scalar mapping decisions](scalar-persistence-audit.md).
- [Event delivery](event-delivery.md) and [ports implementation](ports-adapters-and-acl.md).

## Current priorities

- [Platform roadmap](platform-roadmap.md): IDE depth, language consistency, standard
  adapters, Kafka replication, HTML fragment composition and the separate SaaS project.

## Modeling follow-up

- [DDD modeling roadmap](ddd-modeling-roadmap.md): aggregate protection, concurrency-safe uniqueness, reusable rules, factories and domain variants.
- [Ubiquitous-language proposal](ubiquitous-language.md): implemented initial term/hover/glossary support, with broader vocabulary and AI work deferred.

## Project maintenance

- [Documentation ownership and validation](documentation.md)
- [Deep IntelliJ integration roadmap](intellij-deep-integration.md): cross-language refactoring and debugger
  integration (implemented) and refactoring follow-up.
- [Versioned module completion](module-completion-roadmap.md): implemented scope, regression entrypoints and operational boundaries.
- [Capabilities and usecase authorization](capabilities-and-usecase-authorization.md): future SaaS policy and reusable capability design on the implemented module system.
- [Consolidation status](consolidation-status.md)
- [Pagination implementation sequence](pagination.md): runtime foundation and remaining repository/Result/HTTP work.
- [0.4.0 release plan](release-0.4.0.md)
- [0.5.0 release preparation](release-0.5.0.md)
- [Release operations](../maintainers/releasing.md)

Implementation notes explain mechanisms and trade-offs. They do not override the
[specification](../specification/index.md). Historical patch progress belongs in Git
history; keep unresolved design decisions with an explicit status and rationale.

## Native debugger implementation

The [debugger implementation record](vernac-debugger-prototype.md) documents build-time source
mappings, Java-backed Vernac breakpoints and the installed-IDE acceptance walkthrough.

## Related assessments and editor mechanisms

- [AI content disclosure review for vernac.org](ai-content-disclosure-review.md): assessment notes for the site's publication workflow; recheck if that workflow changes.

- [AI tools reference](../ai-tools-reference.md): concise build, schema and migration workflow for coding agents.

- [Editor declaration recovery](editor-declaration-recovery.md): declaration-level recovery, strict build separation and source ranges.

- [Live value-object Java APIs](live-value-object-java-apis.md): in-memory Java views before generation, ownership and scope boundaries.
