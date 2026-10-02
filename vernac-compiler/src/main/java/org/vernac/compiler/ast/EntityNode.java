package org.vernac.compiler.ast;

import java.util.List;
import java.util.Optional;

public record EntityNode(
        SourceLocation location,
        String name,
        IdReferenceNode idDefinition,
        List<FieldNode> fields,
        List<ValidationRuleNode> validations,
        List<MethodNode> methods,
        Optional<CollectionDefinitionNode> collection,
        Optional<String> customPackage
) implements TopLevelDefinition {
}