package org.vernac.compiler.ast;

import java.util.List;

public record MappingBlockNode(
        List<MappingStatementNode> statements,
        SourceLocation location
) implements AstNode {
}