package org.vernac.compiler.ast;

import java.util.List;
import java.util.Optional;

public record PortMethodNode(
        String name,
        TypeNode returnType,
        List<FieldNode> parameters,
        List<String> thrownExceptions,
        AdapterNode adapter,
        Optional<MappingBlockNode> mapping,
        SourceLocation location
) implements AstNode {
}