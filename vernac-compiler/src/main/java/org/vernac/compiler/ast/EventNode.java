package org.vernac.compiler.ast;

import org.vernac.runtime.DispatchMode;

import java.util.List;
import java.util.Optional;

public record EventNode(
        SourceLocation location,
        String name,
        DispatchMode dispatchMode,
        Optional<String> customPackage,
        List<FieldNode> fields
) implements TopLevelDefinition {
}