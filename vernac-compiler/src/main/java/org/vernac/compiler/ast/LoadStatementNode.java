// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

import java.util.Optional;

public record LoadStatementNode(
        SourceLocation location,
        String aggregateType,
        Optional<String> instanceName,
        Optional<String> repositoryName,
        Optional<String> idExpressionCode
) implements UseCaseStatementNode {
}