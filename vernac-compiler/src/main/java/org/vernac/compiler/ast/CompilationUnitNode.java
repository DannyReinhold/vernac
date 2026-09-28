package org.vernac.compiler.ast;

import java.util.List;
import java.util.Optional;

public record CompilationUnitNode(
        SourceLocation location,
        Optional<String> packageName,
        List<ValueObjectNode> valueObjects
) implements AstNode {}