package org.vernac.compiler.ast;

import java.util.List;

public record RepositoryMethodNode(
        SourceLocation location,
        TypeNode returnType,
        String name,
        List<FieldNode> parameters,
        boolean isCustom
) {
}