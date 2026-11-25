// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.junit.jupiter.api.Test;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.ast.KeywordExpression.*;
import org.vernac.compiler.ast.SourceLocation;

import static org.junit.jupiter.api.Assertions.*;

class KeywordExpressionParserTest {
    private final KeywordExpressionParser parser = new KeywordExpressionParser();

    @Test
    void keepsSelectorPartsTogetherAndRecordsTheirPositions() {
        var move = assertInstanceOf(Message.class,
                parser.parse("move.vernac", "document moveFrom: inbox\n    to: archive"));
        assertEquals("moveFrom:to:", move.selector());
        assertEquals("document", assertInstanceOf(Name.class, move.receiver()).name());
        assertEquals(new SourceLocation("move.vernac", 2, 5), move.parts().get(1).location());
        assertEquals("archive", assertInstanceOf(Name.class, move.parts().get(1).argument()).name());
    }

    @Test
    void distinguishesUnaryChainsFromNestedKeywordMessages() {
        var emit = assertInstanceOf(Message.class, parser.parse("move.vernac",
                "events emit: (DocumentMoved document: document owner id destination: archive)"));
        var event = assertInstanceOf(Message.class, emit.parts().getFirst().argument());
        assertEquals("document:destination:", event.selector());
        var id = assertInstanceOf(UnaryMessage.class, event.parts().getFirst().argument());
        assertEquals("id", id.selector());
        assertEquals("owner", assertInstanceOf(UnaryMessage.class, id.receiver()).selector());
    }

    @Test
    void preservesArithmeticAndBooleanPrecedence() {
        var either = assertInstanceOf(Binary.class, parser.parse("rule.vernac",
                "quantity * price + shipping >= limit && !document isArchived || override"));
        assertEquals("||", either.operator());
        var both = assertInstanceOf(Binary.class, either.left());
        assertEquals("&&", both.operator());
        var comparison = assertInstanceOf(Binary.class, both.left());
        assertEquals(">=", comparison.operator());
        var sum = assertInstanceOf(Binary.class, comparison.left());
        assertEquals("+", sum.operator());
        assertEquals("*", assertInstanceOf(Binary.class, sum.left()).operator());
        assertEquals("isArchived", assertInstanceOf(UnaryMessage.class,
                assertInstanceOf(Prefix.class, both.right()).operand()).selector());
    }

    @Test
    void rejectsAnIncompleteKeywordArgumentAtTheSourcePosition() {
        var error = assertThrows(SemanticValidationException.class,
                () -> parser.parse("move.vernac", "document moveFrom: inbox\n    to:"));
        assertEquals(new SourceLocation("move.vernac", 2, 8),
                error.diagnostics().getFirst().location());
    }
}
