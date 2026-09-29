package org.vernac.compiler.ast;

import java.util.List;
import java.util.Optional;

public record EntityNode(
        SourceLocation location,
        String name,
        IdDefinitionNode idDefinition,
        List<FieldNode> fields,
        List<ValidationRuleNode> validations,
        List<MethodNode> methods,
        Optional<String> customPackage
) implements TopLevelDefinition {
}