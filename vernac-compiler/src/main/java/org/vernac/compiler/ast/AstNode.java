// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

public sealed interface AstNode permits
        CompilationUnitNode, TermNode,
        ImportNode, JavaImportNode,
        TopLevelDefinition,
        CollectionDefinitionNode,
        IdReferenceNode,
        FieldNode,
        TypeNode,
        ValidationRuleNode,
        InvariantNode,
        MethodNode,
        PortMethodNode,
        SchemaNode, SchemaFieldNode, MappingNode, MappingAssignmentNode,
        EnumConstantNode {

    SourceLocation location();
}