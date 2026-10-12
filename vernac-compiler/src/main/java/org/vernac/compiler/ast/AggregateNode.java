// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

import java.util.List;

public record AggregateNode(
        SourceLocation location,
        String name,
        IdReferenceNode idDefinition,
        List<FieldNode> fields,
        List<ValidationRuleNode> validations,
        List<MethodNode> methods,
        List<CollectionDefinitionNode> collections,
        List<JavaImportNode> javaImports
) implements TopLevelDefinition {
    public AggregateNode { collections = List.copyOf(collections); }
}
