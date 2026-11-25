// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

import java.util.Optional;

public record ValidationRuleNode(
        SourceLocation location,
        String condition,
        String message,
        Optional<NativeRule> nativeRule
) implements AstNode {
    public ValidationRuleNode(SourceLocation location, String condition, String message) {
        this(location, condition, message, Optional.empty());
    }

    public record NativeRule(KeywordExpression predicate, String rejection) { }
}
