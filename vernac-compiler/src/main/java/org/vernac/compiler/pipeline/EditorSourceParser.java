// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTree;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.parser.VernacLexer;
import org.vernac.compiler.parser.VernacParser;

import java.util.*;

/** Editor-only recovery: publish complete declarations, never ANTLR's invented signatures. */
public final class EditorSourceParser {
    public record Analysis(
            CompilationUnitNode unit,
            VernacParser.CompilationUnitContext tree,
            List<VernacParser.TopLevelDeclarationContext> declarations) {
        public Analysis {
            declarations = List.copyOf(declarations);
        }
    }

    public Optional<Analysis> analyze(
            String name, String text, List<CompilerDiagnostic> diagnostics) {
        Set<ParserRuleContext> invalid = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Integer> lexicalErrors = new ArrayList<>();
        boolean[] invalidHeader = {false};
        var errors =
                new BaseErrorListener() {
                    @Override
                    public void syntaxError(
                            Recognizer<?, ?> recognizer,
                            Object offending,
                            int line,
                            int column,
                            String message,
                            RecognitionException exception) {
                        if (offending instanceof Token token
                                && token.getText() != null
                                && token.getText()
                                        .codePoints()
                                        .anyMatch(org.vernac.language.VernacNames::isForbidden))
                            message =
                                    org.vernac.language.VernacNames.invalidNameMessage(
                                            token.getText());
                        diagnostics.add(
                                CompilerDiagnostic.error(
                                        new SourceLocation(name, line, column + 1),
                                        "Syntax error: " + message));
                        if (recognizer instanceof Lexer lexer)
                            lexicalErrors.add(lexer.getCharIndex());
                        if (recognizer instanceof Parser parser) {
                            for (ParserRuleContext context = parser.getContext();
                                    context != null;
                                    context = context.getParent()) {
                                if (context instanceof VernacParser.TopLevelDeclarationContext) {
                                    invalid.add(context);
                                    break;
                                }
                                if (context instanceof VernacParser.NamespaceDeclarationContext
                                        || context
                                                instanceof VernacParser.ImportDeclarationContext) {
                                    invalidHeader[0] = true;
                                    break;
                                }
                            }
                        }
                    }
                };
        var lexer = new VernacLexer(CharStreams.fromString(text, name));
        lexer.removeErrorListeners();
        lexer.addErrorListener(errors);
        var tokens = new CommonTokenStream(lexer);
        tokens.fill();
        var parser = new VernacParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(errors);
        var tree = parser.compilationUnit();
        var namespace = tree.namespaceDeclaration();
        var imports = tree.importDeclaration();
        if (namespace == null || namespace.getStop() == null || invalidHeader[0])
            return Optional.empty();
        int headerEnd =
                imports.isEmpty()
                        ? namespace.getStop().getStopIndex()
                        : imports.getLast().getStop().getStopIndex();
        if (invalidHeader[0]
                || hasError(namespace)
                || imports.stream().anyMatch(EditorSourceParser::hasError)
                || lexicalErrors.stream().anyMatch(i -> i <= headerEnd)) return Optional.empty();
        var names = new VernacSourceParser();
        try {
            names.validateNames(name, namespace);
            for (var imported : imports) names.validateNames(name, imported);
        } catch (SemanticValidationException error) {
            diagnostics.addAll(error.diagnostics());
            return Optional.empty();
        }
        List<TopLevelDefinition> definitions = new ArrayList<>();
        List<TermNode> terms = new ArrayList<>();
        List<VernacParser.TopLevelDeclarationContext> accepted = new ArrayList<>();
        var visitor = new AstBuilderVisitor(name);
        for (var declaration : tree.topLevelDeclaration()) {
            if (invalid.contains(declaration)
                    || hasError(declaration)
                    || lexicalErrors.stream()
                            .anyMatch(
                                    i ->
                                            i >= declaration.getStart().getStartIndex()
                                                    && i <= declaration.getStop().getStopIndex()))
                continue;
            try {
                names.validateNames(name, declaration);
                if (declaration.termDefinition() != null)
                    terms.add(
                            org.vernac.compiler.vocabulary.Vocabulary.parse(
                                    name, declaration.termDefinition()));
                else definitions.add(visitor.toTopLevelDefinition(declaration));
                accepted.add(declaration);
            } catch (SemanticValidationException error) {
                diagnostics.addAll(error.diagnostics());
            }
        }
        var unit =
                new CompilationUnitNode(
                        location(name, namespace),
                        namespace.qualifiedName().getText(),
                        imports.stream()
                                .map(
                                        i ->
                                                new ImportNode(
                                                        location(name, i),
                                                        i.qualifiedName().getText(),
                                                        i.getText().endsWith(".*;")))
                                .toList(),
                        definitions,
                        terms);
        return Optional.of(new Analysis(unit, tree, accepted));
    }

    private static SourceLocation location(String name, ParserRuleContext context) {
        return new SourceLocation(
                name, context.getStart().getLine(), context.getStart().getCharPositionInLine() + 1);
    }

    private static boolean hasError(ParseTree tree) {
        if (tree instanceof ErrorNode) return true;
        if (tree instanceof ParserRuleContext rule && rule.exception != null) return true;
        for (int i = 0; i < tree.getChildCount(); i++) if (hasError(tree.getChild(i))) return true;
        return false;
    }
}
