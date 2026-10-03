package org.vernac.compiler.ast;

import java.util.Optional;

public record SaveStatementNode(
        SourceLocation location,
        String instanceName,
        Optional<String> repositoryName
) implements UseCaseStatementNode {
}