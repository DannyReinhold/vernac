package org.vernac.compiler.ast;

import java.util.List;

public record EntityNode(
        SourceLocation location,
        String name,
        List<FieldNode> fields,
        List<MethodNode> methods
) implements AstNode {}