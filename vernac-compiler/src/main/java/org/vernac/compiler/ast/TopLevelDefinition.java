package org.vernac.compiler.ast;

public sealed interface TopLevelDefinition extends AstNode permits
        IdDeclarationNode,
        ValueObjectNode,
        AggregateNode,
        EntityNode,
        EventNode,
        RepositoryNode,
        PortNode,
        UseCaseNode,
        DomainServiceNode {
}