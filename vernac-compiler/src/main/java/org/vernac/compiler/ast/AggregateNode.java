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
        List<JavaImportNode> javaImports,
        Classification classification
) implements TopLevelDefinition {
    public enum Classification { UNSPECIFIED, GLOBAL, TENANT }
    public boolean global() { return classification == Classification.GLOBAL; }
    public boolean tenant() { return classification == Classification.TENANT; }
    public AggregateNode(SourceLocation location, String name, IdReferenceNode idDefinition,
            List<FieldNode> fields, List<ValidationRuleNode> validations, List<MethodNode> methods,
            List<CollectionDefinitionNode> collections, List<JavaImportNode> javaImports) {
        this(location, name, idDefinition, fields, validations, methods, collections, javaImports, Classification.UNSPECIFIED);
    }
    public AggregateNode(SourceLocation location, String name, IdReferenceNode idDefinition,
            List<FieldNode> fields, List<ValidationRuleNode> validations, List<MethodNode> methods,
            List<CollectionDefinitionNode> collections, List<JavaImportNode> javaImports, boolean global) {
        this(location, name, idDefinition, fields, validations, methods, collections, javaImports,
                global ? Classification.GLOBAL : Classification.UNSPECIFIED);
    }
    public AggregateNode { java.util.Objects.requireNonNull(classification); collections = List.copyOf(collections); }
}
