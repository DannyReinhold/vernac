// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.ast;
import java.util.List;

public record IdDeclarationNode(SourceLocation location, String name,
        List<CollectionDefinitionNode> collections) implements TopLevelDefinition {
    public IdDeclarationNode { collections = List.copyOf(collections); }
    public IdDeclarationNode(SourceLocation location, String name) { this(location, name, List.of()); }
}
