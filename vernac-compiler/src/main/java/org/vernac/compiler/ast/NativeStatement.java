// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.ast;

import java.util.List;

public sealed interface NativeStatement {
    default SourceLocation location() {
        return switch (this) {
            case Let let -> let.name().location();
            case Require rule -> rule.predicate().location();
            case Assign assignment -> assignment.location();
            case Send send -> send.expression().location();
            case Return result -> result.value().location();
            case Branch branch -> branch.location();
        };
    }

    record Let(KeywordExpression.Name name, KeywordExpression value) implements NativeStatement { }
    record Require(KeywordExpression predicate, String rejection) implements NativeStatement { }
    record Assign(SourceLocation location, String field, KeywordExpression value) implements NativeStatement { }
    record Send(KeywordExpression expression) implements NativeStatement { }
    record Return(KeywordExpression value) implements NativeStatement { }
    record Branch(SourceLocation location, KeywordExpression condition, List<NativeStatement> onTrue,
                  List<NativeStatement> onFalse) implements NativeStatement {
        public Branch {
            onTrue = List.copyOf(onTrue);
            onFalse = List.copyOf(onFalse);
        }
    }
}
