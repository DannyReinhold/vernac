package org.vernac.compiler.ast;

import java.util.Optional;

public record SingleReturnNode(
        SourceLocation location,
        Optional<String> expressionCode
) implements ReturnStatementNode {
}