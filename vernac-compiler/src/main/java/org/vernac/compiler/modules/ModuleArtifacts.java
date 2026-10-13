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
                Path file=path.resolve(RESOURCE);if(Files.isRegularFile(file))result.add(JSON.readValue(Files.readString(file),ModuleDescriptor.class));
            } else if(path.toString().endsWith(".jar")) try(var jar=new JarFile(path.toFile())) {
                var entry=jar.getJarEntry(RESOURCE);
                if(entry!=null)try(var input=jar.getInputStream(entry)) {if(entry.getSize()>8_000_000)throw new IOException("Oversized Vernac module metadata: "+path);result.add(JSON.readValue(input,ModuleDescriptor.class));}
            }
        }
        ModuleContracts.checkDependencies(result);return List.copyOf(result);
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
                    else throw new IllegalArgumentException("Unsupported type-module definition: "+d.identity());
                }
                sources.add(new VernacSourceFile(path,new CompilationUnitNode(at,entry.getKey(),List.of(),definitions),true));
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
    private static List<FieldNode> fields(List<Field> source,SourceLocation at) {return source.stream().map(f->new FieldNode(at,type(f.type(),at),f.name())).toList();}
    private static TypeNode type(Type source,SourceLocation at) {return new TypeNode(at,source.name(),List.of(),source.optional());}
    private static List<MethodNode> methods(List<Method> source,SourceLocation at) {return source.stream().map(m->new MethodNode(at,"public",type(m.returns(),at),m.name(),fields(m.parameters(),at),"throw new UnsupportedOperationException();")).toList();}
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
    /** Read-only contract-view content; source code is not used for compilation. */
    private static void appendMethodSignatures(StringBuilder text,String owner,List<Method> methods) {
        for(var method:methods)text.append("// ").append(owner).append(" behavior: public ").append(method.returns().name())
            .append(method.returns().optional()?"?":"").append(" ").append(method.name()).append("(")
            .append(method.parameters().stream().map(f->f.type().name()+(f.type().optional()?"?":"")+" "+f.name()).collect(java.util.stream.Collectors.joining(", "))).append(");\n");
    }
    public static String view(ModuleDescriptor module,String namespace) {
        StringBuilder text=new StringBuilder("// Dependency contract view; implementation sources are not shown.\n// "+module.module()+" "+module.coordinates()+"\nnamespace "+namespace+";\n");
        for(var d:module.exports()) if(namespace(d.identity()).equals(namespace)&&!d.kind().equals("COLLECTION")) {
            if(d.kind().equals("ID"))text.append("id ").append(simple(d.identity()));
            else if(d.kind().equals("ENUM"))text.append("value ").append(simple(d.identity())).append(" = ").append(String.join(" | ",d.constants()));
            else {text.append("value ").append(simple(d.identity())).append("(");text.append(d.fields().stream().map(f->f.type().name()+(f.type().optional()?"?":"")+" "+f.name()).collect(java.util.stream.Collectors.joining(", ")));text.append(")");}
            for(var c:module.exports())if(c.kind().equals("COLLECTION")&&c.element().equals(d.identity()))text.append(" ").append(c.collectionKind().toLowerCase(Locale.ROOT)).append(" ").append(simple(c.identity()));
            text.append(";\n");
            appendMethodSignatures(text,simple(d.identity()),d.methods());
            for(var c:module.exports())if(c.kind().equals("COLLECTION")&&c.element().equals(d.identity()))appendMethodSignatures(text,simple(c.identity()),c.methods());
        }
        return text.toString();
    }
}
