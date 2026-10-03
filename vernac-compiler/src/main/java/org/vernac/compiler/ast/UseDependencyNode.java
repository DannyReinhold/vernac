package org.vernac.compiler.ast;

import java.util.Optional;

public record UseDependencyNode(
        SourceLocation location,
        String typeName,
        Optional<String> instanceName
) {
}