package org.vernac.compiler.ast;

import java.util.Map;

public record ExternalSchemaNode(
        SourceLocation location,
        String name,
        Map<String, TypeNode> fields
) implements AstNode {}