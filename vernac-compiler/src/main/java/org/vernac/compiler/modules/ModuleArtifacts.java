// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.modules;

import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.jar.JarFile;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.analyzer.*;
import static org.vernac.compiler.modules.ModuleDescriptor.*;

/** Reads data-only contracts from the resolved Maven classpath. */
public final class ModuleArtifacts {
    private ModuleArtifacts() { }
    public static List<ModuleDescriptor> read(List<Path> classpath) throws IOException {
        List<ModuleDescriptor> result=new ArrayList<>();Set<Path> seen=new HashSet<>();
        for(var path:classpath) {
            path=path.toAbsolutePath().normalize();if(!seen.add(path)||!Files.exists(path))continue;
            if(Files.isDirectory(path)) {
                Path file=path.resolve(RESOURCE);if(Files.isRegularFile(file)){var module=JSON.readValue(Files.readString(file),ModuleDescriptor.class);verifyPersistence(module,path);result.add(module);}
            } else if(path.toString().endsWith(".jar")) try(var jar=new JarFile(path.toFile())) {
                var entry=jar.getJarEntry(RESOURCE);
                if(entry!=null)try(var input=jar.getInputStream(entry)) {if(entry.getSize()>8_000_000)throw new IOException("Oversized Vernac module metadata: "+path);var module=JSON.readValue(input,ModuleDescriptor.class);verifyPersistence(module,path);result.add(module);}
            }
        }
        ModuleContracts.checkDependencies(result);return List.copyOf(result);
    }
    private static void verifyPersistence(ModuleDescriptor module,Path artifact) throws IOException {
        if(!module.persistent())return;
        String prefix=ModulePersistence.directory(module.module());Map<String,byte[]> bytes=new TreeMap<>();
        if(Files.isDirectory(artifact)) {
            Path directory=artifact.resolve(prefix);
            if(Files.isDirectory(directory))try(var paths=Files.walk(directory)){for(var file:paths.filter(Files::isRegularFile).toList())bytes.put(artifact.relativize(file).toString().replace('\\','/'),Files.readAllBytes(file));}
        }else try(var jar=new JarFile(artifact.toFile())) {
            for(var entry:jar.stream().filter(e->!e.isDirectory()&&e.getName().startsWith(prefix)).toList())try(var input=jar.getInputStream(entry)){bytes.put(entry.getName(),input.readAllBytes());}
        }
        byte[] manifest=bytes.get(ModulePersistence.resource(module.module()));
        if(manifest==null)throw new IOException("Persistent module is missing its migration bundle: "+module.module()+". Rebuild the producer with reviewed module history.");
        var descriptor=JSON.readValue(manifest,ModulePersistence.Descriptor.class);
        if(!descriptor.module().equals(module.module()))throw new IOException("Persistence owner differs from module contract: "+module.module());
        ModulePersistence.verify(descriptor,bytes);
    }
    public static VernacProject merge(VernacProject local,List<ModuleDescriptor> modules) {
        List<CompilerDiagnostic> issues=new ArrayList<>();
        var result=merge(local,modules,issues);
        if(!issues.isEmpty())throw new SemanticValidationException(issues);return result;
    }
    public static VernacProject merge(VernacProject local,List<ModuleDescriptor> modules,List<CompilerDiagnostic> issues) {
        if(modules.isEmpty())return local;
        List<VernacSourceFile> sources=new ArrayList<>(local.sources());
        for(var module:modules) {
            Map<String,List<Definition>> namespaces=new TreeMap<>();
            for(var d:module.exports())namespaces.computeIfAbsent(namespace(d.identity()),n->new ArrayList<>()).add(d);
            for(var entry:namespaces.entrySet()) {
                Path path=projectDirectory(local.sourceRoot()).resolve("target/vernac-module-contracts").resolve(module.module()).resolve(entry.getKey().replace('.','/')).resolve("contract.vernac");
                SourceLocation at=new SourceLocation(path.toString(),1,1);List<TopLevelDefinition> definitions=new ArrayList<>();
                for(var d:entry.getValue()) if(!d.kind().equals("COLLECTION")) {
                    var collections=entry.getValue().stream().filter(c->c.kind().equals("COLLECTION")&&c.element().equals(d.identity())).map(c->new CollectionDefinitionNode(at,CollectionDefinitionNode.Kind.valueOf(c.collectionKind()),Optional.of(simple(c.identity())),methods(c.methods(),at),List.of())).toList();
                    if(d.kind().equals("ID"))definitions.add(new IdDeclarationNode(at,simple(d.identity()),collections));
                    else if(d.kind().equals("VALUE_OBJECT")||d.kind().equals("ENUM"))definitions.add(new ValueObjectNode(at,simple(d.identity()),fields(d.fields(),at),d.constants().stream().map(c->new EnumConstantNode(at,c)).toList(),List.of(),methods(d.methods(),at),collections,List.of()));
                    else if(d.kind().equals("ENTITY")||d.kind().equals("AGGREGATE")) {
                        var id=new IdReferenceNode(at,new TypeNode(at,d.element()));
                        if(d.kind().equals("ENTITY"))definitions.add(new EntityNode(at,simple(d.identity()),id,fields(d.fields(),at),List.of(),methods(d.methods(),at),collections,List.of()));
                        else definitions.add(new AggregateNode(at,simple(d.identity()),id,fields(d.fields(),at),List.of(),methods(d.methods(),at),collections,List.of()));
                    }
                    else if(d.kind().equals("REPOSITORY")) {
                        List<RepositoryMethodNode> queries=d.methods().stream().filter(m->!Set.of("byId","save","delete").contains(m.name())).map(m->new RepositoryMethodNode(at,type(m.returns(),at),m.name(),fields(m.parameters(),at),List.of(),List.of(),null,m.mode().equals("CURSOR_QUERY")?RepositoryMethodNode.Pagination.CURSOR:m.mode().contains("OFFSET_QUERY")?RepositoryMethodNode.Pagination.OFFSET:RepositoryMethodNode.Pagination.NONE,m.mode().equals("COUNTED_OFFSET_QUERY"))).toList();
                        definitions.add(new RepositoryNode(at,simple(d.identity()),d.element(),queries));
                    }
                    else if(d.kind().equals("EVENT"))definitions.add(new EventNode(at,simple(d.identity()),Boolean.parseBoolean(d.javaMembers().get("@recorded")),fields(d.fields(),at),List.of()));
                    else if(d.kind().equals("PORT"))definitions.add(new PortNode(simple(d.identity()),d.methods().stream().map(m->new PortMethodNode(m.name(),type(m.returns(),at),fields(m.parameters(),at),at)).toList(),at));
                    else if(d.kind().equals("OUTBOUND_ADAPTER"))definitions.add(new OutboundAdapterNode(at,simple(d.identity()),new TypeNode(at,d.element(),List.of(),false),fields(d.fields(),at),List.of(),List.of(),methods(d.methods(),at).stream().map(m->new ServiceMethodNode(m,List.of())).toList(),List.of(),List.of()));
                    else if(d.kind().equals("DOMAIN_SERVICE"))definitions.add(new DomainServiceNode(at,simple(d.identity()),List.of(),List.of(),methods(d.methods(),at).stream().map(m->new ServiceMethodNode(m,List.of())).toList()));
                    else if(d.kind().equals("USE_CASE")) {
                        var execute=d.methods().stream().filter(m->m.name().equals("execute")).findFirst().orElseThrow();
                        Optional<TypeNode> returns=execute.returns().name().equals("void")||execute.returns().name().equals("@result")?Optional.empty():Optional.of(type(execute.returns(),at));
                        definitions.add(new UseCaseNode(at,simple(d.identity()),fields(execute.parameters(),at),List.of(),List.of(),returns,fields(d.fields(),at),List.of(),List.of(),"throw new UnsupportedOperationException();",Optional.empty(),1));
                    }
                    else throw new IllegalArgumentException("Unsupported type-module definition: "+d.identity());
                }
                sources.add(new VernacSourceFile(path,new CompilationUnitNode(at,entry.getKey(),List.of(),definitions,
                    module.vocabulary().stream().filter(t->t.namespace().equals(entry.getKey()))
                        .map(t->new TermNode(at,t.name(),t.description(),t.examples(),t.notes())).toList()),true));
            }
        }
        var merged=new VernacProjectLoader().index(local.sourceRoot(),sources,issues);
        Map<String,String> hidden=new TreeMap<>();
        for(var module:modules)for(String identity:module.internalTypes()) {
            hidden.put(identity,module.module()+" ("+module.coordinates()+")");
            merged.symbols().find(identity).ifPresent(symbol->issues.add(CompilerDiagnostic.error(symbol.location(),"Definition "+identity+" collides with an internal dependency type owned by "+module.module())));
        }
        return new VernacProject(merged.sourceRoot(),merged.sources(),merged.symbols().withHiddenTypes(hidden));
    }
    private static List<FieldNode> fields(List<Field> source,SourceLocation at) {return source.stream().map(f->new FieldNode(at,type(f.type(),at),f.name(),f.mutable())).toList();}
    private static TypeNode type(Type source,SourceLocation at) {return new TypeNode(at,source.name(),List.of(),source.optional(),TypeNode.PageKind.valueOf(source.pageKind()));}
    private static List<MethodNode> methods(List<Method> source,SourceLocation at) {return source.stream().map(m->new MethodNode(at,"public",type(m.returns(),at),m.name(),fields(m.parameters(),at),"throw new UnsupportedOperationException();",Optional.empty(),MethodNode.Mode.valueOf(m.mode()))).toList();}
    private static String namespace(String name) {return name.substring(0,name.lastIndexOf('.'));}
    private static String simple(String name) {return name.substring(name.lastIndexOf('.')+1);}
    public static Path projectDirectory(Path sourceRoot) {
        for(Path path=sourceRoot.toAbsolutePath();path!=null;path=path.getParent()) if(Files.isRegularFile(path.resolve("pom.xml"))||Files.isRegularFile(path.resolve(ModuleDefinition.FILE)))return path;
        Path absolute=sourceRoot.toAbsolutePath();
        return absolute.endsWith(Path.of("src","main","vernac")) ? absolute.getParent().getParent().getParent() : absolute;
    }
    /** Build-written editor input: no independent dependency resolution in the LSP. */
    public static List<ModuleDescriptor> editorModules(Path sourceRoot) throws IOException {
        Path file=projectDirectory(sourceRoot).resolve("target/vernac-module-index.json");
        if(!Files.isRegularFile(file))return List.of();
        var modules=JSON.readValue(Files.readString(file),new com.fasterxml.jackson.core.type.TypeReference<List<ModuleDescriptor>>(){});
        ModuleContracts.checkDependencies(modules);return modules;
    }
    /** A navigation view, not a producer file and never compiler input. */
    public static String manifestView(ModuleDescriptor module) {
        StringBuilder text=new StringBuilder("// Read-only dependency module overview; not a local export declaration.\n// ")
            .append(module.coordinates()).append("\n// Runtime activation: ")
            .append(module.activatable()?"available":"type/port contracts only; select outbound adapters through bindings")
            .append("\nmodule ").append(module.module()).append(" {\n");
        module.exports().stream().sorted(Comparator.comparing(Definition::identity))
            .forEach(d->text.append("    export ").append(d.identity()).append("; // ").append(d.kind()).append("\n"));
        text.append("}\n\n// Internal runtime components (not importable exports):\n");
        module.components().stream().sorted(Comparator.comparing(Component::identity)).forEach(c->{
            text.append("// ").append(c.kind()).append(" ").append(c.identity());
            if(!c.dependencies().isEmpty())text.append(" requires ").append(String.join(", ",c.dependencies()));
            text.append("\n");
        });
        if(module.persistent())text.append("// Persistent owner: isolated module Flyway history; activation is required.\n");
        return text.toString();
    }
    private static void appendCollections(StringBuilder text,ModuleDescriptor module,String element) {
        boolean first=true;
        for(var c:module.exports())if(c.kind().equals("COLLECTION")&&c.element().equals(element)) {
            text.append(first?" ":", ").append(c.collectionKind().toLowerCase(Locale.ROOT)).append(" ").append(simple(c.identity()));
            first=false;
        }
    }
    /** Read-only contract-view content; source code is not used for compilation. */
    private static void appendMethodSignatures(StringBuilder text,String owner,List<Method> methods) {
        for(var method:methods)text.append("// ").append(owner).append(" behavior: public ").append(method.returns().name())
            .append(method.returns().optional()?"?":"").append(" ").append(method.name()).append("(")
            .append(method.parameters().stream().map(f->f.type().name()+(f.type().optional()?"?":"")+" "+f.name()).collect(java.util.stream.Collectors.joining(", "))).append(");\n");
    }
    private static String typeText(Type type) {
        String prefix=TypeNode.PageKind.valueOf(type.pageKind()).syntax();
        return (prefix.isEmpty()?"":prefix+" ")+type.name()+(type.optional()?"?":"");
    }
    public static String view(ModuleDescriptor module,String namespace) {
        StringBuilder text=new StringBuilder("// Dependency contract view; implementation sources are not shown.\n// "+module.module()+" "+module.coordinates()+"\nnamespace "+namespace+";\n");
        for(var term:module.vocabulary())if(term.namespace().equals(namespace))text.append(org.vernac.compiler.vocabulary.Vocabulary.source(term));
        for(var d:module.exports()) if(namespace(d.identity()).equals(namespace)&&!d.kind().equals("COLLECTION")) {
            if(d.kind().equals("ENTITY")||d.kind().equals("AGGREGATE")) {
                text.append(d.kind().toLowerCase(Locale.ROOT)).append(" ").append(simple(d.identity())).append("[").append(d.element()).append("](")
                    .append(d.fields().stream().map(f->(f.mutable()?"mut ":"")+typeText(f.type())+" "+f.name()).collect(java.util.stream.Collectors.joining(", "))).append(")");
                if(!d.methods().isEmpty()) {
                    text.append(" behavior {\n");for(var m:d.methods())text.append("    ").append(m.mode().toLowerCase(Locale.ROOT)).append(" ").append(typeText(m.returns())).append(" ").append(m.name()).append("(").append(m.parameters().stream().map(f->typeText(f.type())+" "+f.name()).collect(java.util.stream.Collectors.joining(", "))).append(") { throw new UnsupportedOperationException(); }\n");text.append("}");
                }
                appendCollections(text,module,d.identity());
                text.append(";\n");continue;
            }
            if(d.kind().equals("REPOSITORY")) {
                text.append("repository ").append(simple(d.identity())).append(" for ").append(d.element()).append(" {\n    // Public query signatures only; predicates and ordering belong to the compiled module.\n");
                for(var m:d.methods())if(!Set.of("byId","save","delete").contains(m.name()))text.append("    find ").append(m.mode().equals("CURSOR_QUERY")?"cursor ":m.mode().contains("OFFSET_QUERY")?"offset ":"").append(typeText(m.returns())).append(" ").append(m.name()).append("(").append(m.parameters().stream().map(f->typeText(f.type())+" "+f.name()).collect(java.util.stream.Collectors.joining(", "))).append(")").append(m.mode().equals("COUNTED_OFFSET_QUERY")?" counting":"").append(";\n");
                text.append("}\n");continue;
            }
            if(d.kind().equals("DOMAIN_SERVICE")) {
                text.append("service ").append(simple(d.identity())).append(" behavior {\n");
                for(var method:d.methods())text.append(" public ").append(typeText(method.returns())).append(" ").append(method.name()).append("(")
                    .append(method.parameters().stream().map(f->typeText(f.type())+" "+f.name()).collect(java.util.stream.Collectors.joining(", "))).append(") {throw new UnsupportedOperationException();}\n");
                text.append("}\n");continue;
            }
            if(d.kind().equals("USE_CASE")) {
                var execute=d.methods().stream().filter(m->m.name().equals("execute")).findFirst().orElseThrow();
                text.append("usecase ").append(simple(d.identity())).append("(").append(execute.parameters().stream().map(f->typeText(f.type())+" "+f.name()).collect(java.util.stream.Collectors.joining(", "))).append(")");
                if(!d.fields().isEmpty())text.append(" returns (").append(d.fields().stream().map(f->typeText(f.type())+" "+f.name()).collect(java.util.stream.Collectors.joining(", "))).append(")");
                else if(!execute.returns().name().equals("void"))text.append(" returns ").append(typeText(execute.returns()));
                text.append(" behavior {execute {throw new UnsupportedOperationException();}}\n");continue;
            }
            if(d.kind().equals("OUTBOUND_ADAPTER")) {
                text.append("adapter ").append(simple(d.identity())).append(" implements ").append(d.element());
                if(!d.fields().isEmpty())text.append(" uses ").append(d.fields().stream().map(f->f.type().name()+" "+f.name()).collect(java.util.stream.Collectors.joining(", ")));
                text.append(" { behavior {\n");
                for(var method:d.methods())text.append("    public ").append(method.returns().name()).append(method.returns().optional()?"?":"")
                    .append(" ").append(method.name()).append("(").append(method.parameters().stream()
                    .map(f->f.type().name()+(f.type().optional()?"?":"")+" "+f.name()).collect(java.util.stream.Collectors.joining(", ")))
                    .append(") { throw new UnsupportedOperationException(\"Read-only dependency contract view\"); }\n");
                text.append("} }\n");continue;
            }
            if(d.kind().equals("EVENT")) {
                text.append(Boolean.parseBoolean(d.javaMembers().get("@recorded"))?"recorded event ":"event ").append(simple(d.identity())).append("(").append(d.fields().stream().map(f->typeText(f.type())+" "+f.name()).collect(java.util.stream.Collectors.joining(", "))).append(");\n");continue;
            }
            if(d.kind().equals("PORT")) {
                text.append("port ").append(simple(d.identity())).append(" {\n");
                for(var method:d.methods())text.append("    ").append(method.returns().name()).append(method.returns().optional()?"?":"")
                    .append(" ").append(method.name()).append("(").append(method.parameters().stream()
                    .map(f->f.type().name()+(f.type().optional()?"?":"")+" "+f.name()).collect(java.util.stream.Collectors.joining(", "))).append(");\n");
                text.append("}\n");continue;
            }
            if(d.kind().equals("ID"))text.append("id ").append(simple(d.identity()));
            else if(d.kind().equals("ENUM"))text.append("value ").append(simple(d.identity())).append(" = ").append(String.join(" | ",d.constants()));
            else {text.append("value ").append(simple(d.identity())).append("(");text.append(d.fields().stream().map(f->f.type().name()+(f.type().optional()?"?":"")+" "+f.name()).collect(java.util.stream.Collectors.joining(", ")));text.append(")");}
            appendCollections(text,module,d.identity());
            text.append(";\n");
            appendMethodSignatures(text,simple(d.identity()),d.methods());
            for(var c:module.exports())if(c.kind().equals("COLLECTION")&&c.element().equals(d.identity()))appendMethodSignatures(text,simple(c.identity()),c.methods());
        }
        return text.toString();
    }
}
