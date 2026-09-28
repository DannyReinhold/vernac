package org.vernac.compiler.ast;

import java.util.List;

public record EntityNode(
        SourceLocation location,
        String name,
        IdDefinitionNode idDefinition,
        List<FieldNode> fields,
        List<ValidationRuleNode> validations,
        List<MethodNode> methods
) implements TopLevelDefinition {
}