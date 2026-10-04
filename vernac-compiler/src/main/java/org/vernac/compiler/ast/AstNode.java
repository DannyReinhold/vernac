package org.vernac.compiler.ast;

public sealed interface AstNode permits
        CompilationUnitNode,
        TopLevelDefinition,
        CollectionDefinitionNode,
        IdReferenceNode,
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
        MappingStatementNode,
        EnumConstantNode {

    SourceLocation location();
}