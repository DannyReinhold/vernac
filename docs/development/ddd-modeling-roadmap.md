# DDD modeling follow-up

Status: **planning, not implemented language features**. Recorded after completing
versioned modules in the 0.4.0 development line. These items do not reopen the module
contract or imply that every DDD pattern needs a dedicated language keyword.

## Priority and review questions

| Priority | Topic | User problem and next decision |
| --- | --- | --- |
| 1 | Aggregate boundary and invariant protection | A mutable child can escape through a getter and change outside a root modify operation. Determine which ownership/access guarantees are worthwhile without requiring candidate-state rollback or deep proxy machinery. |
| 2 | Domain uniqueness under concurrency | Checking a repository before insertion cannot prevent two concurrent inserts. Define scoped uniqueness, its persistence guarantee and useful domain error translation; a single-result query is not a write constraint. |
| 3 | Reusable specifications and policies | Share named eligibility rules across decisions, validation and selection, with useful rejection explanations. Start with existing behavior/services; justify a new construct only where it removes real repetition. Arbitrary rule-to-SQL translation is not assumed. |
| 3 | Named domain creation operations | Express opening an account, beginning a draft or constructing an order from an offer. Explore factory services and whether unrestricted generated create factories should remain available in every such model. |
| 4 | Closed domain variants and polymorphism | Model alternatives with different required payloads without a large group of optional fields. Investigate value semantics, Java sealed-type representation, exhaustive matching, persistence and versioned-module compatibility together. |
| Later | Long-running processes | Evaluate process managers/sagas, timeouts, compensation and explicit state-machine support against real multi-step examples. Existing events/listeners/usecases already supply basic building blocks. |
| Later | Optional architecture extensions | Separate read models and event sourcing are possible future choices, not prerequisites for tactical DDD or promises of the current persistence/event implementation. |

The ordering is a recommendation, not a commitment to implement all items before
a particular release. Run the [feature checklist](feature-review-checklist.md) for each proposal.

## Mandatory 0.6.0 follow-up: safe custom persistence and webhook ingress

Track [tenant-safe webhooks and handwritten repositories](tenant-webhooks-and-repository-extensions.md)
as explicit release prerequisites A22/A23. Do not confuse generated persistence safety
with protection of arbitrary interface replacements. A supported extension path must
bind before SQL, reuse graph hydration/writes, validate returned provenance/tenant/version
and ship compiled tutorials plus negative tests. Provider webhook routing must establish
trusted external-resource ownership before opening business context. These requirements
are planned; they do not introduce repository behavior or webhook syntax yet.

## Already supported foundations

IDs and identity-based entities; immutable VOs and enums; explicitly named lists/sets;
aggregate containment rules and validation; factories and reconstruction; domain
services; aggregate repositories and optimistic locking; synchronous/deferred domain
events; application usecases; ports/ACL and versioned reusable modules are implemented.
The priorities above concern stronger guarantees or more expressive modeling, not
replacement of these foundations.

Direct Java-object mutation after failed validation and transaction rollback remains
the documented behavior. Strengthening aggregate access does not by itself require
undoing those accepted failure semantics. The earlier candidate-state proposal remains
in [its planning record](entity-behavior-and-change-contexts.md).

## Ubiquitous language as model metadata

Track [the ubiquitous-language proposal](ubiquitous-language.md) as a separate, potentially
small modeling/tooling feature. It connects vocabulary, executable definitions,
human-readable documentation and AI context. The first term/hover/glossary increment is implemented; it does not introduce
a new bounded-context mechanism or a global naming-enforcement engine.

## Existing independent workstreams

- [Capabilities and usecase authorization](capabilities-and-usecase-authorization.md):
  permissions, scope, subscription limits and reusable SaaS capabilities.
- [Deep IntelliJ integration](intellij-deep-integration.md): cross-language refactoring,
  diagnostics, navigation and debugger investigation.

## Sources for the assessment

- Eric Evans, [Domain-Driven Design Reference](https://www.domainlanguage.com/wp-content/uploads/2016/05/DDD_Reference_2015-03.pdf).
- Vaughn Vernon, [Effective Aggregate Design](https://www.dddcommunity.org/library/vernon_2011/).
- Eric Evans and Martin Fowler, [Specifications](https://martinfowler.com/apsupp/spec.pdf).

These sources describe design patterns and trade-offs, not requirements for particular
Vernac keywords. Keep Vernac product choices explicit.

## Strategic and distributed-system follow-up

Track Kafka replication, cross-context HTML fragment composition and later strategic
DDD evaluation in the [platform roadmap](platform-roadmap.md). These do not imply
that a namespace or Maven module is a bounded context. SaaS-specific capabilities
are now part of the coordinated [0.6.0 SaaS objective](saas-0.6.0-plan.md).
Core/extension packaging remains open; the full outcome is required regardless of
repository boundaries.


## Long-term platform architecture investigation

Vernac currently builds Java/Spring Boot applications. Consider a deliberate split
between framework-neutral mechanisms and Spring-specific generation/runtime only as
a distant architecture investigation, after the current language/SaaS priorities.
It is not 0.6.0 scope or a promise of another backend. Preserve plain domain testing
without confusing it with Spring-independent application support. See the
[compiler platform guidance](compiler-architecture.md#supported-platform-and-future-separation).
