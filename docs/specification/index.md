# Vernac specification and contracts

This is the normative documentation for the **0.4.0-SNAPSHOT development language revision**. It is
organized into language rules and feature contracts; both parts define the language.
MUST/MUST NOT express requirements, SHOULD a recommendation, and MAY a permission.
Examples illustrate rules; explicitly incomplete fragments require their surrounding
model. Planned features are not guarantees.

The [language reference](../language-reference.md) is the usage-oriented overview.
The grammar describes parsing, not the complete supported language: semantic
resolution and feature contracts also constrain accepted programs.

## Language foundations

- [Namespaces and imports](namespaces-and-imports.md): source layout, identities and resolution.
- [Domain field types and nullness](domain-field-types.md): allowed types and optional values.
- [Names and Unicode](../contracts/names-and-unicode.md): identifiers, defaults and collisions.

- [Ubiquitous language](../contracts/ubiquitous-language.md): term documentation and exact-name model associations.

## Domain and application contracts

- [Value objects](../contracts/value-objects.md)
- [Enum values](../contracts/enum-value-objects.md)
- [Named collections](../contracts/collections.md)
- [Behavior](../contracts/behavior.md)
- [Entities and aggregates](../contracts/entities-and-aggregates.md)
- [Usecases](../contracts/usecases.md)
- [Domain services](../contracts/domain-services.md)
- [Versioned modules](../contracts/modules.md): exports, compiled reuse, activation and independent histories.
- [Events and listeners](../contracts/events.md)

## Persistence and integration contracts

- [PostgreSQL mapping](../contracts/postgresql-persistence.md)
- [Scalar representation limits](../contracts/scalar-storage-limits.md)
- [Repository queries](../contracts/repository-queries.md)
- [Pagination runtime foundation](../contracts/pagination.md): offset/cursor queries and completed HTTP results.
- [HTTP inbound adapters](../contracts/inbound-http.md)
- [Ports and adapters](../contracts/ports-adapters-and-acl.md)
- [Adapter configuration](../contracts/adapter-configuration.md)
- [Adapter DTOs and JSON](../contracts/adapter-dtos.md)
- [Mappings](../contracts/adapter-mappings.md)
- [REST declarations](../contracts/declarative-rest.md)
- [HTTP runtime and failures](../contracts/http-adapter-runtime.md)

## Authority and maintenance

Each rule has one authoritative home. Tutorials explain it and references summarize
it; they MUST NOT introduce contradictory defaults. `contracts` specifies observable
APIs and runtime semantics; `specification` specifies cross-cutting language semantics.
These are two sections of the same specification, not competing sources of truth.

Implementation notes and test results are evidence, not normative promises. When a
contract and implementation disagree, investigate and correct the defect or explicitly
revise the contract. Do not silently treat an old document, a parser production or
a generated excerpt as a new supported feature.

See [documentation maintenance](../development/documentation.md) and the
[release plan](../development/release-0.3.0.md) for remaining verification work.

## Subsequent design work

SaaS policies and deeper cross-language IDE integration remain separately planned. The
[module completion record](../development/module-completion-roadmap.md) defines the delivered 0.4.0 scope.
