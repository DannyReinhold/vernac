# Ubiquitous language and term documentation

Status: implemented in 0.4.0-SNAPSHOT. A term documents a concept; it is not an
executable definition, field type, dependency, table or Spring bean.

## Declarations and identity

```vernac
namespace example.vocabulary;

term Order {
    description "A customer's submitted request to purchase products.";
    example "An order awaiting payment.";
    note "A shopping cart is not yet an order.";
}
term "Outstanding balance" {
    description "The amount still owed for an order.";
}

id OrderId;
value CustomerName(String);
aggregate Order[OrderId](CustomerName);
```

A term appears at top level in an ordinary `.vernac` file. Namespace/path discovery,
UTF-8 decoding and multi-file analysis follow the existing project rules. Terms may
be beside model definitions or in a separate vocabulary file in the same namespace;
source order has no meaning.

The name MUST be an unqualified identifier/keyword segment or a quoted JSON string.
Quoted names MAY contain spaces and other characters. Names MUST NOT be blank, have
surrounding whitespace or contain control characters, including line breaks and tabs.
Strings use the existing JSON escape spelling. Internal spaces are preserved.
Comparison is exact and case-sensitive, without stemming, pluralization or Unicode
normalization. `term Order` and `term "Order"` have the same identity.

Term identity is the pair (namespace, decoded name), separate from executable type
identity. Duplicate terms across files in that namespace are errors. `term Order` and
`aggregate Order` MUST coexist. A standalone concept without a corresponding type is
valid. A term MUST NOT be accepted where a type is required.

There MUST be exactly one nonblank `description`. Zero or more nonblank `example`
and `note` entries MAY follow or precede it. Each entry requires a semicolon; the
term block's final semicolon is optional. Notes can explain exclusions, boundaries or
misleading vocabulary without defining aliases or compiler-enforced prose rules.

## Association with model definitions

A term describes an executable definition only when both its namespace and decoded
name equal that definition's namespace and name. The same rule applies to explicitly
named collections. Quoted names matching an identifier associate identically.

A quoted name containing spaces or dots is not split into a qualified type name.
Parent namespaces, imports, spelling similarity and synonyms MUST NOT create an
association. There is no explicit annotation/reference syntax in this increment.

Namespaced vocabulary does not declare a DDD bounded context. Neither namespace
hierarchy nor Maven dependency implies shared meaning. Different namespaces MAY
define Customer differently. Terms support the team's shared language; they cannot
prove that domain experts agree or that implementation behavior matches the prose.

## Presentation and output

Type hover in Vernac shows the technical kind/qualified name and associated term
content. A link opens its vocabulary definition. Hover on a term name shows the term;
Go To Declaration on a same-named term opens its model type. Standalone terms remain
navigable at their source definition. Completion offers term entries and a top-level
term snippet; vocabulary names MUST NOT pollute type completion.

Generated public domain classes/collections, usecases and outbound adapters include
associated descriptions/examples/notes in JavaDoc. This supports ordinary Java source
documentation, including generated-source views. Java class files do not retain JavaDoc;
full Java-side documentation for binary dependencies still depends on available source
or documentation attachments. Vernac module hovers do not have that dependency.

Successful Maven Vernac compilation writes deterministic documentation files:

- `target/vernac-glossary.json`: formatVersion 1 and sorted namespace-qualified term
  entries with description, examples, notes and source positions.
- `target/vernac-glossary.md`: a readable glossary of local and imported vocabulary.

These are generated outputs, not compiler input or manually maintained duplicates.
Local source paths are relative to the Vernac source root. Line/column positions are
one-based, with Unicode code-point columns; LSP presentation uses UTF-16. Imported entries identify a
dependency contract view, not a fictitious local producer implementation. Descriptions
are plain text; renderers escape markup rather than executing embedded HTML/Markdown.

## Published modules

The module descriptor includes documentation associated with its explicitly exported
definitions. No separate export of the term is required. Internal-type vocabulary and
unassociated standalone concepts are not published by this increment. Vocabulary from
another module is not implicitly re-exported. No glossary-only import syntax is added.

Consumers obtain term content from the effective dependency metadata; original source
attachments are optional. Hover on an imported type therefore includes its module's
associated documentation even without a sources JAR. Build after dependency changes;
the existing index refresh behavior also refreshes documentation.

Module metadata uses `module-contracts-9`; rebuild development producers and consumers.
Documentation lives outside executable Definition fingerprints and required structural
contracts. A prose-only change MUST NOT change Java signatures, type compatibility,
SQL mappings, migrations or activation. Generated JavaDoc and glossary output may change.
A substantive meaning change can still require a human-led API/data migration; the
compiler does not certify semantic compatibility from unchanged structure.

## Deliberate limits

No term inheritance, explicit cross-name links, synonym aliases, automatic terminology
renaming, semantic prose validation, LLM integration or project-specific model training
is introduced. Future AI tools may consume the same glossary output. Fine-tuning on
Vernac syntax and supplying application vocabulary are separate concerns.
