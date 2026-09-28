package org.vernac.compiler.ast;

import java.util.List;

public record ValueObjectNode(
        SourceLocation location,
        String name,
        List<FieldNode> fields
) implements AstNode {}