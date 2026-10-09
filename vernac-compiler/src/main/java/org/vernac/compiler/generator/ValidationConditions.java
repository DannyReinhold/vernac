// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.generator;

import org.antlr.v4.runtime.*;
import org.vernac.compiler.parser.VernacLexer;

/** A deliberately conservative readability rule, not a boolean optimizer. */
public final class ValidationConditions {
    private ValidationConditions() { }
    public static String failure(String condition) {
        var lexer = new VernacLexer(CharStreams.fromString(condition));
        lexer.removeErrorListeners();
        boolean[] lexicalError = {false};
        lexer.addErrorListener(new BaseErrorListener() {
            @Override public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                    int line, int position, String message, RecognitionException exception) {
                lexicalError[0] = true;
            }
        });
        var tokens = lexer.getAllTokens().stream().filter(t -> t.getChannel() == Token.DEFAULT_CHANNEL).toList();
        if (!lexicalError[0] && !tokens.isEmpty() && tokens.getFirst().getText().equals("!")) {
            int depth = 0;
            boolean outerNegation = true;
            for (int i = 1; i < tokens.size(); i++) {
                String token = tokens.get(i).getText();
                if (token.equals("(") || token.equals("[")) depth++;
                else if (token.equals(")") || token.equals("]")) depth--;
                else if (depth == 0 && java.util.Set.of("&&", "||", "==", "!=", "<", ">", "<=", ">=", "+", "-", "*", "/", "?", ":", "&", "|", "^", "%", "instanceof").contains(token))
                    outerNegation = false;
            }
            if (outerNegation && depth == 0) {
                int start = condition.offsetByCodePoints(0, tokens.getFirst().getStartIndex());
                int end = condition.offsetByCodePoints(0, tokens.getFirst().getStopIndex() + 1);
                return (condition.substring(0, start) + condition.substring(end)).strip();
            }
        }
        return "!(" + condition + ")";
    }
}
