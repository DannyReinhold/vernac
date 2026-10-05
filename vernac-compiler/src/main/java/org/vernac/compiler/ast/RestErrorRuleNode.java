// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

import java.util.Optional;

public record RestErrorRuleNode(
        String statusCode,
        Optional<String> returnExpression,
        Optional<String> throwExceptionType,
        SourceLocation location
) implements AstNode {
}
