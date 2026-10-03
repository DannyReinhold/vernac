package org.vernac.compiler.ast;

import java.util.List;

public record TupleReturnNode(
        SourceLocation location,
        List<TupleElementNode> elements
) implements ReturnStatementNode {
}