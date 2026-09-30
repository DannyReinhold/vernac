package org.vernac.compiler.ast;

public record MappingStatementNode(
        String sourcePath,
        String targetPath,
        String direction, // "->" oder "<-"
        SourceLocation location
) implements AstNode {
}