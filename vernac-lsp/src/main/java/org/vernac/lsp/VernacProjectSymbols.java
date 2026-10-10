// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.lsp;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.parser.*;
import org.vernac.compiler.symbols.*;

import org.vernac.language.VernacNames;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/** A tolerant editor view. Visibility is still decided by the compiler's FileTypeScope. */
final class VernacProjectSymbols {
    private static final String CURSOR = "VernacCompletionTarget";
    private record Parsed(String text, VernacParser.CompilationUnitContext tree,
                          CompilationUnitNode unit) { }
    private final Map<Path, Parsed> files = new LinkedHashMap<>();
    private final List<TypeSymbol> declarations = new ArrayList<>();
    private final Map<TypeSymbol, Range> declarationRanges = new HashMap<>();
    private final Set<TypeIdentity> valueCollections = new HashSet<>();
    private final Set<String> namespaces = new TreeSet<>();
    private final ProjectSymbolIndex index;

    VernacProjectSymbols(Path root, Map<Path, String> texts) {
        texts.forEach((path, text) -> {
            var tree = parse(text);
            var header = tree.namespaceDeclaration();
            if (header == null || header.qualifiedName() == null) return;
            String namespace = header.qualifiedName().getText();
            if (!VernacNames.isNamespace(namespace)
                    || !root.resolve(namespace.replace('.', '/')).equals(path.getParent())) return;
            List<ImportNode> imports = new ArrayList<>();
            for (var item : tree.importDeclaration()) {
                // Ignore an unfinished import for editor visibility; diagnostics still report it.
                if (item.qualifiedName() != null && item.getText().endsWith(";")
                        && !item.getText().contains("<missing")) {
                    imports.add(new ImportNode(location(path, item.getStart()),
                            item.qualifiedName().getText(), item.getText().endsWith(".*;")));
                }
            }
            var unit = new CompilationUnitNode(location(path, header.getStart()), namespace, imports, List.of());
            files.put(path, new Parsed(text, tree, unit));
            namespaces.add(namespace);
            for (var top : tree.topLevelDeclaration()) {
                if (top.idDeclaration() != null) {
                    add(path, namespace, top.idDeclaration().name, TypeSymbol.Kind.ID);
                    collection(path, namespace, top.idDeclaration().name, top.idDeclaration().collectionDefinition(), true);
                } else if (top.valueDefinition() != null) {
                    var value = top.valueDefinition();
                    add(path, namespace, value.name, value.enumConstantList() == null
                            ? TypeSymbol.Kind.VALUE_OBJECT : TypeSymbol.Kind.ENUM);
                    collection(path, namespace, value.name, value.collectionDefinition(), true);
                } else if (top.entityDefinition() != null) {
                    var entity = top.entityDefinition();
                    add(path, namespace, entity.name, TypeSymbol.Kind.ENTITY);
                    collection(path, namespace, entity.name, entity.collectionDefinition(), false);
                } else if (top.aggregateDefinition() != null) {
                    add(path, namespace, top.aggregateDefinition().name, TypeSymbol.Kind.AGGREGATE);
                    collection(path, namespace, top.aggregateDefinition().name, top.aggregateDefinition().collectionDefinition(), false);
                } else if (top.eventDefinition() != null) {
                    add(path, namespace, top.eventDefinition().name, TypeSymbol.Kind.EVENT);
                } else if (top.portDefinition() != null) {
                    add(path, namespace, top.portDefinition().name, TypeSymbol.Kind.PORT);
                } else if (top.usecaseDefinition() != null) {
                    add(path, namespace, top.usecaseDefinition().name, TypeSymbol.Kind.USE_CASE);
                } else if (top.domainServiceDefinition() != null) {
                    add(path, namespace, top.domainServiceDefinition().name, TypeSymbol.Kind.DOMAIN_SERVICE);
                } else if (top.repositoryDefinition() != null) {
                    var repository = top.repositoryDefinition();
                    if (repository.name != null) add(path, namespace, repository.name, TypeSymbol.Kind.REPOSITORY);
                    else addDerived(path, namespace, repository.aggregateName, "Repository", TypeSymbol.Kind.REPOSITORY);
                } else if (top.listenerDefinition() != null) {
                    addDerived(path, namespace, top.listenerDefinition().eventName, "Listener", TypeSymbol.Kind.LISTENER);
                }
            }
        });
        index = new ProjectSymbolIndex(declarations);
    }

    private void collection(Path path, String namespace, ParserRuleContext owner,
                            VernacParser.CollectionDefinitionContext collection, boolean valueElements) {
        if (collection == null) return;
        if (valueElements && owner != null) {
            String name = collection.collectionName == null ? owner.getText() + "s" : collection.collectionName.getText();
            if (VernacNames.isIdentifier(name)) valueCollections.add(new TypeIdentity(namespace, name));
        }
        if (collection.collectionName != null) add(path, namespace, collection.collectionName, TypeSymbol.Kind.COLLECTION);
        else addDerived(path, namespace, owner, "s", TypeSymbol.Kind.COLLECTION);
    }

    private void add(Path path, String namespace, ParserRuleContext name, TypeSymbol.Kind kind) {
        addDerived(path, namespace, name, "", kind);
    }

    private void addDerived(Path path, String namespace, ParserRuleContext name, String suffix, TypeSymbol.Kind kind) {
        if (name == null || name.getStart().getTokenIndex() < 0) return;
        try {
            var symbol = new TypeSymbol(new TypeIdentity(namespace, name.getText() + suffix), kind, path,
                    location(path, name.getStart()));
            declarations.add(symbol);
            String text = files.get(path).text();
            declarationRanges.put(symbol, new Range(position(text, utf16(text, name.getStart().getStartIndex())),
                    position(text, utf16(text, name.getStop().getStopIndex() + 1))));
        } catch (IllegalArgumentException ignored) {
            // Recovery tokens and invalid declaration names are diagnosed by the compiler.
        }
    }

    /** Null means this is not a type or import context; retain other editor snippets. */
    List<CompletionItem> complete(Path path, Position position) {
        Parsed file = files.get(path);
        if (file == null) return List.of();
        int cursor = offset(file.text(), position);
        if (cursor < 0) return List.of();
        int start = cursor, end = cursor;
        while (start > 0 && nameCharacter(file.text().codePointBefore(start)))
            start -= Character.charCount(file.text().codePointBefore(start));
        while (end < file.text().length() && nameCharacter(file.text().codePointAt(end)))
            end += Character.charCount(file.text().codePointAt(end));
        String prefix = file.text().substring(start, cursor);
        if (excluded(file, cursor)) return List.of();
        var probe = parse(file.text().substring(0, start) + CURSOR + file.text().substring(end));
        List<VernacParser.QualifiedNameContext> contexts = new ArrayList<>();
        // Match by the injected token's code-point offset, not by text alone.
        int marker = file.text().codePointCount(0, start);
        var query=queryReference(probe,marker);
        if(query!=null) {
            List<CompletionItem> result=new ArrayList<>();
            Range queryRange=new Range(position(file.text(),start),position(file.text(),end));
            queryChoices(path,file,query).forEach((name,target)->{
                if(name.startsWith(prefix)) {
                    var item=new CompletionItem(name);
                    item.setKind(query.parameter()?CompletionItemKind.Variable:CompletionItemKind.Field);
                    item.setTextEdit(Either.forLeft(new TextEdit(queryRange,name)));
                    result.add(item);
                }
            });
            return result;
        }

        walk(probe, node -> {
            if (node instanceof VernacParser.QualifiedNameContext name && CURSOR.equals(name.getText())
                    && name.getStart().getStartIndex() == marker) contexts.add(name);
        });
        if (contexts.isEmpty()) return null;
        var context = contexts.getFirst();
        boolean importing = context.getParent() instanceof VernacParser.ImportDeclarationContext;
        if (!importing && !(context.getParent() instanceof VernacParser.TypeContext)) return null;
        boolean valueField = context.getParent().getParent() instanceof VernacParser.ParameterContext parameter
                && parameter.getParent().getParent() instanceof VernacParser.ValueDefinitionContext;
        var scope = new FileTypeScope(file.unit(), index, namespaces);
        Map<String, String> choices = new TreeMap<>();
        if (importing) {
            for (String namespace : namespaces) {
                if (!namespace.equals(file.unit().namespace())) choices.put(namespace + ".*", "Vernac namespace");
            }
            for (var symbol : declarations) {
                if (!symbol.identity().namespace().equals(file.unit().namespace()) && accepted(symbol))
                    choices.put(symbol.identity().qualifiedName(), symbol.kind().toString());
            }
        } else if (prefix.contains(".")) {
            for (var symbol : declarations) if (accepted(symbol) && (!valueField || valueType(symbol)))
                choices.put(symbol.identity().qualifiedName(), symbol.kind().toString());
        } else if (!scope.hasErrors() && index.problems().isEmpty()) {
            BuiltinTypes.names().forEach(name -> choices.put(name, "Vernac built-in type"));
            for (var symbol : declarations) {
                if (valueField && !valueType(symbol)) continue;
                String name = symbol.identity().name();
                var resolved = scope.resolve(name, file.unit().location()).type();
                if (resolved.orElse(null) instanceof ResolvedType.Declared declared
                        && declared.symbol().equals(symbol)) choices.put(name, symbol.identity().qualifiedName());
            }
        }
        Range range = new Range(position(file.text(), start), position(file.text(), end));
        List<CompletionItem> result = new ArrayList<>();
        choices.forEach((name, detail) -> {
            if (name.startsWith(prefix)) {
                var item = new CompletionItem(name);
                item.setKind(name.endsWith(".*") ? CompletionItemKind.Module : CompletionItemKind.Class);
                item.setDetail(detail);
                item.setTextEdit(Either.forLeft(new TextEdit(range, name)));
                result.add(item);
            }
        });
        return result;
    }

    List<Location> definition(Path path, Position position) {
        Parsed file = files.get(path);
        if (file == null) return List.of();
        int offset = offset(file.text(), position);
        if (offset < 0) return List.of();
        int point = file.text().codePointCount(0, offset);
        var query=queryReference(file.tree(),point);
        if(query!=null) {
            var target=queryChoices(path,file,query).get(query.name().getText());
            return target==null?List.of():List.of(target);
        }

        List<String> references = new ArrayList<>();
        walk(file.tree(), node -> {
            if (node instanceof VernacParser.QualifiedNameContext name
                    && name.getStart().getStartIndex() <= point && point <= name.getStop().getStopIndex()
                    && (name.getParent() instanceof VernacParser.TypeContext
                        || name.getParent() instanceof VernacParser.ImportDeclarationContext)) {
                if (!(name.getParent() instanceof VernacParser.ImportDeclarationContext declaration)
                        || !declaration.getText().endsWith(".*;")) references.add(name.getText());
            }
        });
        if (references.size() != 1) return List.of();
        String name = references.getFirst();
        TypeSymbol target = null;
        if (name.contains(".")) target = index.find(name).orElse(null);
        else {
            var scope = new FileTypeScope(file.unit(), index, namespaces);
            if (!scope.hasErrors() && index.problems().isEmpty() && scope.resolve(name, file.unit().location()).type().orElse(null)
                    instanceof ResolvedType.Declared declared) target = declared.symbol();
        }
        if (target == null) return List.of();
        return List.of(new Location(target.sourceFile().toUri().toString(), declarationRanges.get(target)));
    }

    private record QueryReference(ParserRuleContext name, VernacParser.RepositoryFindMethodContext method,
                                  VernacParser.RepositoryDefinitionContext repository, boolean parameter) { }
    private static QueryReference queryReference(ParseTree tree, int point) {
        List<QueryReference> found=new ArrayList<>();
        walk(tree,node->{
            ParserRuleContext name=null; boolean parameter=false;
            if(node instanceof VernacParser.RepositoryPredicateContext p) {
                if(contains(p.parameterName,point)) { name=p.parameterName; parameter=true; }
                else if(contains(p.field,point)) name=p.field;
            } else if(node instanceof VernacParser.RepositoryOrderContext o && contains(o.field,point)) name=o.field;
            if(name==null) return;
            ParseTree parent=node;
            while(parent!=null && !(parent instanceof VernacParser.RepositoryFindMethodContext)) parent=parent.getParent();
            if(!(parent instanceof VernacParser.RepositoryFindMethodContext method)) return;
            while(parent!=null && !(parent instanceof VernacParser.RepositoryDefinitionContext)) parent=parent.getParent();
            if(parent instanceof VernacParser.RepositoryDefinitionContext repo) found.add(new QueryReference(name,method,repo,parameter));
        });
        return found.size()==1?found.getFirst():null;
    }
    private static boolean contains(ParserRuleContext node,int point) {
        return node!=null && node.getStop()!=null && node.getStart().getStartIndex()<=point && point<=node.getStop().getStopIndex();
    }
    private Location queryLocation(Path path,Parsed file,ParserRuleContext node) {
        int start=utf16(file.text(),node.getStart().getStartIndex());
        int end=utf16(file.text(),node.getStop().getStopIndex()+1);
        return new Location(path.toUri().toString(),new Range(position(file.text(),start),position(file.text(),end)));
    }
    private Map<String,Location> queryChoices(Path path,Parsed file,QueryReference reference) {
        Map<String,Location> choices=new TreeMap<>();
        if(reference.parameter()) {
            if(reference.method().parameterList()!=null) for(var p:reference.method().parameterList().parameter())
                if(p.name!=null) choices.put(p.name.getText(),queryLocation(path,file,p.name));
            return choices;
        }
        var scope=new FileTypeScope(file.unit(),index,namespaces);
        var target=scope.resolve(reference.repository().aggregateName.getText(),file.unit().location()).type().orElse(null);
        if(!(target instanceof ResolvedType.Declared d) || d.symbol().kind()!=TypeSymbol.Kind.AGGREGATE) return choices;
        var source=files.get(d.symbol().sourceFile());
        if(source==null) return choices;
        for(var top:source.tree().topLevelDeclaration()) {
            var a=top.aggregateDefinition();
            if(a==null || !a.name.getText().equals(d.symbol().identity().name())) continue;
            Location root=queryLocation(d.symbol().sourceFile(),source,a.name);
            choices.put("id",queryLocation(d.symbol().sourceFile(),source,a.idReference()));
            choices.put("createdAt",root); choices.put("updatedAt",root);
            if(a.parameterList()!=null) for(var p:a.parameterList().parameter()) {
                String name=p.name==null?VernacNames.defaultMemberName(p.paramType.rawType.getText()):p.name.getText();
                choices.put(name,queryLocation(d.symbol().sourceFile(),source,p.name==null?p.paramType:p.name));
            }
        }
        return choices;
    }
    private boolean valueType(TypeSymbol symbol) {
        return switch (symbol.kind()) {
            case ID, VALUE_OBJECT, ENUM -> true;
            case COLLECTION -> valueCollections.contains(symbol.identity());
            default -> false;
        };
    }

    private static boolean excluded(Parsed file, int cursor) {
        int point = file.text().codePointCount(0, cursor);
        var lexer = new VernacLexer(CharStreams.fromString(file.text()));
        lexer.removeErrorListeners();
        for (var token : lexer.getAllTokens()) {
            if (token.getStartIndex() <= point && point <= token.getStopIndex()
                    && (token.getChannel() == Token.HIDDEN_CHANNEL
                        || token.getType() == VernacLexer.STRING_LITERAL)) return true;
            // A line comment also includes the insertion point immediately after its last character.
            if (token.getType() == VernacLexer.LINE_COMMENT && point == token.getStopIndex() + 1) return true;
        }
        boolean[] inside = {false};
        walk(file.tree(), node -> {
            if (node instanceof VernacParser.RawJavaBlockContext block
                    && block.getStart().getStartIndex() <= point
                    && point <= block.getStop().getStopIndex() + 1) inside[0] = true;
        });
        return inside[0];
    }

    private boolean accepted(TypeSymbol symbol) {
        return index.find(symbol.identity().qualifiedName()).filter(symbol::equals).isPresent();
    }

    private static VernacParser.CompilationUnitContext parse(String text) {
        var lexer = new VernacLexer(CharStreams.fromString(text));
        lexer.removeErrorListeners();
        var parser = new VernacParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        return parser.compilationUnit();
    }

    private static void walk(ParseTree node, Consumer<ParseTree> visitor) {
        visitor.accept(node);
        for (int i = 0; i < node.getChildCount(); i++) walk(node.getChild(i), visitor);
    }

    private static SourceLocation location(Path path, Token token) {
        return new SourceLocation(path.toString(), token.getLine(), token.getCharPositionInLine() + 1);
    }
    private static boolean nameCharacter(int c) { return VernacNames.isPart(c) || c == '.'; }
    private static int utf16(String text, int points) { return text.offsetByCodePoints(0, points); }
    private static int offset(String text, Position position) {
        if (position.getLine() < 0 || position.getCharacter() < 0) return -1;
        int start = 0;
        for (int line = 0; line < position.getLine(); line++) {
            start = text.indexOf('\n', start);
            if (start < 0) return -1;
            start++;
        }
        int end = text.indexOf('\n', start);
        if (end < 0) end = text.length();
        if (position.getCharacter() > end - start) return -1;
        int result = start + position.getCharacter();
        if (result > 0 && result < text.length() && Character.isHighSurrogate(text.charAt(result - 1))
                && Character.isLowSurrogate(text.charAt(result))) return -1;
        return result;
    }
    private static Position position(String text, int offset) {
        int line = 0, start = 0;
        for (int i = 0; i < offset; i++) if (text.charAt(i) == '\n') { line++; start = i + 1; }
        return new Position(line, offset - start);
    }
}
