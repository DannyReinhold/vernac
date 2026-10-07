// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.antlr.v4.runtime.*;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.ast.AstBuilderVisitor;
import org.vernac.compiler.ast.CompilationUnitNode;
import org.vernac.compiler.ast.SourceLocation;
import org.vernac.compiler.parser.VernacLexer;
import org.vernac.compiler.parser.VernacParser;
import java.util.List;

/** Parses one named source without resolving imports or references. */
public final class VernacSourceParser {
    public CompilationUnitNode parse(String sourceName, String source) {
        var errors = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                    int line, int column, String message, RecognitionException cause) {
                throw new SemanticValidationException(List.of(CompilerDiagnostic.error(
                        new SourceLocation(sourceName, line, column + 1), "Syntax error: " + message)));
            }
        };
        var lexer = new VernacLexer(CharStreams.fromString(source, sourceName));
        lexer.removeErrorListeners();
        lexer.addErrorListener(errors);
        var parser = new VernacParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(errors);
        return new AstBuilderVisitor(sourceName).visitCompilationUnit(parser.compilationUnit());
    }
}
