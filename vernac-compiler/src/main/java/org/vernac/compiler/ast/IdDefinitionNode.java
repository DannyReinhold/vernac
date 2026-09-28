package org.vernac.compiler.ast;

public record IdDefinitionNode(
        SourceLocation location,
        TypeNode type,
        String fieldName
) implements AstNode {
}