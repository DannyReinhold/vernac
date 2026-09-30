package org.vernac.compiler.ast;

public record RestConfigNode(
        String key,
        String value,
        SourceLocation location
) implements AstNode {
}
