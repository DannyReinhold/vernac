package org.vernac.compiler.ast;

import java.util.Optional;

public record IdDeclarationNode(
        SourceLocation location,
        String name,
        Optional<String> customPackage
) implements TopLevelDefinition {
}