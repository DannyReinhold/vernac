package org.vernac.compiler.ast;

import java.util.List;
import java.util.Optional;

public record RepositoryNode(
        SourceLocation location,
        String name,
        String aggregateName,
        Optional<String> customPackage,
        List<RepositoryMethodNode> methods
) implements TopLevelDefinition {

    public List<RepositoryMethodNode> customMethods() {
        return methods.stream().filter(RepositoryMethodNode::isCustom).toList();
    }

    public List<RepositoryMethodNode> findMethods() {
        return methods.stream().filter(m -> !m.isCustom()).toList();
    }

    public boolean hasCustomMethods() {
        return methods.stream().anyMatch(RepositoryMethodNode::isCustom);
    }
}