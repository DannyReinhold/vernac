# Vernac platform roadmap

Status: **planning, not implemented contracts**. Updated 2026-10-14 after the 0.4.0
release. This records priorities and design questions, not a commitment to ship every
item in 0.5.0. Existing contracts remain authoritative.

## Immediate focus: language and developer experience

1. Deep IntelliJ integration: cross-language rename/refactoring, usage search,
   source-aware diagnostics, navigation and debugger investigation. Follow the
   [IDE roadmap](intellij-deep-integration.md), starting with one bounded workflow.
2. Language consistency and completion: review dependency/self access across
   usecases, services and adapters; review read/modify service operations and apply
   the [feature checklist](feature-review-checklist.md) to syntax, generated API,
   diagnostics, documentation and tooling together.
3. Standard adapter foundations: extend the existing port/ACL model for frequent
   integration styles. HTTP/REST remains important, but filesystem, messaging,
   databases and device integrations must remain expressible without HTTP assumptions.

Choose the next implementation slice after examining current code and defining its
contract. These are workstreams, not three simultaneous rewrites.

## Complex microservices and event-driven replication

Vernac should support independently deployed services with clear bounded-context
boundaries. A reusable Maven module is not a remote service or automatically a bounded
context. Cross-service contracts need explicit adapters and ACL rather than accidental
sharing of aggregate instances, tables or synchronous transactions.

Investigate Kafka inbound/outbound adapters and event-driven replication together:

- Distinguish internal domain events from externally published integration contracts;
  define payload evolution and the producer/consumer ACL explicitly.
- Build on durable publication/outbox foundations; specify the database-to-broker
  failure window, acknowledgement and retry behavior. A database rollback does not
  undo an already accepted broker publication.
- Define delivery guarantees, idempotent consumption/deduplication, consumer offsets,
  transaction boundaries, partition keys and the scope of ordering. Do not imply
  end-to-end exactly-once effects merely because the broker offers transactions.
- Model projection/read-model ownership, eventual consistency, initial population,
  replay/rebuild, recovery, lag visibility and failed-message handling.
- Distinguish a replica/projection from the source context's authoritative domain
  model. Do not introduce shared persistence or cross-service aggregate transactions.

The first executable example should have at least two independently runnable contexts,
a local Kafka setup, versioned integration messages, an idempotent consumer and a
queryable replicated view. Demonstrate duplicate delivery, temporary failure, restart
and recovery with automated tests. Tutorials should explain the lifecycle and limits,
not only show a successful publish/consume exchange.

## Compose a UI across bounded contexts

Investigate server-rendered HTML fragment composition for a unified user experience
across independently owned contexts. This composes views; it does not merge domain
models or erase context boundaries.

Specify fragment ownership and routing, a shell/composition boundary, authentication
and authorization propagation, tenant context, timeouts and partial failure behavior.
Review CSRF/XSS boundaries, assets/styles, navigation and browser interactions with
real examples. Evaluate server-side composition and browser-requested fragments before
choosing a standard adapter. Do not assume every fragment shares a process or database.

Provide a coherent multi-context example and tutorials covering fragment contracts,
one unavailable context, independent deployment and the user-visible consistency
boundary between a command and an asynchronously updated read model. Keep the initial
Kafka example independently usable; do not require UI composition to learn messaging.

## Separate SaaS extension project

Identity/user management, subscriptions, Mollie/Stripe payments, Brevo mail and local
simulation adapters belong in a separately versioned and released extension project.
Vernac supplies general module composition, ports/adapters, ACL, configuration,
transactions/events and suitable policy extension points. Provider-specific SaaS
concepts should not become core keywords by default.

Keep [capabilities/authorization](capabilities-and-usecase-authorization.md) as design
input, not the immediate core implementation priority. Distinguish general execution
policy hooks from subscription-specific entitlement and quota semantics. Use real
extension examples to establish what belongs in the core.

The separate project's licensing/commercial model, potentially hybrid, is undecided.
No Vernac licensing change is proposed by this roadmap. Define the extension boundary,
distribution units and maintenance responsibilities before choosing that model.

## Later: strategic DDD

Evaluate explicit bounded contexts, context maps and relationships, upstream/downstream
contracts, published languages, ACL and shared-kernel declarations against working
multi-service examples. Existing namespaces, modules and vocabulary terms MUST NOT be
silently reinterpreted as bounded contexts. Start with documentation and architecture
validation value; add language constructs only when they provide clear user benefit.

The [tactical DDD backlog](ddd-modeling-roadmap.md) remains separate. Neither backlog
requires every DDD pattern to have a keyword or a mandatory distributed deployment.
