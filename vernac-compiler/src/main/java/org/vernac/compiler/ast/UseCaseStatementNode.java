package org.vernac.compiler.ast;

public sealed interface UseCaseStatementNode permits
        LoadStatementNode,
        SaveStatementNode,
        RawJavaStatementNode {
}