// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.ast;

public sealed interface NativeStatement {
    record Require(KeywordExpression predicate, String rejection) implements NativeStatement { }
    record Assign(SourceLocation location, String field, KeywordExpression value) implements NativeStatement { }
    record Return(KeywordExpression value) implements NativeStatement { }
}
