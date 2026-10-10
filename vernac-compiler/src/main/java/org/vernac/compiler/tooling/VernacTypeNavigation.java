// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.tooling;

import org.antlr.v4.runtime.*;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.parser.*;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.symbols.*;

import java.nio.file.Path;
import java.util.*;

/** Maps reviewed generated Java type identities to live Vernac declaration ranges. */
public final class VernacTypeNavigation {
    public record Target(Path source, String name, int start, int end) { }
    public record Member(String name, List<String> parameterTypes) {
        public Member { parameterTypes = List.copyOf(parameterTypes); }
    }

    public Optional<Target> find(VernacProject project, Map<Path, String> texts, String javaName) {
        return find(project, texts, javaName, Optional.empty());
    }

    public Optional<Target> findMember(VernacProject project, Map<Path, String> texts, String javaName, Member member) {
        return find(project, texts, javaName, Optional.of(member));
    }

    private Optional<Target> find(VernacProject project, Map<Path, String> texts, String javaName, Optional<Member> member) {
        for (var source : project.sources()) {
            for (var symbol : project.symbols().inNamespace(source.unit().namespace())) {
                if (!symbol.sourceFile().equals(source.path()) || !reviewed(symbol.kind())) continue;
                String generated = JavaTypeNames.canonicalName(new ResolvedType.Declared(symbol));
                boolean access = (symbol.kind() == TypeSymbol.Kind.ENTITY || symbol.kind() == TypeSymbol.Kind.AGGREGATE)
                        && List.of("Read", "Write", "Access").stream().anyMatch(suffix -> (source.unit().namespace() + ".domain.access." + symbol.identity().name() + suffix).equals(javaName));
                boolean valueRead = symbol.kind() == TypeSymbol.Kind.VALUE_OBJECT && (source.unit().namespace() + ".domain.access." + symbol.identity().name() + "Read").equals(javaName)
                        && source.unit().valueObjects().stream().anyMatch(v -> v.name().equals(symbol.identity().name()) && !v.validations().isEmpty());
                if (!generated.equals(javaName) && !(member.isPresent() && (access || valueRead))) continue;
                String text = texts.get(source.path());
                return text == null ? Optional.empty() : locate(project, source, text, symbol, member);
            }
        }
        return Optional.empty();
    }

    private boolean reviewed(TypeSymbol.Kind kind) {
        return switch (kind) {
            case ID, VALUE_OBJECT, ENUM, COLLECTION, ENTITY, AGGREGATE, USE_CASE -> true;
            default -> false;
        };
    }

    private Optional<Target> locate(VernacProject project, VernacSourceFile source, String text,
                                    TypeSymbol symbol, Optional<Member> member) {
        var lexer = new VernacLexer(CharStreams.fromString(text));
        lexer.removeErrorListeners();
        var parser = new VernacParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        var tree = parser.compilationUnit();
        if (parser.getNumberOfSyntaxErrors() != 0 || tree.topLevelDeclaration().size() != source.unit().definitions().size())
            return Optional.empty();
        for (int i = 0; i < source.unit().definitions().size(); i++) {
            var definition = source.unit().definitions().get(i);
            var syntax = tree.topLevelDeclaration(i);
            ParserRuleContext name = null;
            VernacParser.CollectionDefinitionContext collection = null;
            if (syntax.idDeclaration() != null) {
                name = syntax.idDeclaration().name;
                collection = syntax.idDeclaration().collectionDefinition();
            } else if (syntax.valueDefinition() != null) {
                name = syntax.valueDefinition().name;
                collection = syntax.valueDefinition().collectionDefinition();
            }
            if (syntax.entityDefinition() != null) {
                name = syntax.entityDefinition().name;
                collection = syntax.entityDefinition().collectionDefinition();
            } else if (syntax.aggregateDefinition() != null) {
                name = syntax.aggregateDefinition().name;
                collection = syntax.aggregateDefinition().collectionDefinition();
            }
            if (syntax.usecaseDefinition() != null) name=syntax.usecaseDefinition().name;
            if (name == null) continue;
            if (symbol.kind() != TypeSymbol.Kind.COLLECTION && name.getText().equals(symbol.identity().name())) {
                if (member.isEmpty()) return target(source.path(), text, symbol.identity().name(), name.getStart(), name.getStop());
                var key = member.get();
                if (definition instanceof IdDeclarationNode && key.name().equals("value") && key.parameterTypes().isEmpty())
                    return target(source.path(), text, "value", name.getStart(), name.getStop());
                List<Target> candidates = new ArrayList<>();
                if (definition instanceof UseCaseNode u && key.name().equals("execute") && key.parameterTypes().size()==u.parameters().size())
                    return target(source.path(),text,"execute",name.getStart(),name.getStop());
                if (definition instanceof ValueObjectNode value) {
                    var valueSyntax = syntax.valueDefinition();
                    if (key.parameterTypes().isEmpty() && valueSyntax.parameterList() != null) {
                        for (int f = 0; f < value.fields().size(); f++) {
                            if (!value.fields().get(f).name().equals(key.name())) continue;
                            var field = valueSyntax.parameterList().parameter(f);
                            ParserRuleContext anchor = field.name == null ? field.paramType : field.name;
                            target(source.path(), text, key.name(), anchor.getStart(), anchor.getStop()).ifPresent(candidates::add);
                        }
                    }
                    candidates.addAll(behavior(project, source, text, value.methods(), valueSyntax.behaviorBlock(), key));
                }
                var mutable = MutableDomain.of(definition);
                if (mutable.isPresent()) {
                    var model = mutable.get();
                    var parameters = syntax.entityDefinition() != null ? syntax.entityDefinition().parameterList() : syntax.aggregateDefinition().parameterList();
                    var block = syntax.entityDefinition() != null ? syntax.entityDefinition().behaviorBlock() : syntax.aggregateDefinition().behaviorBlock();
                    if (key.parameterTypes().isEmpty() && key.name().equals("id")) {
                        var id = syntax.entityDefinition() != null ? syntax.entityDefinition().idReference() : syntax.aggregateDefinition().idReference();
                        target(source.path(), text, "id", id.getStart(), id.getStop()).ifPresent(candidates::add);
                    }
                    if (key.parameterTypes().isEmpty() && parameters != null) for (int f = 0; f < model.fields().size(); f++) {
                        if (!model.fields().get(f).name().equals(key.name())) continue;
                        var field = parameters.parameter(f);
                        var anchor = field.name == null ? field.paramType : field.name;
                        target(source.path(), text, key.name(), anchor.getStart(), anchor.getStop()).ifPresent(candidates::add);
                    }
                    candidates.addAll(behavior(project, source, text, model.methods(), block, key));
                }
                return unique(candidates);
            }
            var declared = CollectionDeclaration.of(definition);
            if (symbol.kind() == TypeSymbol.Kind.COLLECTION && collection != null && declared.isPresent()
                    && declared.get().name().equals(symbol.identity().name())) {
                if (member.isPresent()) return unique(behavior(project, source, text,
                        declared.get().definition().customMethods(), collection.behaviorBlock(), member.get()));
                if (collection.collectionName != null) return target(source.path(), text, declared.get().name(),
                        collection.collectionName.getStart(), collection.collectionName.getStop());
                // An implicit collection name has no name token: navigate to list/set itself.
                return target(source.path(), text, declared.get().name(), collection.kind, collection.kind);
            }
        }
        return Optional.empty();
    }

    private Optional<Target> unique(List<Target> candidates) {
        return candidates.size() == 1 ? Optional.of(candidates.getFirst()) : Optional.empty();
    }

    private List<Target> behavior(VernacProject project, VernacSourceFile source, String text,
                                  List<MethodNode> methods, VernacParser.BehaviorBlockContext syntax, Member member) {
        if (syntax == null || methods.size() != syntax.behaviorMethod().size()) return List.of();
        Set<String> namespaces = new HashSet<>();
        project.sources().forEach(s -> namespaces.add(s.unit().namespace()));
        var scope = new FileTypeScope(source.unit(), project.symbols(), namespaces);
        if (scope.hasErrors()) return List.of();
        List<Target> result = new ArrayList<>();
        for (int i = 0; i < methods.size(); i++) {
            var method = methods.get(i);
            if (!method.accessModifier().equals("public") || !method.name().equals(member.name())
                    || method.parameters().size() != member.parameterTypes().size()) continue;
            List<String> parameters = new ArrayList<>();
            for (var parameter : method.parameters()) {
                var resolved = scope.resolve(parameter.type().name(), parameter.location()).type();
                if (resolved.isEmpty()) break;
                try { parameters.add(JavaTypeNames.canonicalName(resolved.get())); }
                catch (IllegalArgumentException unsupported) { break; }
            }
            if (!parameters.equals(member.parameterTypes())) continue;
            var name = syntax.behaviorMethod(i).name;
            target(source.path(), text, member.name(), name.getStart(), name.getStop()).ifPresent(result::add);
        }
        return result;
    }

    private Optional<Target> target(Path source, String text, String name, Token first, Token last) {
        if (first.getStartIndex() < 0 || last.getStopIndex() < first.getStartIndex()) return Optional.empty();
        return Optional.of(new Target(source, name, text.offsetByCodePoints(0, first.getStartIndex()),
                text.offsetByCodePoints(0, last.getStopIndex() + 1)));
    }
}
