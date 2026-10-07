# Language feature review checklist

Status: agreed review process. Apply to existing features as well as proposals.

Review from broad user goals toward precise semantics and implementation. Keep
individual decisions small enough to discuss. A feature need not wait for a full
language redesign before it can be implemented and verified.

## Two directions of review

1. Given a feature: what does an application developer use it for?
2. Given a real domain/application problem: which Vernac feature solves it, and
   where must the developer use Java or another tool?

Use concrete examples for both directions. Distinguish DDD principles from Vernac
product choices; do not present one preferred syntax as a DDD requirement.

## Questions for every feature

| Perspective | Review questions |
| --- | --- |
| User purpose | What concrete task becomes easier? What is the smallest useful example? |
| Problem coverage | Can users solve realistic variants? Are there gaps or unnecessary workarounds? |
| DDD quality | Does the model express domain meaning and protect the appropriate boundaries and invariants? |
| Boilerplate | Which repetition disappears? Which explicit information remains valuable? |
| Language consistency | Are similar problems solved by similar rules? Are exceptions necessary, predictable and explained? Do defaults compose with other features? |
| Generated Java quality | Would a developer be proud to maintain this API? Are names, factories, nullability, equality and visibility coherent? |
| Java interoperability | Can hand-written Java construct, consume and extend behavior through the intended boundaries? Which errors belong to Vernac and which to javac? |
| API stability | What changes when fields, names, namespaces or dependencies change? Can unrelated edits change generated names or behavior? |
| Compiler and tool quality | Is each rule implemented once at an appropriate layer? Do parser, semantic analysis, generators, LSP and plugin consume shared rules and resolved data rather than independently guessing? |
| Architecture and testability | Are responsibilities clear? Is the change understandable and testable without redundant infrastructure or hidden coupling? |
| Diagnostics | Are errors early, specific and located correctly? Are conflicts explained? Are suggested fixes valid in context? |
| IDE support | Does the plugin support creation, highlighting, analysis, completion and navigation where relevant? Are compiler and editor semantics consistent? |
| Documentation | Is the contract clear and discoverable? Are examples current? Which tutorials and FAQs help users apply the feature? |
| Verification | Are normal use, invalid use, edge cases and feature interactions covered? Is generated Java compiled when that materially verifies behavior? |
| Scope and status | What is agreed, implemented, tested or deferred? What remains outside the guarantee? |

## Evidence and tests

Prefer tests that verify user-visible behavior over tests that mirror internal
implementation. Preserve existing useful coverage. For each material rule include
positive and negative examples and relevant interactions with other features.

Inspect the generators during each feature review. A correct AST is not sufficient
proof of a correct Java API. Conversely, a javac failure is not a substitute for a
Vernac diagnostic when Vernac has enough information to detect the error.

Use end-to-end or build-level checks where the claim crosses component boundaries.
For example, source cleanup tests cannot prove that Maven removes stale bytecode
from a JAR. Document that ownership boundary rather than silently widening scope.

Unreviewed language areas may remain incomplete during the refactoring. Record
findings there without automatically expanding the current task. Regressions in
already reviewed features must be investigated promptly.

## Shared rules, not duplicated decisions

Shared implementation is both a language-consistency requirement and a code-quality
goal. In particular, default naming, type identity and Java signature comparison
should have authoritative representations. Generators and tools should consume
resolved information instead of reconstructing it from strings.

Sharing does not mean putting every responsibility into one large class. Keep
parsing, resolution, validation, generation and presentation separate. The plugin
may present a compiler diagnostic differently; it must not invent different rules.

## Review outputs and locations

- `docs/contracts`: observable promises to application developers and generated API contracts.
- `docs/specification`: formal syntax, resolution and language semantics.
- `docs/development`: implementation decisions, this review process, verification and open work.
- `docs/tutorials`: guided application and experiments, with links to the relevant contracts.

Avoid duplicating normative rules across these locations. Reference the authoritative
document. Clearly label accepted design that is not yet implemented.

For each review record: the agreed rules, concrete examples, required diagnostics,
test cases, IDE implications, documentation work, exclusions and next implementation
step. Apply the questions proportionately; do not force irrelevant categories.

## Current example: default names and API collisions

The [names contract](../contracts/names-and-unicode.md) records the decisions.
The implementation removes the implicit single-field `value` fallback, centralizes
default-name derivation, and validates effective fields and resolved Java signatures
for ordinary VOs. ID customization is rejected by the existing declaration syntax.
See [implementation and verification notes](member-naming.md) for current status.

Regression cases include `int` versus `IntValue`, acronym runs, Unicode names,
adding a second field, qualified type references, generated getters/factories,
inherited final methods, valid overloads and unsafe diagnostic suggestions.

Full Java-body analysis, enum redesign, collection semantics and SQL naming are
separate reviews. Entity/aggregate identity is acknowledged here; their full API
conformance belongs to their own review.
