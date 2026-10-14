# Keep the meaning beside the model

Two developers can agree on a class name and still mean different things. Is an Order
a shopping cart, an unconfirmed request, or a binding purchase? Record the distinction
where everyone working on the model can see it.

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

The Order term and aggregate have exactly the same namespace/name, so Vernac associates
them. Their order in the file is irrelevant. You can move terms into `vocabulary.vernac`
in the same directory without losing that association. The quoted balance concept needs
no class: not every useful domain word deserves a runtime object.

Hover over an Order type reference or its declaration. The IDE shows its definition,
example and note alongside the technical type identity. The vocabulary link opens the
term source. Navigate from a term named Order to its aggregate. Generated Java source
also includes the term's content in its JavaDoc.

Run the normal build:

```text
mvn clean compile
```

Open `target/vernac-glossary.md` for a readable glossary, or use
`target/vernac-glossary.json` as project context for other tools. Generated files are
replaced from source; edit the `.vernac` declaration rather than those outputs.

A module publishing Order automatically includes its associated documentation in its
public metadata. Consumers build against the dependency normally and see that content
in Vernac type hover, even if no original source attachment is installed. The
[type-module example](../../examples/type-modules/README.md) includes a documented Money
value for trying this across separate artifacts. Rebuild both sides with the updated
SNAPSHOT tools; module-contracts-9 metadata is required.

Descriptions should explain domain meaning, not repeat a field list. Discuss a concrete
case with a domain expert, adjust the definition and model together, and review both in
one change. A glossary helps maintain shared language; it cannot replace that discussion.

For AI-assisted work, provide the current language reference plus the relevant project
glossary and model. Ask the AI to preserve these meanings and flag ambiguity. Glossary
output is available now; an in-plugin LLM, automatic fixes and project-specific inference
are not part of this feature.

See the [term contract](../contracts/ubiquitous-language.md) for identity, duplicate
checks, publication boundaries and compatibility behavior.
