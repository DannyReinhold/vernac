// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

import java.util.List;

public record ValueObjectNode(
        SourceLocation location,
        String name,
        List<FieldNode> fields,
        List<EnumConstantNode> enumConstants,
        List<ValidationRuleNode> validations,
        List<MethodNode> methods,
        List<CollectionDefinitionNode> collections,
        List<JavaImportNode> javaImports
) implements TopLevelDefinition {
    public ValueObjectNode { collections = List.copyOf(collections); }
    public ValueObjectNode(SourceLocation location, String name, List<FieldNode> fields,
                           List<EnumConstantNode> enumConstants, List<ValidationRuleNode> validations,
                           List<MethodNode> methods, List<CollectionDefinitionNode> collections) {
        this(location, name, fields, enumConstants, validations, methods, collections, List.of());
    }
    public boolean isEnum() {
        return !enumConstants.isEmpty();
    }
}

