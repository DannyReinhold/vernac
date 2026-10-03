package org.vernac.compiler.ast;

public record RawJavaStatementNode(
        SourceLocation location,
        String javaCode
) implements UseCaseStatementNode {
}