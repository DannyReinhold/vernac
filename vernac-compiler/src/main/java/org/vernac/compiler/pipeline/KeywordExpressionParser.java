// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.vernac.compiler.ast.KeywordExpression;
import org.vernac.compiler.ast.KeywordExpression.*;
import org.vernac.compiler.ast.SourceLocation;
import org.vernac.compiler.parser.VernacBaseVisitor;
import org.vernac.compiler.parser.VernacParser.*;

import java.util.ArrayList;

/** Parses a complete expression with the compiler's standard source diagnostics. */
public final class KeywordExpressionParser {
    public KeywordExpression parse(String sourceName, String source) {
        var parser = new VernacSourceParser();
        var tree = parser.parser(sourceName, source).keywordExpressionInput();
        parser.validateNames(sourceName, tree);
        return build(sourceName, tree.keywordExpression());
    }

    public static KeywordExpression build(String sourceName, KeywordExpressionContext expression) {
        return new Builder(sourceName).visit(expression);
    }

    private static final class Builder extends VernacBaseVisitor<KeywordExpression> {
        private final String sourceName;

        private Builder(String sourceName) {
            this.sourceName = sourceName;
        }

        @Override
        public KeywordExpression visitKeywordExpression(KeywordExpressionContext ctx) {
            var receiver = visit(ctx.keywordDisjunction(0));
            if (ctx.keywordSelectorPart().isEmpty()) {
                return receiver;
            }
            var parts = new ArrayList<KeywordPart>();
            for (int index = 0; index < ctx.keywordSelectorPart().size(); index++) {
                var keyword = ctx.keywordSelectorPart(index);
                parts.add(new KeywordPart(location(keyword.start), keyword.getText(),
                        visit(ctx.keywordDisjunction(index + 1))));
            }
            return new Message(parts.getFirst().location(), receiver, parts);
        }

        @Override
        public KeywordExpression visitKeywordDisjunction(KeywordDisjunctionContext ctx) {
            return binary(ctx);
        }

        @Override
        public KeywordExpression visitKeywordConjunction(KeywordConjunctionContext ctx) {
            return binary(ctx);
        }

        @Override
        public KeywordExpression visitKeywordEquality(KeywordEqualityContext ctx) {
            return binary(ctx);
        }

        @Override
        public KeywordExpression visitKeywordComparison(KeywordComparisonContext ctx) {
            return binary(ctx);
        }

        @Override
        public KeywordExpression visitKeywordSum(KeywordSumContext ctx) {
            return binary(ctx);
        }

        @Override
        public KeywordExpression visitKeywordProduct(KeywordProductContext ctx) {
            return binary(ctx);
        }

        private KeywordExpression binary(ParserRuleContext ctx) {
            var left = visit(ctx.getChild(0));
            for (int index = 1; index < ctx.getChildCount(); index += 2) {
                var operator = ((TerminalNode) ctx.getChild(index)).getSymbol();
                left = new Binary(location(operator), operator.getText(), left, visit(ctx.getChild(index + 1)));
            }
            return left;
        }

        @Override
        public KeywordExpression visitKeywordPrefix(KeywordPrefixContext ctx) {
            if (ctx.unaryMessage() != null) {
                return visit(ctx.unaryMessage());
            }
            return new Prefix(location(ctx.start), ctx.start.getText(), visit(ctx.keywordPrefix()));
        }

        @Override
        public KeywordExpression visitUnaryMessage(UnaryMessageContext ctx) {
            var receiver = visit(ctx.keywordPrimary());
            for (var message : ctx.keywordSelectorPart()) {
                receiver = new UnaryMessage(location(message.start), receiver, message.getText());
            }
            return receiver;
        }

        @Override
        public KeywordExpression visitKeywordPrimary(KeywordPrimaryContext ctx) {
            if (ctx.blockBinding != null) {
                var binding = new Name(location(ctx.blockBinding.start), ctx.blockBinding.getText());
                return new KeywordExpression.Block(location(ctx.start), binding, visit(ctx.blockBody));
            }
            if (ctx.subject != null) {
                var binding = new Name(location(ctx.binding.start), ctx.binding.getText());
                return new Match(location(ctx.start), visit(ctx.subject), binding,
                        visit(ctx.onPresent), visit(ctx.onAbsent));
            }
            if (ctx.variableName() != null) {
                return new Name(location(ctx.start), ctx.getText());
            }
            if (!ctx.keywordExpression().isEmpty()) {
                return visit(ctx.keywordExpression(0));
            }
            var kind = ctx.STRING_LITERAL() != null ? Literal.Kind.STRING
                    : ctx.INT_LITERAL() != null ? Literal.Kind.INTEGER
                    : ctx.DECIMAL_LITERAL() != null ? Literal.Kind.DECIMAL : Literal.Kind.BOOLEAN;
            return new Literal(location(ctx.start), kind, ctx.getText());
        }

        private SourceLocation location(Token token) {
            return new SourceLocation(sourceName, token.getLine(), token.getCharPositionInLine() + 1);
        }
    }
}
