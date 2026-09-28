package org.vernac.compiler.ast;

import java.util.Map;
import java.util.Optional;

public record AnnotationNode(
        SourceLocation location,
        String name,
        Optional<String> singleValue,
        Map<String, String> attributes
) implements AstNode {
    public AnnotationNode(SourceLocation location, String name, Optional<String> singleValue) {
        this(location, name, singleValue, Map.of());
    }
}