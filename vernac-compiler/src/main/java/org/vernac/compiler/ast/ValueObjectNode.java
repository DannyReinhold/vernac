package org.vernac.compiler.ast;

import java.util.List;
import java.util.Optional;

public record ValueObjectNode(
        SourceLocation location,
        String name,
        List<FieldNode> fields,
        List<ValidationRuleNode> validations,
        List<MethodNode> methods,
        Optional<CollectionDefinitionNode> collection
) implements TopLevelDefinition {
}