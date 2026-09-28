package org.vernac.compiler.ast;

import java.util.List;

public record MethodNode(
        SourceLocation location,
        String accessModifier,
        TypeNode returnType,
        String name,
        List<FieldNode> parameters,
        String bodyCode
) implements AstNode {}