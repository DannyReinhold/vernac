// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

public sealed interface AstNode permits
        CompilationUnitNode,
        ImportNode,
        TopLevelDefinition,
        CollectionDefinitionNode,
        IdReferenceNode,
        FieldNode,
        TypeNode,
        ValidationRuleNode,
        InvariantNode,
        MethodNode,
        SchemaNode,
        PortMethodNode,
        AdapterNode,
        RestConfigNode,
        RestErrorRuleNode,
        MappingBlockNode,
        MappingStatementNode,
        EnumConstantNode {

    SourceLocation location();
}