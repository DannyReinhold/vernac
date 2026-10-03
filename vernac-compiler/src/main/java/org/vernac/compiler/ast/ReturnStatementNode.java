package org.vernac.compiler.ast;

public sealed interface ReturnStatementNode permits
        SingleReturnNode,
        TupleReturnNode {
}