// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.ast;

import java.util.List;
import java.util.stream.Collectors;

/** Source syntax before name, type and effect resolution. */
public sealed interface KeywordExpression extends AstNode {
    record Name(SourceLocation location, String name) implements KeywordExpression { }

    record Literal(SourceLocation location, Kind kind, String spelling) implements KeywordExpression {
        public enum Kind { STRING, INTEGER, DECIMAL, BOOLEAN }
    }

    record Prefix(SourceLocation location, String operator, KeywordExpression operand)
            implements KeywordExpression { }

    record Binary(SourceLocation location, String operator, KeywordExpression left, KeywordExpression right)
            implements KeywordExpression { }

    record UnaryMessage(SourceLocation location, KeywordExpression receiver, String selector)
            implements KeywordExpression { }

    record Match(SourceLocation location, KeywordExpression subject, Name binding,
                 KeywordExpression onPresent, KeywordExpression onAbsent) implements KeywordExpression { }

    record Block(SourceLocation location, Name binding, KeywordExpression body) implements KeywordExpression { }

    record KeywordPart(SourceLocation location, String name, KeywordExpression argument) { }

    record Message(SourceLocation location, KeywordExpression receiver, List<KeywordPart> parts)
            implements KeywordExpression {
        public Message {
            parts = List.copyOf(parts);
        }

        public String selector() {
            return parts.stream().map(part -> part.name() + ":").collect(Collectors.joining());
        }
    }
}
