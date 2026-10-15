// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.tooling;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.vernac.compiler.parser.*;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.symbols.*;

import java.nio.file.Path;
import java.util.*;

/** Exact native-editor type references. Visibility and ambiguity remain FileTypeScope decisions. */
public final class VernacTypeReferences {
    public record Reference(
            int start, int end, String identity, VernacTypeNavigation.Target target) {}

    public List<Reference> build(VernacProject project, Path path, Map<Path, String> texts) {
        var source = project.sources().stream().filter(s -> s.path().equals(path)).findFirst();
        String text = texts.get(path);
        if (source.isEmpty() || text == null) return List.of();
        var lexer = new VernacLexer(CharStreams.fromString(text));
        lexer.removeErrorListeners();
        var parser = new VernacParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        var tree = parser.compilationUnit();
        if (parser.getNumberOfSyntaxErrors() != 0) return List.of();
        var namespaces = new HashSet<String>();
        project.sources().forEach(s -> namespaces.add(s.unit().namespace()));
        var scope = new FileTypeScope(source.get().unit(), project.symbols(), namespaces);
        if (scope.hasErrors()) return List.of();
        List<Reference> result = new ArrayList<>();
        Map<String, Optional<VernacTypeNavigation.Target>> targets = new HashMap<>();
        var terms =
                tree.topLevelDeclaration().stream()
                        .map(VernacParser.TopLevelDeclarationContext::termDefinition)
                        .filter(Objects::nonNull)
                        .toList();
        Map<ParseTree, String> termNames = new IdentityHashMap<>();
        if (terms.size() == source.get().unit().terms().size())
            for (int i = 0; i < terms.size(); i++)
                termNames.put(terms.get(i), source.get().unit().terms().get(i).name());
        walk(
                tree,
                node -> {
                    ParserRuleContext name = null, anchor = null;
                    String qualifiedTerm = null;
                    if (node instanceof VernacParser.TermDefinitionContext term) {
                        String label = termNames.get(term);
                        if (label == null
                                || label.contains(".")
                                || label.codePoints().anyMatch(Character::isWhitespace)) return;
                        qualifiedTerm = source.get().unit().namespace() + "." + label;
                        name = term;
                        anchor = term.name != null ? term.name : term;
                    }
                    if (node instanceof VernacParser.TypeContext type) {
                        name = type.rawType;
                        anchor = type.rawType.qualifiedNameSegment().getLast();
                    } else if (node instanceof VernacParser.ImportDeclarationContext declaration
                            && declaration.getText().endsWith(".*;")) {
                        return; // A namespace import is not a reference to a type declaration.
                    } else if (node instanceof VernacParser.ImportDeclarationContext declaration) {
                        name = declaration.qualifiedName();
                        anchor = declaration.qualifiedName().qualifiedNameSegment().getLast();
                    } else if (node
                            instanceof VernacParser.RepositoryDefinitionContext repository) {
                        name = repository.aggregateName;
                        anchor = name;
                    }
                    if (name == null
                            || anchor == null
                            || name.getStart() == null
                            || anchor.getStop() == null) return;
                    var resolved =
                            scope.resolve(
                                            qualifiedTerm != null ? qualifiedTerm : name.getText(),
                                            source.get().unit().location())
                                    .type();
                    if (!(resolved.orElse(null) instanceof ResolvedType.Declared declared)) return;
                    var symbol = declared.symbol();
                    String identity = symbol.identity().qualifiedName();
                    var target =
                            targets.computeIfAbsent(
                                    identity,
                                    ignored ->
                                            new VernacTypeNavigation()
                                                    .findSymbol(project, texts, symbol));
                    int a = anchor.getStart().getStartIndex(),
                            b = anchor.getStop().getStopIndex() + 1;
                    if (node instanceof VernacParser.TermDefinitionContext term
                            && term.label != null) {
                        a = term.label.getStartIndex();
                        b = term.label.getStopIndex() + 1;
                    }
                    if (a < 0 || b < a) return;
                    if (target.isPresent())
                        result.add(
                                new Reference(
                                        text.offsetByCodePoints(0, a),
                                        text.offsetByCodePoints(0, b),
                                        identity,
                                        target.get()));
                });
        return result.stream()
                .distinct()
                .sorted(Comparator.comparingInt(Reference::start))
                .toList();
    }

    /** Java class references in DSL syntax; raw Java remains owned by Java injection. */
    public record JavaReference(int start, int end, String javaName) {}

    public List<JavaReference> javaReferences(String text) {
        var lexer = new VernacLexer(CharStreams.fromString(text));
        lexer.removeErrorListeners();
        var parser = new VernacParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        var tree = parser.compilationUnit();
        if (parser.getNumberOfSyntaxErrors() != 0) return List.of();
        List<JavaReference> result = new ArrayList<>();
        walk(
                tree,
                node -> {
                    VernacParser.QualifiedNameContext name = null;
                    String javaName = null;
                    if (node instanceof VernacParser.TypeContext type) {
                        var builtin = BuiltinTypes.find(type.rawType.getText());
                        if (builtin.isEmpty() || builtin.get().isPrimitive()) return;
                        name = type.rawType;
                        javaName = builtin.get().getCanonicalName();
                    } else {
                        name =
                                switch (node) {
                                    case VernacParser.BehaviorMethodContext method ->
                                            method.implementation;
                                    case VernacParser.ServiceMethodContext method ->
                                            method.implementation;
                                    case VernacParser.UsecaseBehaviorMemberContext member ->
                                            member.implementation;
                                    case VernacParser.MappingDefinitionContext mapping ->
                                            mapping.implementation;
                                    case VernacParser.InboundErrorPolicyContext errors ->
                                            errors.implementation;
                                    case VernacParser.RestOperationContext operation ->
                                            operation.errorImplementation;
                                    default -> null;
                                };
                        if (name != null) javaName = name.getText();
                    }
                    if (name == null) return;
                    var anchor = name.qualifiedNameSegment().getLast();
                    int start = anchor.getStart().getStartIndex();
                    int end = anchor.getStop().getStopIndex() + 1;
                    if (start >= 0 && end >= start)
                        result.add(
                                new JavaReference(
                                        text.offsetByCodePoints(0, start),
                                        text.offsetByCodePoints(0, end),
                                        javaName));
                });
        return result.stream()
                .distinct()
                .sorted(Comparator.comparingInt(JavaReference::start))
                .toList();
    }

    private static void walk(ParseTree tree, java.util.function.Consumer<ParseTree> visit) {
        visit.accept(tree);
        for (int i = 0; i < tree.getChildCount(); i++) walk(tree.getChild(i), visit);
    }
}
