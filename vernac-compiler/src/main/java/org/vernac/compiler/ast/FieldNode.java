package org.vernac.compiler.ast;

import java.util.Optional;

public record FieldNode(
        SourceLocation location,
        TypeNode type,
        String name,
        boolean isId,
        Optional<String> defaultValue
) implements AstNode {}