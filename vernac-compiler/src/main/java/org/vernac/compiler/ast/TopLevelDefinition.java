// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

public sealed interface TopLevelDefinition extends AstNode permits
        IdDeclarationNode,
        ValueObjectNode,
        AggregateNode,
        EntityNode,
        EventNode,
        RepositoryNode,
        PortNode,
        UseCaseNode,
        DomainServiceNode,
        ListenerNode {
}