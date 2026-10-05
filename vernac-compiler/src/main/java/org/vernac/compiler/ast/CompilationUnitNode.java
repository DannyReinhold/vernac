// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

import java.util.List;
import java.util.Optional;

public record CompilationUnitNode(
        SourceLocation location,
        Optional<String> packageName,
        List<String> imports,
        List<TopLevelDefinition> definitions
) implements AstNode {

    public List<ValueObjectNode> valueObjects() {
        return definitions.stream()
                .filter(ValueObjectNode.class::isInstance)
                .map(ValueObjectNode.class::cast)
                .toList();
    }

    public List<AggregateNode> aggregates() {
        return definitions.stream()
                .filter(AggregateNode.class::isInstance)
                .map(AggregateNode.class::cast)
                .toList();
    }

    public List<EntityNode> entities() {
        return definitions.stream()
                .filter(EntityNode.class::isInstance)
                .map(EntityNode.class::cast)
                .toList();
    }

    public List<EventNode> events() {
        return definitions.stream()
                .filter(EventNode.class::isInstance)
                .map(EventNode.class::cast)
                .toList();
    }

    public List<RepositoryNode> repositories() {
        return definitions.stream()
                .filter(RepositoryNode.class::isInstance)
                .map(RepositoryNode.class::cast)
                .toList();
    }

    public List<PortNode> ports() {
        return definitions.stream()
                .filter(PortNode.class::isInstance)
                .map(PortNode.class::cast)
                .toList();
    }

    public List<UseCaseNode> useCases() {
        return definitions.stream()
                .filter(d -> d instanceof UseCaseNode)
                .map(d -> (UseCaseNode) d)
                .toList();
    }

    public List<DomainServiceNode> domainServices() {
        return definitions.stream()
                .filter(d -> d instanceof DomainServiceNode)
                .map(d -> (DomainServiceNode) d)
                .toList();
    }

    public List<ListenerNode> listeners() {
        return definitions.stream()
                .filter(d -> d instanceof ListenerNode)
                .map(d -> (ListenerNode) d)
                .toList();
    }
}