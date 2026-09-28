package org.vernac.compiler.ast;

import java.util.List;

public record EventNode(
        SourceLocation location,
        String name,
        List<AnnotationNode> annotations,
        List<FieldNode> fields
) implements TopLevelDefinition {}