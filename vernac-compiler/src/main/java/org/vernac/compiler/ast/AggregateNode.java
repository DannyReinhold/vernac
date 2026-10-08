// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

import java.util.List;
import java.util.Optional;

public record AggregateNode(
        SourceLocation location,
        String name,
        IdReferenceNode idDefinition,
        List<FieldNode> fields,
        List<ValidationRuleNode> validations,
        List<MethodNode> methods,
        Optional<String> customPackage,
        Optional<CollectionDefinitionNode> collection
) implements TopLevelDefinition {
    public AggregateNode(SourceLocation location, String name, IdReferenceNode idDefinition,
                         List<FieldNode> fields, List<ValidationRuleNode> validations,
                         List<MethodNode> methods, Optional<String> customPackage) {
        this(location, name, idDefinition, fields, validations, methods, customPackage, Optional.empty());
    }
}