package org.vernac.compiler.ast;

import java.util.List;
import java.util.Optional;

public record ValueObjectNode(
        SourceLocation location,
        String name,
        List<FieldNode> fields,
        List<ValidationRuleNode> validations,
        Optional<CollectionDefinitionNode> collection
) implements TopLevelDefinition {

    public ValueObjectNode(SourceLocation location, String name, List<FieldNode> fields, List<ValidationRuleNode> validations) {
        this(location, name, fields, validations, Optional.empty());
    }
}