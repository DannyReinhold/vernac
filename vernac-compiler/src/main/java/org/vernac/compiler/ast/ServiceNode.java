package org.vernac.compiler.ast;

import java.util.List;

public record ServiceNode(
        SourceLocation location,
        String name,
        List<AnnotationNode> annotations,
        List<ExternalSchemaNode> schemas,
        List<ServiceMethodNode> methods
) implements TopLevelDefinition {}