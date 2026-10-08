// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

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
        Optional<String> customPackage,
        List<JavaImportNode> javaImports
) implements TopLevelDefinition {
    public EntityNode(SourceLocation location, String name, IdReferenceNode idDefinition,
                      List<FieldNode> fields, List<ValidationRuleNode> validations, List<MethodNode> methods,
                      Optional<CollectionDefinitionNode> collection, Optional<String> customPackage) {
        this(location, name, idDefinition, fields, validations, methods, collection, customPackage, List.of());
    }
}