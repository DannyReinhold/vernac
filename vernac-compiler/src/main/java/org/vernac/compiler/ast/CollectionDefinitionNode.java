package org.vernac.compiler.ast;

import java.util.List;
import java.util.Optional;

public record CollectionDefinitionNode(
        SourceLocation location,
        Optional<String> customName,
        List<MethodNode> customMethods,
        Optional<String> customPackage
) implements AstNode {
}