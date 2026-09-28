package org.vernac.compiler.ast;

import java.util.List;

public record ServiceMethodNode(
        SourceLocation location,
        String httpMethod,
        String endpointPath,
        TypeNode returnType,
        String name,
        List<FieldNode> parameters,
        List<String> thrownExceptions,
        List<MappingStatementNode> mappings
) implements AstNode {}