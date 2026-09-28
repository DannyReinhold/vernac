package org.vernac.compiler.ast;

import java.util.List;

public record ValidationRuleNode(
        SourceLocation location,
        String condition,
        String message
) implements AstNode {}
