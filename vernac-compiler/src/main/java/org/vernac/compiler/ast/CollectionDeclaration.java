// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.ast;
import java.util.List;
import java.util.Optional;

/** Uniform views of all explicitly requested collections on a domain declaration. */
public record CollectionDeclaration(String elementName, CollectionDefinitionNode definition,
                                    Optional<TypeNode> idType, boolean valueElements) {
    public String name() { return definition.nameFor(elementName); }
    public static List<CollectionDeclaration> of(TopLevelDefinition node) {
        return switch (node) {
            case IdDeclarationNode id -> declarations(id.name(), id.collections(), Optional.empty(), true);
            case ValueObjectNode value -> declarations(value.name(), value.collections(), Optional.empty(), true);
            case EntityNode entity -> declarations(entity.name(), entity.collections(), Optional.of(entity.idDefinition().type()), false);
            case AggregateNode aggregate -> declarations(aggregate.name(), aggregate.collections(), Optional.of(aggregate.idDefinition().type()), false);
            default -> List.of();
        };
    }
    private static List<CollectionDeclaration> declarations(String element, List<CollectionDefinitionNode> collections,
                                                            Optional<TypeNode> id, boolean values) {
        return collections.stream().map(c -> new CollectionDeclaration(element, c, id, values)).toList();
    }
}
