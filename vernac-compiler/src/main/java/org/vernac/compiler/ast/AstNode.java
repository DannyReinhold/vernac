package org.vernac.compiler.ast;

public sealed interface AstNode permits
        CompilationUnitNode,
        TopLevelDefinition,
        CollectionDefinitionNode,
        EntityNode,
        FieldNode,
        TypeNode,
        ValidationRuleNode,
        InvariantNode,
        MethodNode,
        AnnotationNode,
        ExternalSchemaNode,
        ServiceMethodNode,
        MappingStatementNode {
    SourceLocation location();
}