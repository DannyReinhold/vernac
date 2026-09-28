package org.vernac.compiler.ast;

public record InvariantNode(
        SourceLocation location,
        String name,
        String codeBlock
) implements AstNode {}