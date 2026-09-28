package org.vernac.compiler.ast;

public record MappingStatementNode(
        SourceLocation location,
        String sourceExpression,
        String targetField
) implements AstNode {}