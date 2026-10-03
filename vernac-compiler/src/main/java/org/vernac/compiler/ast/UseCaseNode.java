package org.vernac.compiler.ast;

import java.util.List;
import java.util.Optional;

public record UseCaseNode(
        SourceLocation location,
        String name,
        List<FieldNode> parameters,
        List<ValidationRuleNode> validations,
        List<UseDependencyNode> dependencies,
        List<UseCaseStatementNode> statements,
        Optional<ReturnStatementNode> returnStatement,
        Optional<String> customPackage
) implements TopLevelDefinition {
}