package org.vernac.compiler.ast;

public record FieldNode(
        SourceLocation location,
        TypeNode type,
        String name,
        boolean isMutable
) implements AstNode {

    public FieldNode(SourceLocation location, TypeNode type, String name) {
        this(location, type, name, false);
    }
}