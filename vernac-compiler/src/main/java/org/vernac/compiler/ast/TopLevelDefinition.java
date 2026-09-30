package org.vernac.compiler.ast;

public sealed interface TopLevelDefinition extends AstNode permits
        ValueObjectNode,
        AggregateNode,
        EntityNode,
        EventNode,
        RepositoryNode,
        PortNode {
}