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

    public List<ServiceNode> services() {
        return definitions.stream()
                .filter(ServiceNode.class::isInstance)
                .map(ServiceNode.class::cast)
                .toList();
    }

    public List<ExternalSchemaNode> externalSchemas() {
        return definitions.stream()
                .filter(ExternalSchemaNode.class::isInstance)
                .map(ExternalSchemaNode.class::cast)
                .toList();
    }
}