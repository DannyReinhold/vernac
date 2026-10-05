// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

import java.util.List;
import java.util.Optional;

public record DomainServiceNode(
        SourceLocation location,
        String name,
        List<FieldNode> parameters,
        Optional<TypeNode> returnType,
        List<ValidationRuleNode> validations,
        List<UseCaseStatementNode> statements,
        Optional<ReturnStatementNode> returnStatement,
        Optional<String> customPackage
) implements TopLevelDefinition {
}