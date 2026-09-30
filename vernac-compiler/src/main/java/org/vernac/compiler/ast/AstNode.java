package org.vernac.compiler.ast;

public sealed interface AstNode permits
        CompilationUnitNode,
        TopLevelDefinition,
        CollectionDefinitionNode,
        IdDefinitionNode,
        FieldNode,
        TypeNode,
        ValidationRuleNode,
        InvariantNode,
        MethodNode,
        SchemaNode,
        PortMethodNode,
        AdapterNode,
        RestConfigNode,
        RestErrorRuleNode,
        MappingBlockNode,
        MappingStatementNode {

    SourceLocation location();
}