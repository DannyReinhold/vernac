package org.vernac.compiler.ast;

import java.util.Optional;

public record TupleElementNode(
        SourceLocation location,
        String expressionCode,
        Optional<String> alias
) {
}