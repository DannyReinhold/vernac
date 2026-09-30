package org.vernac.compiler.ast;

import java.util.List;

public record SchemaNode(
        String name,
        List<FieldNode> fields,
        SourceLocation location
) implements AstNode {
}