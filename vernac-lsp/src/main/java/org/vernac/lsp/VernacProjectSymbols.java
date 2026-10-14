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
    private final Path root;
    private org.vernac.compiler.tooling.MappingEditorModel mappingModel;
    private boolean mappingModelAttempted;
    private final Map<Path, Parsed> files = new LinkedHashMap<>();
    private final List<TypeSymbol> declarations = new ArrayList<>();
    private final Map<TypeSymbol, Range> declarationRanges = new HashMap<>();
    private final Set<TypeIdentity> valueCollections = new HashSet<>();
    private final Set<String> namespaces = new TreeSet<>();
    private final ProjectSymbolIndex index;
    private record TermView(org.vernac.compiler.vocabulary.Vocabulary.Entry entry,Location location,Range nameRange) { }
    private final Map<org.vernac.compiler.vocabulary.Vocabulary.Key,TermView> terms=new LinkedHashMap<>();
    private Map<String,Location> dependencyLocations=Map.of();
    void dependencyLocations(Map<String,Location> locations) {dependencyLocations=Map.copyOf(locations);}
    private Location location(TypeSymbol symbol) {
        return dependencyLocations.getOrDefault(symbol.identity().qualifiedName(),new Location(symbol.sourceFile().toUri().toString(),declarationRanges.get(symbol)));
    }

    VernacProjectSymbols(Path root, Map<Path, String> texts) { this(root,texts,Set.of()); }
    VernacProjectSymbols(Path root, Map<Path, String> texts,Set<Path> external) {
        this.root=root;
        texts.forEach((path, text) -> {
            var tree = parse(text);
            var header = tree.namespaceDeclaration();
            if (header == null || header.qualifiedName() == null) return;
            String namespace = header.qualifiedName().getText();
            if (!VernacNames.isNamespace(namespace)
                    || (!external.contains(path) && !root.resolve(namespace.replace('.', '/')).equals(path.getParent()))) return;
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
            for(var top:tree.topLevelDeclaration())if(top.termDefinition()!=null) {
                var term=top.termDefinition();
                try {
                    var node=org.vernac.compiler.vocabulary.Vocabulary.parse(path.toString(),term);
                    var token=term.name==null?term.label:term.name.getStart();
                    int a=utf16(text,token.getStartIndex()),b=utf16(text,token.getStopIndex()+1);
                    var range=new Range(position(text,a),position(text,b));
                    var entry=new org.vernac.compiler.vocabulary.Vocabulary.Entry(namespace,node.name(),node.description(),node.examples(),node.notes(),path.getFileName().toString(),node.location().line(),node.location().column());
                    terms.putIfAbsent(entry.key(),new TermView(entry,new Location(path.toUri().toString(),range),range));
                }catch(org.vernac.compiler.analyzer.SemanticValidationException ignored){ /* Keep unrelated symbols available while a term is edited. */ }
            }
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
                } else if (top.inboundHttpAdapterDefinition() != null) {
                    add(path,namespace,top.inboundHttpAdapterDefinition().name,TypeSymbol.Kind.INBOUND_HTTP_ADAPTER);
                } else if (top.outboundAdapterDefinition() != null) {
                    add(path,namespace,top.outboundAdapterDefinition().name,TypeSymbol.Kind.OUTBOUND_ADAPTER);
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
                    add(path, namespace, top.listenerDefinition().name, TypeSymbol.Kind.LISTENER);
                }
            }
        });
        index = new ProjectSymbolIndex(declarations);
    }

    List<CompletionItem> applicationComplete(String text,Position at) {
        return applicationComplete(text,at,List.of());
    }
    List<CompletionItem> applicationComplete(String text,Position at,List<org.vernac.compiler.modules.ModuleDescriptor> modules) {
        if(text==null)return List.of();
        int point=offset(text,at),start=point;
        while(start>0) {int cp=text.codePointBefore(start);if(!Character.isJavaIdentifierPart(cp)&&cp!='.')break;start-=Character.charCount(cp);}
        String before=text.substring(0,start).stripTrailing(),prefix=text.substring(start,point);
        java.util.stream.Stream<String> names;
        if(before.endsWith("activate")) {
            names=modules.stream().filter(org.vernac.compiler.modules.ModuleDescriptor::activatable).map(org.vernac.compiler.modules.ModuleDescriptor::module);
        } else if(before.endsWith("bind")) {
            names=declarations.stream().filter(d->d.kind()==TypeSymbol.Kind.PORT).map(d->d.identity().qualifiedName());
        } else if(before.endsWith("to")) {
            var match=java.util.regex.Pattern.compile("bind\\s+([\\p{javaJavaIdentifierPart}.]+)\\s+to$").matcher(before);
            String port=match.find()?match.group(1):null;
            names=declarations.stream().filter(d->d.kind()==TypeSymbol.Kind.OUTBOUND_ADAPTER)
                    .filter(d->port!=null&&adapterPort(d).filter(port::equals).isPresent()).map(d->d.identity().qualifiedName());
        } else {
            names=java.util.stream.Stream.of("activate", "bind");
            if(!before.endsWith("{")&&!before.endsWith(";"))return List.of();
        }
        Range range=new Range(position(text,start),at);
        return names.distinct().sorted().filter(n->n.startsWith(prefix)).map(n->{var item=new CompletionItem(n);item.setTextEdit(Either.forLeft(new TextEdit(range,n)));return item;}).toList();
    }

    private Optional<String> adapterPort(TypeSymbol symbol) {
        var parsed=files.get(symbol.sourceFile());
        if(parsed==null)return Optional.empty();
        for(var top:parsed.tree().topLevelDeclaration()) {
            var adapter=top.outboundAdapterDefinition();
            if(adapter==null||!adapter.name.getText().equals(symbol.identity().name()))continue;
            var scope=new FileTypeScope(parsed.unit(),index,namespaces);
            if(!scope.diagnostics().isEmpty())return Optional.empty();
            var lookup=scope.resolve(adapter.portType.getText(),symbol.location());
            return lookup.type().filter(t->t instanceof ResolvedType.Declared)
                    .map(t->((ResolvedType.Declared)t).symbol().identity().qualifiedName());
        }
        return Optional.empty();
    }

    List<Location> applicationDefinition(String text,Position at) {
        return applicationDefinition(text,at,Map.of());
    }
    List<Location> applicationDefinition(String text,Position at,Map<String,Location> modules) {
        if(text==null)return List.of();
        int point=offset(text,at),start=point;
        while(start>0) {int cp=text.codePointBefore(start);if(!Character.isJavaIdentifierPart(cp)&&cp!='.')break;start-=Character.charCount(cp);}
        String before=text.substring(0,start).stripTrailing();
        if(before.endsWith("activate")) {
            int end=point;
            while(end<text.length()) {int cp=text.codePointAt(end);if(!Character.isJavaIdentifierPart(cp)&&cp!='.')break;end+=Character.charCount(cp);}
            var location=modules.get(text.substring(start,end));
            return location==null?List.of():List.of(location);
        }
        return before.endsWith("bind")||before.endsWith("to")?moduleDefinition(text,at):List.of();
    }

    List<CompletionItem> moduleComplete(String text,Position at) {
        int point=offset(text,at),start=point;
        while(start>0) {int cp=text.codePointBefore(start);if(!Character.isJavaIdentifierPart(cp)&&cp!='.')break;start-=Character.charCount(cp);}
        if(!text.substring(0,start).stripTrailing().endsWith("export"))return List.of();
        String prefix=text.substring(start,point);Range range=new Range(position(text,start),at);
        return declarations.stream().filter(d->d.sourceFile().startsWith(root) && !d.sourceFile().toString().contains(".module-contracts"))
            .filter(d->d.kind()!=TypeSymbol.Kind.LISTENER && d.kind()!=TypeSymbol.Kind.INBOUND_HTTP_ADAPTER)
            .map(d->d.identity().qualifiedName()).distinct().sorted().filter(n->n.startsWith(prefix)).map(n->{var item=new CompletionItem(n);item.setTextEdit(Either.forLeft(new TextEdit(range,n)));return item;}).toList();
    }
    List<Location> moduleDefinition(String text,Position at) {
        int point=offset(text,at),start=point,end=point;
        while(start>0) {int cp=text.codePointBefore(start);if(!Character.isJavaIdentifierPart(cp)&&cp!='.')break;start-=Character.charCount(cp);}
        while(end<text.length()) {int cp=text.codePointAt(end);if(!Character.isJavaIdentifierPart(cp)&&cp!='.')break;end+=Character.charCount(cp);}
        String name=text.substring(start,end);
        var symbol=index.find(name).orElse(null);
        return symbol==null?List.of():List.of(location(symbol));
    }

    private void collection(Path path, String namespace, ParserRuleContext owner,
                            List<VernacParser.CollectionDefinitionContext> collections, boolean valueElements) {
        for (var collection : collections) collection(path, namespace, owner, collection, valueElements);
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
        if(cursor<0)return List.of();
        if(excluded(file,cursor))return List.of();
        int termPoint=file.text().codePointCount(0,cursor);
        for(var top:file.tree().topLevelDeclaration())if(top.termDefinition()!=null&&contains(top.termDefinition(),termPoint)) {
            var items=new ArrayList<CompletionItem>();
            for(String keyword:List.of("description","example","note")) {
                var item=new CompletionItem(keyword);item.setInsertText(keyword+" \"${1:text}\";");
                item.setInsertTextFormat(InsertTextFormat.Snippet);items.add(item);
            }
            return items;
        }

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
        var inbound = inboundChoices(path,file,probe,marker,prefix);
        if(inbound!=null) {
            Range range=new Range(position(file.text(),start),position(file.text(),end));
            return inbound.keySet().stream().filter(n->n.startsWith(prefix)).map(n->{
                var item=new CompletionItem(n);item.setKind(CompletionItemKind.Property);item.setTextEdit(Either.forLeft(new TextEdit(range,n)));return item;
            }).toList();
        }
        var configuration = configurationReference(probe, marker);
        if (configuration != null) {
            var choices = configuration.types()
                ? org.vernac.compiler.pipeline.AdapterConfigurationTypes.TYPES.keySet()
                : configurationNames(configuration.adapter());
            Range range = new Range(position(file.text(), start), position(file.text(), end));
            return choices.stream().sorted().filter(name -> name.startsWith(prefix)).map(name -> {
                var item = new CompletionItem(name);
                item.setKind(configuration.types() ? CompletionItemKind.TypeParameter : CompletionItemKind.Property);
                item.setTextEdit(Either.forLeft(new TextEdit(range, name)));
                return item;
            }).toList();
        }
        var restReference = restReference(probe, marker);
        if (restReference != null) {
            var choices = restChoices(path, restReference, prefix);
            Range range = new Range(position(file.text(), start), position(file.text(), end));
            return choices.keySet().stream().filter(name -> name.startsWith(prefix)).map(name -> {
                var item = new CompletionItem(name); item.setKind(CompletionItemKind.Reference);
                item.setTextEdit(Either.forLeft(new TextEdit(range, name))); return item;
            }).toList();
        }
        var mappingReference=mappingReference(probe,marker);
        if(mappingReference!=null) {
            var choices=mappingChoices(path,mappingReference,prefix);
            Range range=new Range(position(file.text(),start),position(file.text(),end));
            return choices.keySet().stream().filter(name->name.startsWith(prefix)).map(name->{
                var item=new CompletionItem(name);item.setKind(CompletionItemKind.Field);
                item.setTextEdit(Either.forLeft(new TextEdit(range,name)));return item;
            }).toList();
        }
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
        var schemaAdapter = schemaAdapter(context);
        if (schemaAdapter != null || inboundTransportType(context)) {
            Set<String> choices = new TreeSet<>(BuiltinTypes.names());
            choices.remove("EventId"); choices.add("list");
            localSchemas(context).stream().filter(d -> d.name != null).forEach(d -> choices.add(d.name.getText()));
            Range range = new Range(position(file.text(),start),position(file.text(),end));
            return choices.stream().filter(name -> name.startsWith(prefix)).map(name -> {
                var item = new CompletionItem(name); item.setKind(CompletionItemKind.Class);
                item.setDetail("Adapter-local DTO type"); item.setTextEdit(Either.forLeft(new TextEdit(range,name))); return item;
            }).toList();
        }
        boolean importing = context.getParent() instanceof VernacParser.ImportDeclarationContext;
        if (!importing && !(context.getParent() instanceof VernacParser.TypeContext)) return null;
        boolean valueField = context.getParent().getParent() instanceof VernacParser.ParameterContext parameter
                && parameter.getParent().getParent() instanceof VernacParser.ValueDefinitionContext;
        boolean eventField = context.getParent().getParent() instanceof VernacParser.ParameterContext parameter
                && parameter.getParent().getParent() instanceof VernacParser.EventDefinitionContext;
        boolean eventTarget = context.getParent().getParent() instanceof VernacParser.ListenerDefinitionContext;
        boolean portTarget = context.getParent().getParent() instanceof VernacParser.OutboundAdapterDefinitionContext a
                && context.getParent() == a.portType;
        boolean portSignature = false;
        for (var ancestor=context.getParent(); ancestor!=null; ancestor=ancestor.getParent())
            if(ancestor instanceof VernacParser.PortMethodDefinitionContext || ancestor instanceof VernacParser.RestOperationContext) portSignature=true;
        final boolean portValue=portSignature;
        final boolean mappingType=mappingAncestor(context)!=null;
        java.util.function.Predicate<TypeSymbol> allowed = symbol -> accepted(symbol)
                && (!(valueField || eventField || portValue || mappingType) || valueType(symbol))
                && (!portTarget || symbol.kind() == TypeSymbol.Kind.PORT)
                && (!eventTarget || symbol.kind() == TypeSymbol.Kind.EVENT);
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
            for (var symbol : declarations) if (allowed.test(symbol))
                choices.put(symbol.identity().qualifiedName(), symbol.kind().toString());
        } else if (!scope.hasErrors() && index.problems().isEmpty()) {
            if (!eventTarget && !portTarget && !portValue) BuiltinTypes.names().stream().filter(name -> !eventField || name.equals("EventId"))
                    .forEach(name -> choices.put(name, "Vernac built-in type"));
            for (var symbol : declarations) {
                if (!allowed.test(symbol)) continue;
                String name = symbol.identity().name();
                var resolved = scope.resolve(name, file.unit().location()).type();
                if (resolved.orElse(null) instanceof ResolvedType.Declared declared
                        && declared.symbol().equals(symbol)) choices.put(name, symbol.identity().qualifiedName());
            }
        }
        if(mappingType) {
            choices.remove("EventId");choices.put("list","Technical collection");
            localSchemas(context).forEach(d->{if(d.name!=null)choices.put(d.name.getText(),"Adapter-local DTO");});
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

    private TermView termAt(Path path,Position at) {
        return terms.values().stream().filter(t->t.location().getUri().equals(path.toUri().toString())
            && compare(at,t.nameRange().getStart())>=0&&compare(at,t.nameRange().getEnd())<0).findFirst().orElse(null);
    }
    private static int compare(Position a,Position b) {
        int lines=Integer.compare(a.getLine(),b.getLine());return lines==0?Integer.compare(a.getCharacter(),b.getCharacter()):lines;
    }
    String hover(Path path,Position at) {
        var direct=termAt(path,at);
        if(direct!=null)return org.vernac.compiler.vocabulary.Vocabulary.markdown(direct.entry());
        TypeSymbol symbol=declarations.stream().filter(d->d.sourceFile().equals(path)&&declarationRanges.containsKey(d)
            &&compare(at,declarationRanges.get(d).getStart())>=0&&compare(at,declarationRanges.get(d).getEnd())<0).findFirst().orElse(null);
        if(symbol==null) {
            var targets=definition(path,at);
            symbol=declarations.stream().filter(d->targets.contains(location(d))).findFirst().orElse(null);
        }
        if(symbol==null)return "";
        String header="**"+symbol.kind().name()+"** `"+symbol.identity().qualifiedName()+"`";
        var term=terms.get(new org.vernac.compiler.vocabulary.Vocabulary.Key(symbol.identity().namespace(),symbol.identity().name()));
        return term==null?header:header+"\n\n"+org.vernac.compiler.vocabulary.Vocabulary.markdown(term.entry())
            +"\n\n[Vocabulary definition]("+term.location().getUri()+")";
    }

    List<Location> definition(Path path, Position position) {
        Parsed file = files.get(path);
        if (file == null) return List.of();
        var term=termAt(path,position);
        if(term!=null) {
            var type=index.find(term.entry().qualifiedName()).orElse(null);
            return type==null?List.of(term.location()):List.of(location(type));
        }
        int offset = offset(file.text(), position);
        if (offset < 0) return List.of();
        int point = file.text().codePointCount(0, offset);
        int inboundStart=offset;
        while(inboundStart>0 && nameCharacter(file.text().codePointBefore(inboundStart)))inboundStart-=Character.charCount(file.text().codePointBefore(inboundStart));
        var inbound=inboundChoices(path,file,file.tree(),point,file.text().substring(inboundStart,offset));
        if(inbound!=null) {
            List<ParserRuleContext> names=new ArrayList<>();walk(file.tree(),n->{if(n instanceof ParserRuleContext c && (c instanceof VernacParser.QualifiedNameContext || c instanceof VernacParser.MethodNameContext || c instanceof VernacParser.VariableNameContext) && contains(c,point))names.add(c);});
            for(var name:names) {
                String value=name.getText();
                if(name instanceof VernacParser.QualifiedNameContext q)value=q.qualifiedNameSegment().stream().filter(n->n.getStart().getStartIndex()<=point).map(n->n.getText()).collect(java.util.stream.Collectors.joining("."));
                var target=inbound.get(value);if(target!=null)return List.of(target);
            }
            return List.of();
        }
        var configuration = configurationReference(file.tree(), point);
        if (configuration != null) {
            if (configuration.types() || configuration.adapter().configurationBlock() == null) return List.of();
            String name = configuration.adapter().restBlock().base.getText();
            return configuration.adapter().configurationBlock().configurationEntry().stream()
                .filter(entry -> entry.name != null && entry.name.getText().equals(name))
                .map(entry -> queryLocation(path, file, entry.name)).toList();
        }
        var restReference = restReference(file.tree(), point);
        if (restReference != null) {
            String name = restReference.name().getText();
            if (restReference.name() instanceof VernacParser.QualifiedNameContext qualified)
                name = qualified.qualifiedNameSegment().stream().filter(n -> n.getStart().getStartIndex() <= point)
                    .map(n -> n.getText()).collect(java.util.stream.Collectors.joining("."));
            var target = restChoices(path, restReference, name).get(name);
            return target == null ? List.of() : List.of(target);
        }
        var mappingReference=mappingReference(file.tree(),point);
        if(mappingReference!=null) {
            String name=mappingReference.name().getText();
            if(mappingReference.kind().equals("source")) {
                var pathNode=(VernacParser.QualifiedNameContext)mappingReference.name();
                name=pathNode.qualifiedNameSegment().stream().filter(n->n.getStart().getStartIndex()<=point).map(n->n.getText()).collect(java.util.stream.Collectors.joining("."));
            }
            var target=mappingChoices(path,mappingReference,name).get(name);
            return target==null?List.of():List.of(target);
        }
        var query=queryReference(file.tree(),point);
        if(query!=null) {
            var target=queryChoices(path,file,query).get(query.name().getText());
            return target==null?List.of():List.of(target);
        }

        List<VernacParser.QualifiedNameContext> dtoReferences = new ArrayList<>();
        walk(file.tree(), node -> {
            if(node instanceof VernacParser.QualifiedNameContext name && contains(name,point) && (schemaAdapter(name)!=null || inboundTransportType(name) || name.getParent() instanceof VernacParser.TypeContext && mappingAncestor(name)!=null))
                dtoReferences.add(name);
        });
        if(!dtoReferences.isEmpty()) {
            var reference=dtoReferences.getFirst();
            var local=localSchemas(reference).stream()
                .filter(d -> d.name!=null && d.name.getText().equals(reference.getText()))
                .map(d -> queryLocation(path,file,d.name)).toList();
            if(!local.isEmpty() || schemaAdapter(reference)!=null || inboundTransportType(reference))return local;
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
        return List.of(location(target));
    }

    private static VernacParser.InboundHttpAdapterDefinitionContext inboundAncestor(ParseTree node) {
        for(var n=node;n!=null;n=n.getParent())if(n instanceof VernacParser.InboundHttpAdapterDefinitionContext a)return a;
        return null;
    }
    private static List<VernacParser.SchemaDefinitionContext> localSchemas(ParseTree node) {
        var inbound=inboundAncestor(node);if(inbound!=null)return inbound.schemaDefinition();
        var outbound=adapterAncestor(node);return outbound==null?List.of():outbound.schemaDefinition();
    }
    private static boolean inboundTransportType(ParseTree node) {
        for(var n=node;n!=null;n=n.getParent()) {
            if(n instanceof VernacParser.SchemaFieldContext)return inboundAncestor(n)!=null;
            if(n instanceof VernacParser.TypeContext t && t.getParent() instanceof VernacParser.InboundEndpointContext e)
                return t==e.bodyType || t==e.responseType;
        }
        return false;
    }
    private VernacParser.UsecaseDefinitionContext inboundUsecase(Parsed file,VernacParser.InboundHttpAdapterDefinitionContext adapter,String dependency) {
        var parameter=adapter.dependencies.stream().filter(p->parameterName(p).equals(dependency)).findFirst();
        if(parameter.isEmpty())return null;
        var scope=new FileTypeScope(file.unit(),index,namespaces);
        var found=scope.resolve(parameter.get().paramType.rawType.getText(),file.unit().location()).type().orElse(null);
        if(!(found instanceof ResolvedType.Declared d) || d.symbol().kind()!=TypeSymbol.Kind.USE_CASE)return null;
        var source=files.get(d.symbol().sourceFile());if(source==null)return null;
        return source.tree().topLevelDeclaration().stream().map(VernacParser.TopLevelDeclarationContext::usecaseDefinition)
                .filter(Objects::nonNull).filter(u->u.name!=null && u.name.getText().equals(d.symbol().identity().name())).findFirst().orElse(null);
    }
    private static String parameterName(VernacParser.ParameterContext p) {
        return p.name==null?org.vernac.language.VernacNames.defaultMemberName(p.paramType.rawType.getText()):p.name.getText();
    }
    private Map<String,Location> inboundChoices(Path path,Parsed file,ParseTree tree,int point,String prefix) {
        List<ParserRuleContext> hits=new ArrayList<>();
        walk(tree,node->{
            if(node instanceof VernacParser.InboundEndpointContext e && contains(e.dependency,point))hits.add(e.dependency);
            if(node instanceof VernacParser.MappingAssignmentContext a && inboundAncestor(a)!=null) {
                if(contains(a.target,point))hits.add(a.target);
                if(contains(a.path,point))hits.add(a.path);
                if(contains(a.mapper,point))hits.add(a.mapper);
            }
            if(node instanceof VernacParser.InboundEndpointContext e && contains(e.responseMapping,point))hits.add(e.responseMapping);
        });
        if(hits.isEmpty())return null;
        var hit=hits.getFirst();var adapter=inboundAncestor(hit);Map<String,Location> result=new TreeMap<>();
        if(hit.getParent() instanceof VernacParser.InboundEndpointContext e && hit==e.dependency) {
            for(var d:adapter.dependencies)result.put(parameterName(d),queryLocation(path,file,d));return result;
        }
        if(hit.getParent() instanceof VernacParser.InboundEndpointContext || hit.getParent() instanceof VernacParser.MappingAssignmentContext a && hit==a.mapper) {
            for(var m:adapter.mappingDefinition())if(m.name!=null)result.put(m.name.getText(),queryLocation(path,file,m.name));return result;
        }
        var assignment=(VernacParser.MappingAssignmentContext)hit.getParent();
        var mapping=mappingAncestor(assignment);
        VernacParser.InboundEndpointContext endpoint=null;
        for(var n=assignment.getParent();n!=null;n=n.getParent())if(n instanceof VernacParser.InboundEndpointContext e){endpoint=e;break;}
        if(hit==assignment.target) {
            if(mapping!=null)return inboundFields(path,file,adapter,mapping.targetType,List.of(),"");
            var usecase=endpoint==null?null:inboundUsecase(file,adapter,endpoint.dependency.getText());
            if(usecase!=null && usecase.inputs!=null)for(var p:usecase.inputs.parameter())result.put(parameterName(p),queryLocation(pathForUsecase(usecase),files.get(pathForUsecase(usecase)),p));
            return result;
        }
        int dot=prefix.lastIndexOf('.');String qualifier=dot<0?"":prefix.substring(0,dot+1);
        var steps=dot<0?List.<String>of():List.of(prefix.substring(0,dot).split("\\."));
        if(mapping!=null) {
            if(steps.isEmpty()){result.put("source",queryLocation(path,file,mapping.sourceType));return result;}
            if(!steps.getFirst().equals("source"))return result;
            return inboundFields(path,file,adapter,mapping.sourceType,steps.subList(1,steps.size()),qualifier);
        }
        if(endpoint==null)return result;
        if(steps.isEmpty()) {
            for(String group:List.of("path","query","header"))if(endpoint.inboundBinding().stream().anyMatch(b->b.kind.getText().equals(group)))result.put(group,queryLocation(path,file,endpoint));
            if(endpoint.bodyType!=null)result.put("body",queryLocation(path,file,endpoint.bodyType));return result;
        }
        if(steps.getFirst().equals("body") && endpoint.bodyType!=null)return inboundFields(path,file,adapter,endpoint.bodyType,steps.subList(1,steps.size()),qualifier);
        if(steps.size()==1)for(var binding:endpoint.inboundBinding())if(binding.kind.getText().equals(steps.getFirst())) {
            String name=binding.name==null?org.vernac.language.VernacNames.defaultMemberName(binding.valueType.rawType.getText()):binding.name.getText();
            result.put(qualifier+name,queryLocation(path,file,binding.name==null?binding:binding.name));
        }
        return result;
    }
    private Path pathForUsecase(VernacParser.UsecaseDefinitionContext usecase) {
        return files.entrySet().stream().filter(e->e.getValue().tree().topLevelDeclaration().stream().anyMatch(d->d.usecaseDefinition()==usecase)).map(Map.Entry::getKey).findFirst().orElseThrow();
    }
    private Map<String,Location> inboundFields(Path path,Parsed file,VernacParser.InboundHttpAdapterDefinitionContext adapter,
            VernacParser.PageTypeContext type,List<String> steps,String qualifier) {
        return inboundFields(path,file,adapter,type==null?null:type.type(),steps,qualifier);
    }
    private String resultParameterName(VernacParser.ResultParameterContext p) {
        return p.name==null?org.vernac.language.VernacNames.defaultMemberName(p.paramType.type().rawType.getText()):p.name.getText();
    }
    private Map<String,Location> inboundFields(Path path,Parsed file,VernacParser.InboundHttpAdapterDefinitionContext adapter,
            VernacParser.TypeContext type,List<String> steps,String qualifier) {
        Map<String,Location> result=new TreeMap<>();if(type==null || type.rawType==null)return result;
        String name=type.rawType.getText();Path origin=path;Parsed owner=file;
        List<VernacParser.ResultParameterContext> results=List.of();
        List<VernacParser.ParameterContext> parameters=List.of();List<VernacParser.SchemaFieldContext> fields=List.of();
        var dto=adapter.schemaDefinition().stream().filter(s->s.name!=null && s.name.getText().equals(name)).findFirst();
        if(dto.isPresent())fields=dto.get().schemaField();
        else {
            String base=name.endsWith(".Result")?name.substring(0,name.length()-7):name;
            var found=new FileTypeScope(file.unit(),index,namespaces).resolve(base,file.unit().location()).type().orElse(null);
            if(found instanceof ResolvedType.Declared declared) {
                origin=declared.symbol().sourceFile();owner=files.get(origin);if(owner==null)return result;
                for(var d:owner.tree().topLevelDeclaration()) {
                    if(name.endsWith(".Result") && d.usecaseDefinition()!=null && d.usecaseDefinition().name.getText().equals(declared.symbol().identity().name()) && d.usecaseDefinition().results!=null)results=d.usecaseDefinition().results.resultParameter();
                    if(d.valueDefinition()!=null && d.valueDefinition().name.getText().equals(declared.symbol().identity().name()) && d.valueDefinition().parameterList()!=null)parameters=d.valueDefinition().parameterList().parameter();
                    if(d.idDeclaration()!=null && d.idDeclaration().name.getText().equals(declared.symbol().identity().name()) && steps.isEmpty())result.put(qualifier+"value",queryLocation(origin,owner,d.idDeclaration()));
                }
            }
        }
        if(!steps.isEmpty()) {
            String next=steps.getFirst();
            for(var f:fields)if((f.name==null?org.vernac.language.VernacNames.defaultMemberName(f.fieldType.type().rawType.getText()):f.name.getText()).equals(next))return inboundFields(path,file,adapter,f.fieldType,steps.subList(1,steps.size()),qualifier);
            for(var p:results)if(resultParameterName(p).equals(next))return inboundFields(origin,owner,adapter,p.paramType,steps.subList(1,steps.size()),qualifier);
            for(var p:parameters)if(parameterName(p).equals(next))return inboundFields(origin,owner,adapter,p.paramType,steps.subList(1,steps.size()),qualifier);
            return result;
        }
        for(var f:fields)result.put(qualifier+(f.name==null?org.vernac.language.VernacNames.defaultMemberName(f.fieldType.type().rawType.getText()):f.name.getText()),queryLocation(path,file,f.name==null?f:f.name));
        for(var p:results)result.put(qualifier+resultParameterName(p),queryLocation(origin,owner,p.name==null?p:p.name));
        for(var p:parameters)result.put(qualifier+parameterName(p),queryLocation(origin,owner,p.name==null?p:p.name));
        return result;
    }

    private static VernacParser.MappingDefinitionContext mappingAncestor(ParseTree node) {
        for(var n=node;n!=null;n=n.getParent())if(n instanceof VernacParser.MappingDefinitionContext m)return m;
        return null;
    }
    private static VernacParser.OutboundAdapterDefinitionContext adapterAncestor(ParseTree node) {
        for(var n=node;n!=null;n=n.getParent())if(n instanceof VernacParser.OutboundAdapterDefinitionContext a)return a;
        return null;
    }
    private record MappingReference(ParserRuleContext name,String kind,VernacParser.MappingDefinitionContext mapping,
                                    VernacParser.OutboundAdapterDefinitionContext adapter) { }
    private static MappingReference mappingReference(ParseTree tree,int point) {
        List<MappingReference> result=new ArrayList<>();
        walk(tree,node->{if(node instanceof VernacParser.MappingAssignmentContext a) {
            ParserRuleContext name=contains(a.target,point)?a.target:contains(a.path,point)?a.path:contains(a.mapper,point)?a.mapper:null;
            if(name!=null && adapterAncestor(a)!=null && mappingAncestor(a)!=null)result.add(new MappingReference(name,name==a.target?"target":name==a.path?"source":"using",mappingAncestor(a),adapterAncestor(a)));
        }});
        return result.size()==1?result.getFirst():null;
    }
    private Map<String,Location> mappingChoices(Path path,MappingReference reference,String prefix) {
        Map<String,Location> choices=new TreeMap<>();Parsed file=files.get(path);
        if(reference.kind().equals("using")) {
            reference.adapter().mappingDefinition().forEach(m->{if(m.name!=null)choices.put(m.name.getText(),queryLocation(path,file,m.name));});
            return choices;
        }
        try {
            ensureMappingModel();
            if(mappingModel==null)return choices;
            var syntax=reference.kind().equals("target")?reference.mapping().targetType:reference.mapping().sourceType;
            if(syntax==null || syntax.type().rawType==null)return choices;
            var type=new AstBuilderVisitor(path.toString()).visitPageType(syntax);
            List<String> segments=new ArrayList<>();String qualifier="";
            if(reference.kind().equals("source")) {
                int dot=prefix.lastIndexOf('.');
                if(dot<0) {choices.put("source",queryLocation(path,file,reference.mapping().sourceType));return choices;}
                qualifier=prefix.substring(0,dot+1);
                String[] parts=prefix.substring(0,dot).split("\\.");
                if(!parts[0].equals("source"))return choices;
                segments.addAll(Arrays.asList(parts).subList(1,parts.length));
            }
            var fields=mappingModel.fields(path,reference.adapter().name.getText(),type,segments);
            for(var field:fields.entrySet()) {
                var loc=field.getValue();Path target=Path.of(loc.sourceName());var targetFile=files.get(target);if(targetFile==null)continue;
                String line=targetFile.text().lines().skip(loc.line()-1).findFirst().orElse("");
                int column=line.offsetByCodePoints(0,Math.min(loc.column()-1,line.codePointCount(0,line.length())));
                Position at=new Position(loc.line()-1,column);
                choices.put(qualifier+field.getKey(),new Location(target.toUri().toString(),new Range(at,at)));
            }
        } catch(IllegalArgumentException | NoSuchElementException | org.vernac.compiler.analyzer.SemanticValidationException incomplete) {
            // Incomplete declarations have no guessed field targets.
        }
        return choices;
    }

    private void ensureMappingModel() {
            if(!mappingModelAttempted) {
                mappingModelAttempted=true;
                List<org.vernac.compiler.pipeline.VernacSourceFile> sources=new ArrayList<>();
                for(var entry:files.entrySet()) {
                    try {sources.add(new org.vernac.compiler.pipeline.VernacSourceFile(entry.getKey(),new org.vernac.compiler.pipeline.VernacSourceParser().parse(entry.getKey().toString(),entry.getValue().text())));}
                    catch(org.vernac.compiler.analyzer.SemanticValidationException incomplete) { }
                }
                var project=new org.vernac.compiler.pipeline.VernacProjectLoader().index(root,sources);
                mappingModel=new org.vernac.compiler.tooling.MappingEditorModel(project);
            }
    }

    private record RestReference(ParserRuleContext name, String kind, VernacParser.RestOperationContext operation,
                                 VernacParser.OutboundAdapterDefinitionContext adapter) { }
    private static RestReference restReference(ParseTree tree, int point) {
        List<RestReference> found = new ArrayList<>();
        walk(tree, node -> {
            if (node instanceof VernacParser.InboundErrorSelectorContext selector) {
                if (contains(selector.rejectionCode, point)) found.add(new RestReference(selector.rejectionCode,"errorCode",null,null));
                if (contains(selector.category, point)) found.add(new RestReference(selector.category,"errorCategory",null,null));
            }
            if (!(node instanceof VernacParser.RestOperationContext op)) return;
            ParserRuleContext name = null; String kind = "source";
            if (contains(op.requestMapping, point)) { name = op.requestMapping; kind = "using"; }
            else if (contains(op.responseMapping, point)) { name = op.responseMapping; kind = "using"; }
            else if (contains(op.requestPath, point)) name = op.requestPath;
            else for (var binding : op.restBinding()) if (contains(binding.source, point)) name = binding.source;
            if (name != null) found.add(new RestReference(name, kind, op, adapterAncestor(op)));
            if (op.restErrors() != null) walk(op.restErrors(), child -> {
                if (child instanceof VernacParser.RestErrorPredicateContext p && contains(p.field, point))
                    found.add(new RestReference(p.field, "errorField", op, adapterAncestor(op)));
                if (child instanceof VernacParser.RestErrorActionContext a) {
                    if (contains(a.code, point)) found.add(new RestReference(a.code, "errorCode", op, adapterAncestor(op)));
                    if (contains(a.category, point)) found.add(new RestReference(a.category, "errorCategory", op, adapterAncestor(op)));
                }
            });
        });
        return found.size() == 1 ? found.getFirst() : null;
    }
    private Map<String, Location> restChoices(Path path, RestReference ref, String prefix) {
        var file = files.get(path);
        Map<String, Location> choices = new TreeMap<>();
        if (ref.kind().equals("errorCategory")) {
            for (var category : org.vernac.runtime.ports.IntegrationCategory.values()) choices.put(category.name(), null);
            return choices;
        }
        if (ref.kind().equals("errorCode")) {
            var scope = new FileTypeScope(file.unit(), index, namespaces);
            for (var symbol : declarations) {
                if (symbol.kind() != TypeSymbol.Kind.ENUM) continue;
                String name = symbol.identity().name();
                var visible = scope.resolve(name, file.unit().location()).type().orElse(null);
                List<String> names = new ArrayList<>();
                names.add(symbol.identity().qualifiedName());
                if (visible instanceof ResolvedType.Declared d && d.symbol().equals(symbol)) names.add(name);
                var target = files.get(symbol.sourceFile());
                for (String spelling : names) {
                    choices.put(spelling, location(symbol));
                    for (var top : target.tree().topLevelDeclaration()) {
                        var v = top.valueDefinition();
                        if (v == null || v.name == null || !v.name.getText().equals(name) || v.enumConstantList() == null) continue;
                        for (var constant : v.enumConstantList().enumConstant()) if (constant.name != null)
                            choices.put(spelling + "." + constant.name.getText(), queryLocation(symbol.sourceFile(), target, constant.name));
                    }
                }
            }
            return choices;
        }
        if (ref.kind().equals("errorField")) {
            choices.put("status", null);
            var errors = ref.operation().restErrors();
            if (errors != null && errors.errorType != null) {
                choices.put("response", queryLocation(path, file, errors.errorType));
                int dot = prefix.lastIndexOf('.');
                if (dot >= 0 && prefix.startsWith("response.")) {
                    try {
                        ensureMappingModel();
                        if (mappingModel != null) {
                            var type = new TypeNode(file.unit().location(), errors.errorType.getText());
                            var segments = prefix.substring(0, dot).split("\\.");
                            var fields = mappingModel.fields(path, ref.adapter().name.getText(), type,
                                    Arrays.asList(segments).subList(1, segments.length));
                            String qualifier = prefix.substring(0, dot + 1);
                            fields.forEach((name, at) -> choices.put(qualifier + name, location(path, file, at)));
                        }
                    } catch (IllegalArgumentException | NoSuchElementException | org.vernac.compiler.analyzer.SemanticValidationException incomplete) { }
                }
            }
            return choices;
        }
        if (ref.kind().equals("using")) {
            ref.adapter().mappingDefinition().forEach(m -> {
                if (m.name != null) choices.put(m.name.getText(), queryLocation(path, file, m.name));
            });
            return choices;
        }
        try {
            // The AST supplies inferred names too; no second naming convention in the LSP.
            var ast = new AstBuilderVisitor(path.toString()).visitOutboundAdapterDefinition(ref.adapter());
            var operation = ast.rest().orElseThrow().operations().stream()
                    .filter(o -> o.method().location().line() == ref.operation().getStart().getLine()
                            && o.method().location().column() == ref.operation().getStart().getCharPositionInLine() + 1)
                    .findFirst().orElseThrow();
            int dot = prefix.lastIndexOf('.');
            for (var parameter : operation.method().parameters()) {
                if (dot < 0) {
                    var at = parameter.location();
                    choices.put(parameter.name(), location(path, file, at));
                    continue;
                }
                String[] segments = prefix.substring(0, dot).split("\\.");
                if (!segments[0].equals(parameter.name())) continue;
                ensureMappingModel();
                if (mappingModel == null) continue;
                var raw = new org.vernac.compiler.ast.TypeNode(parameter.type().location(), parameter.type().name(),
                        parameter.type().typeArguments(), false);
                var fields = mappingModel.fields(path, ast.name(), raw,
                        Arrays.asList(segments).subList(1, segments.length));
                String qualifier = prefix.substring(0, dot + 1);
                for (var field : fields.entrySet()) {
                    Path targetPath = Path.of(field.getValue().sourceName());
                    var target = files.get(targetPath);
                    if (target != null) choices.put(qualifier + field.getKey(), location(targetPath, target, field.getValue()));
                }
            }
        } catch (IllegalArgumentException | NoSuchElementException | org.vernac.compiler.analyzer.SemanticValidationException incomplete) {
            // Tolerate partially typed declarations, just like mapping completion.
        }
        return choices;
    }
    private Location location(Path path, Parsed file, org.vernac.compiler.ast.SourceLocation at) {
        String line = file.text().lines().skip(at.line() - 1).findFirst().orElse("");
        int column = line.offsetByCodePoints(0, Math.min(at.column() - 1, line.codePointCount(0, line.length())));
        Position start = new Position(at.line() - 1, column);
        return new Location(path.toUri().toString(), new Range(start, start));
    }

    private static VernacParser.OutboundAdapterDefinitionContext schemaAdapter(ParseTree node) {
        boolean field = false;
        for(var ancestor=node;ancestor!=null;ancestor=ancestor.getParent()) {
            if(ancestor instanceof VernacParser.SchemaFieldContext) field=true;
            if(ancestor instanceof VernacParser.TypeContext type && type.getParent() instanceof VernacParser.RestOperationContext op
                    && op.responseType == type) field = true;
            if (ancestor instanceof VernacParser.TypeContext type && type.getParent() instanceof VernacParser.RestErrorsContext errors
                    && errors.errorType == type) field = true;
            if(ancestor instanceof VernacParser.OutboundAdapterDefinitionContext adapter) return field ? adapter : null;
        }
        return null;
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
    private record ConfigurationReference(boolean types, VernacParser.OutboundAdapterDefinitionContext adapter) {}
    private static ConfigurationReference configurationReference(ParseTree tree, int point) {
        var found = new ArrayList<ConfigurationReference>();
        walk(tree, node -> {
            if (!(node instanceof VernacParser.OutboundAdapterDefinitionContext adapter)) return;
            if (adapter.configurationBlock() != null) for (var entry : adapter.configurationBlock().configurationEntry())
                if (contains(entry.configType, point)) found.add(new ConfigurationReference(true, adapter));
            if (adapter.restBlock() != null && contains(adapter.restBlock().base, point))
                found.add(new ConfigurationReference(false, adapter));
        });
        return found.size() == 1 ? found.getFirst() : null;
    }
    private static Set<String> configurationNames(VernacParser.OutboundAdapterDefinitionContext adapter) {
        Set<String> names = new TreeSet<>();
        if (adapter.configurationBlock() != null) for (var entry : adapter.configurationBlock().configurationEntry())
            if (entry.name != null && entry.configType != null && entry.configType.getText().equals("URI")) names.add(entry.name.getText());
        return names;
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
