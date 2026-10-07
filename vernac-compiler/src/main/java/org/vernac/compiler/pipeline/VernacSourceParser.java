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
import org.antlr.v4.runtime.tree.ParseTree;
import org.vernac.language.VernacNames;

/** Parses one named source without resolving imports or references. */
public final class VernacSourceParser {
    public CompilationUnitNode parse(String sourceName, String source) {
        var errors = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                    int line, int column, String message, RecognitionException cause) {
                if (offendingSymbol instanceof Token token && token.getText() != null
                        && token.getText().codePoints().anyMatch(VernacNames::isForbidden)) {
                    message = VernacNames.invalidNameMessage(token.getText());
                }
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
        var tree = parser.compilationUnit();
        validateNames(sourceName, tree);
        return new AstBuilderVisitor(sourceName).visitCompilationUnit(tree);
    }

    private void validateNames(String sourceName, ParseTree tree) {
        if (tree instanceof VernacParser.RawJavaBlockContext
                || tree instanceof VernacParser.RawJavaStatementContext) return;
        if (tree instanceof VernacParser.TypeNameContext
                || tree instanceof VernacParser.VariableNameContext
                || tree instanceof VernacParser.MethodNameContext
                || tree instanceof VernacParser.QualifiedNameSegmentContext) {
            String name = tree.getText();
            // Primitive type keywords are valid in qualifiedNameSegment type contexts.
            // Keyword restrictions are checked by namespace/type/member semantic validation.
            if (name.codePoints().anyMatch(point -> !VernacNames.isPart(point))) {
                Token token = ((ParserRuleContext) tree).getStart();
                throw new SemanticValidationException(List.of(CompilerDiagnostic.error(
                        new SourceLocation(sourceName, token.getLine(), token.getCharPositionInLine() + 1),
                        VernacNames.invalidNameMessage(name))));
            }
        }
        // Enum names are tokens, not typeName/variableName rule contexts.
        if (tree instanceof VernacParser.EnumConstantContext constant
                && !VernacNames.isIdentifier(constant.name.getText())) {
            throw new SemanticValidationException(List.of(CompilerDiagnostic.error(
                    new SourceLocation(sourceName, constant.name.getLine(), constant.name.getCharPositionInLine() + 1),
                    VernacNames.invalidNameMessage(constant.name.getText()))));
        }
        for (int i = 0; i < tree.getChildCount(); i++) validateNames(sourceName, tree.getChild(i));
    }
}
