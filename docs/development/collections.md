# Collection implementation

The grammar models an explicit `list` or `set` clause as a
`CollectionDefinitionNode`. `CollectionDeclaration` provides a shared view of
collection declarations on IDs, values, enums, entities and aggregates.
The project index registers the resulting type in the owner's namespace.
Resolution uses that index, including references across files and imports.

`DomainCollectionGenerator` implements the common Java API once. Shared
algorithms live in `vernac-runtime`'s `DomainCollections`. Generated classes
retain their concrete domain type and own custom methods. No collection
packages or linguistic pluralization rules are applied.

The resolver prevents mutable domain objects from entering value objects through
collection fields. Custom method signatures are resolved and checked against the
generated API; embedded Java bodies are compiled by javac. General Java imports
inside custom code remain a separate design topic.

## Current scope

IDs, values, enums and their collections work through the multi-file project
pipeline. Entity/aggregate collection generation is covered through the existing
single-source compiler path. Full entity/aggregate migration to the project
pipeline remains a separate task; this change does not remove its explicit
unsupported-definition diagnostics. Persistence mapping is also separate.

## Verification

`CollectionContractTest` compiles generated Java and exercises public behavior,
including stored-instance preservation and identity lookups. Runtime tests cover
algorithms, null rejection and defensive copying. LSP symbol tests cover imported
collection completion/navigation and exclude entity collections in value fields.
Existing collection tests are retained and adapted to the new syntax/API.

Run the Maven verification build with the project's required JDK. Tests of the
packaged IntelliJ plugin additionally verify its distribution. A source-only or
isolated generator check does not replace the full Maven build and grammar
regeneration.
