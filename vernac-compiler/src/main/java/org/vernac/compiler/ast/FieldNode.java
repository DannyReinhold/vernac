package org.vernac.compiler.ast;

public record FieldNode(
        SourceLocation location,
        TypeNode type,
        String name
) implements AstNode {}