// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class VernacDebuggerHostLexerTest {
    @Test
    void tokensPreserveEachRequestedLineInsteadOfCollapsingEveryContextToFileStart() {
        String text = "namespace example;\r\nvalue Ä(String);\n// 🧭\rnext";
        var lexer = new VernacParserDefinition().createLexer(null);
        lexer.start(text, 0, text.length(), 0);
        List<String> tokens = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        while (lexer.getTokenType() != null) {
            starts.add(lexer.getTokenStart());
            tokens.add(text.substring(lexer.getTokenStart(), lexer.getTokenEnd()));
            lexer.advance();
        }
        assertEquals(
                List.of("namespace example;\r\n", "value Ä(String);\n", "// 🧭\r", "next"), tokens);
        assertEquals(text, String.join("", tokens));
        assertEquals(text.indexOf("value"), starts.get(1));
        assertEquals(text.indexOf("next"), starts.getLast());
    }

    @Test
    void partialAndEmptyLexingRetainExactUtf16Offsets() {
        String text = "🧭 first\nsecond\nthird";
        int from = text.indexOf("second"), end = text.indexOf("third");
        var lexer = new VernacParserDefinition().createLexer(null);
        lexer.start(text, from, end, 0);
        assertEquals(from, lexer.getTokenStart());
        assertEquals(end, lexer.getTokenEnd());
        lexer.advance();
        assertNull(lexer.getTokenType());
        assertEquals(end, lexer.getTokenStart());
        lexer.start("", 0, 0, 0);
        assertNull(lexer.getTokenType());
        assertEquals(0, lexer.getTokenEnd());
    }
}
