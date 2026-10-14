# Ubiquitous language close to the model

Status: **the first term increment is implemented**. The authoritative current rules
are in [the contract](../contracts/ubiquitous-language.md). It includes description,
examples, notes, exact-name associations, module type documentation, hover and glossary
output. The rest of this original exploration records options; proposed explicit links,
synonyms, wider glossary publication and AI integration remain deferred.

## Purpose

A developer and a domain expert should be able to explain what a Customer, Order or
Reservation means in this particular model, see those meanings beside the executable
definitions, and change both in one reviewed commit. An AI should receive the same
vocabulary and boundaries when extending that model.

A glossary is a useful maintained artifact of ubiquitous language. It is not the
whole practice: conversations, examples, operation names, rules and model changes
still matter. Compiler acceptance cannot certify that a definition is true or that
stakeholders agree with it.

The intended benefits are:

- Precise, discoverable meanings in version control beside the domain model.
- Less drift between documentation and code, with IDE visibility at the point of use.
- A browsable/exportable glossary accessible without reading implementation code.
- Project-specific context for AI naming and modeling, alongside the language reference.
- Clear differences between similar words in different contexts.

## Minimal source shape

Implemented core syntax (the contract is authoritative):

```text
namespace sales.orders;

term Customer {
    description "A person or organization placing orders.";
}

term Order {
    description "A customer's request to purchase products.";
}

id OrderId;
id CustomerId;
aggregate Order[OrderId](CustomerId);
```

Terms may live beside the relevant declaration or in a dedicated `.vernac` file
under the ordinary namespace directory, such as `vocabulary.vernac`. Source order
has no meaning. No separate glossary format should have to duplicate the same content.
Entry statements require semicolons, consistent with other declarative metadata blocks.

## Meaning, scope and identity

A term has a namespace-qualified identifier, such as `sales.orders.Customer`.
It has a separate symbol category from executable types, so `term Order` and
`aggregate Order` can coexist. A term is not a type and cannot be used as a field,
method parameter, DTO type, repository target or injected dependency.

Its identifier follows the shared Unicode/name rules applicable to the relevant
symbol category. Descriptions are ordinary Unicode text, not restricted Java names.

A namespace qualifies vocabulary; it does not automatically declare a DDD bounded
context. Neither a namespace hierarchy nor a Maven module proves that two meanings
are the same. No term meaning is inherited from parent namespaces. A Billing Customer
and a Sales Customer may legitimately have different definitions.

Terms may describe things without a corresponding class: a business role, operation,
state, policy, distinction or process. Missing an executable type is not an error.

## Connection to executable definitions

The implemented convention is an exact namespace/name association between a term and
an executable definition. `sales.orders.Order` describes the same-named aggregate;
there is no matching by pluralization, spelling similarity or English stemming.
This is a documentation association, not another generated runtime type.

A term can describe a broader concept than one type. CustomerId, a customer field,
a registration usecase and a CustomerRegistered event may all relate to Customer.
Do not force these to duplicate the same definition. A future increment could support explicit references to a term on fields, behavior
operations and other definitions. The current increment uses exact-name associations only.
The exact reference syntax is open; no annotation syntax or implicit import behavior
is agreed here.

If terms and types have different names, any connection must be explicit. A source
rename must not quietly leave a misleading association: the IDE should preview or
report the corresponding change. This requirement must be scoped against the larger
cross-language refactoring workstream rather than falsely claiming it already exists.

## Useful content and what to defer

The minimum useful term contains a nonblank description. Prefer a domain definition
with distinguishing meaning over "Customer is a customer" or a restatement of fields.
Examples and exclusions often add more value than extra mandatory metadata.

For example: is an Order already binding, or only a request? Is a Customer the payer,
recipient, contractual party or some combination? What is explicitly not an Order?
Descriptions should answer these context-specific questions.

Before fixing the first grammar, decide whether to include:

| Candidate | Purpose and boundary |
| --- | --- |
| Examples | Concrete domain situations; repeatable text, not executable tests unless separately designed |
| Notes/exclusions | Explain boundaries and misleading similarities |
| Approved synonyms | Record genuinely equivalent vocabulary in this context; never create compiler type aliases |
| Discouraged words | Guide humans and AI away from a misleading local name; no substring scan of arbitrary Java or prose |
| Related terms | Resolved references, with a separate term lookup category; not inferred type relations |

Do not require all of these for the initial feature. The implemented increment provides required description, optional examples/notes,
exact-name linkage, IDE presentation and an export suitable for reviewers and AI. Synonym/exclusion guidance is a likely
next addition. Localization, ontologies, automatic natural-language inference and
repository-wide naming enforcement remain outside the initial scope.

A project can use the domain experts' working language. English is not imposed on
application vocabulary. If display labels/translations are later added, distinguish
them from the identifier chosen for source code.

## Diagnostics and consistency

Deterministic errors should cover duplicate term identities, absent/blank descriptions,
invalid identifiers, unresolved explicit term references and ambiguous associations.
Multiple files in one namespace still contain one definition of each term identity.

The compiler cannot decide whether two prose definitions contradict each other, whether
a synonym is truly equivalent, or whether arbitrary Java follows the intended meaning.
Such checks belong in domain review or optional AI assistance with explanations.
Do not report them as proven compiler errors.

Coverage checks such as "public aggregates should have a term" should initially be
opt-in diagnostics, not a barrier to incremental adoption. Technical helper names and
generated types are not automatically vocabulary violations.

## Modules, generated output and evolution

Vocabulary should eventually travel with published module documentation so consumers
can understand exported concepts. Decide term visibility, associated-type exports,
standalone term publication and term-only imports before extending module metadata.
An ordinary `export sales.orders.Order;` currently exports an executable definition;
coexisting term names must not make that declaration ambiguous.

Descriptions must have one authoritative source and a separate documentation identity
or fingerprint, if needed. Prose edits must not change Java signatures, structural
compatibility fingerprints, SQL schemas, migration histories or runtime activation.
A substantive meaning change can still require a human-led model/API/data migration;
"documentation-only" mechanics cannot certify semantic compatibility.

A first release need not solve transitive glossary federation. It must document its
scope honestly: local glossary support and dependency vocabulary support are distinct
capabilities. Do not silently treat imported module terminology as authoritative for
an unrelated local bounded context.

No Java class, database table or Spring bean is generated for a term. Possible outputs
are a readable glossary and data-only machine export, plus IDE/JavaDoc presentation
for associated definitions. Build goal names and output formats are not specified yet.
Outputs should be deterministic and namespace-qualified, with source locations and a
format version. Avoid absolute build-machine paths in published artifacts.

## Human and AI workflow

1. Discuss a concrete example with a domain expert.
2. Define or refine its term beside the model in the same change.
3. Review code names, operations and examples against that definition.
4. Surface the definition in hover/navigation and a readable glossary.
5. Provide the relevant glossary subset to an AI together with the current language
   reference and model/API context.
6. Review AI output against the same domain meaning; do not accept plausible names
   solely because the code compiles.

The AI export should preserve qualified identity, definition, examples, related model
symbols, source location, and any explicitly approved/discouraged vocabulary. It should
clearly separate local meanings from dependency meanings. Conflicting or incomplete
context should lead to a question, not an invented equivalence.

The expected AI benefit is a hypothesis worth testing, not a guarantee. Use a small
benchmark: ask models to extend the same project with and without its glossary, then
compare terminology, domain assumptions, unnecessary aliases and human review effort.
Human-reviewed criteria matter more than a model grading its own output.

## Implementation gates and verification

Before coding, agree on term lookup/import rules, exact-name association behavior,
first metadata fields, symbol-reference scope and local-versus-module delivery.
Apply the shared feature checklist, including:

- Parser/resolver support across files/namespaces, duplicates and Unicode positions.
- No pollution of the executable type lookup or generated output-name checks.
- Terms and same-named model types coexist; standalone concepts remain valid.
- Prose-only edits leave generated Java structure, schema and compatibility unchanged.
- Plugin highlighting, term/type hover, navigation and relevant completion.
- A reviewer-facing glossary and a machine-readable output with deterministic content.
- Tutorial/FAQ and AI reference updates only once the syntax is actually supported.

## Background

- Eric Evans, [DDD Reference](https://www.domainlanguage.com/wp-content/uploads/2016/05/DDD_Reference_2015-03.pdf): shared language within an explicit bounded context.
- Martin Fowler, [Ubiquitous Language](https://martinfowler.com/bliki/UbiquitousLanguage.html): a common language grounded in the domain model and used in conversation and code.

This proposal makes a maintained artifact of that practice; it does not replace it.
