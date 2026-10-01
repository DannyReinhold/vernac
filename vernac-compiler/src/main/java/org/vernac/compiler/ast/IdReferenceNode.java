package org.vernac.compiler.ast;

public record IdReferenceNode(
        SourceLocation location,
        TypeNode type
) implements AstNode {
    public String fieldName() {
        return "id";
    }
}