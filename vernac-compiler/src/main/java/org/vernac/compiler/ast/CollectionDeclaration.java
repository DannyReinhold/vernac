// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.ast;
import java.util.Optional;

/** One uniform view of explicitly requested collections on supported domain declarations. */
public record CollectionDeclaration(String elementName, CollectionDefinitionNode definition,
                                    Optional<TypeNode> idType, boolean valueElements) {
    public String name() { return definition.nameFor(elementName); }
    public static Optional<CollectionDeclaration> of(TopLevelDefinition node) {
        return switch (node) {
            case IdDeclarationNode id -> id.collection().map(c -> new CollectionDeclaration(id.name(), c, Optional.empty(), true));
            case ValueObjectNode value -> value.collection().map(c -> new CollectionDeclaration(value.name(), c, Optional.empty(), true));
            case EntityNode entity -> entity.collection().map(c -> new CollectionDeclaration(entity.name(), c, Optional.of(entity.idDefinition().type()), false));
            case AggregateNode aggregate -> aggregate.collection().map(c -> new CollectionDeclaration(aggregate.name(), c, Optional.of(aggregate.idDefinition().type()), false));
            default -> Optional.empty();
        };
    }
}
