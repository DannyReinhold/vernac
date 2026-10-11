// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

import java.util.List;
import java.util.Optional;

public record ValueObjectNode(
        SourceLocation location,
        String name,
        List<FieldNode> fields,
        List<EnumConstantNode> enumConstants,
        List<ValidationRuleNode> validations,
        List<MethodNode> methods,
        Optional<CollectionDefinitionNode> collection,
        List<JavaImportNode> javaImports
) implements TopLevelDefinition {
    public ValueObjectNode(SourceLocation location, String name, List<FieldNode> fields,
                           List<EnumConstantNode> enumConstants, List<ValidationRuleNode> validations,
                           List<MethodNode> methods, Optional<CollectionDefinitionNode> collection) {
        this(location, name, fields, enumConstants, validations, methods, collection, List.of());
    }
    public boolean isEnum() {
        return !enumConstants.isEmpty();
    }
}

