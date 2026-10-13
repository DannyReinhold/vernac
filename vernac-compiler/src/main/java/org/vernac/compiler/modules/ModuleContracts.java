// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.modules;

import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import com.palantir.javapoet.*;
import javax.lang.model.element.Modifier;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.symbols.*;
import org.vernac.compiler.analyzer.*;
import static org.vernac.compiler.modules.ModuleDescriptor.*;

/** Publication and compatibility checks use resolved structures, never source-text hashes. */
public final class ModuleContracts {
    private ModuleContracts() { }
    public static ModuleDescriptor publish(ModuleDefinition module,ResolvedProject project,List<JavaFile> generated,
            String group,String artifact,String version,List<ModuleDescriptor> dependencies) {
        List<CompilerDiagnostic> errors=new ArrayList<>();List<Definition> definitions=new ArrayList<>();
        Set<String> exports=new TreeSet<>();module.exports().forEach(e->exports.add(e.name()));
        Map<String,TopLevelDefinition> local=new HashMap<>();Map<String,CollectionDeclaration> collections=new HashMap<>();
        Map<String,VernacSourceFile> origins=new HashMap<>();
        for(var source:project.project().sources()) if(!source.external()) for(var top:source.unit().definitions()) {
            String name=top instanceof IdDeclarationNode id?id.name():top instanceof ValueObjectNode vo?vo.name():null;
            if(name==null)errors.add(CompilerDiagnostic.error(top.location(),"The initial type-module slice supports only IDs, VOs, enums and their value collections. Functional definitions require future module activation support."));
            if(name!=null) {String identity=source.unit().namespace()+"."+name;local.put(identity,top);origins.put(identity,source);}
            for(var c:CollectionDeclaration.of(top)) {String identity=source.unit().namespace()+"."+c.name();collections.put(identity,c);origins.put(identity,source);}
        }
        for(var export:module.exports()) {
            String identity=export.name();var source=origins.get(identity);
            if(source==null) {errors.add(CompilerDiagnostic.error(export.location(),"Unknown local type-module export: "+identity+". This first slice exports IDs, VOs, enums and their collections; foreign re-exports are forbidden."));continue;}
            var symbol=project.project().symbols().find(identity).orElseThrow();String javaName=symbol.identity().namespace()+".domain."+symbol.identity().name();
            var fields=new ArrayList<Field>();var constants=new ArrayList<String>();var methods=new ArrayList<Method>();String element="",kind="";SourceLocation at;
            if(collections.containsKey(identity)) {
                var c=collections.get(identity);element=symbol.identity().namespace()+"."+c.elementName();kind=c.definition().kind().name();at=c.definition().location();
                methods.addAll(methods(c.definition().customMethods(),project));
                if(!exports.contains(element))errors.add(CompilerDiagnostic.error(export.location(),"Export "+identity+" requires an explicit export of collection element "+element));
            } else {
                var top=local.get(identity);at=top.location();
                if(top instanceof ValueObjectNode vo) {
                    for(var f:vo.fields())fields.add(new Field(f.name(),type(f.type(),project)));
                    vo.enumConstants().forEach(c->constants.add(c.name()));methods.addAll(methods(vo.methods(),project));
                }
            }
            Set<String> publicTypes=new TreeSet<>();fields.forEach(f->publicTypes.add(f.type().name()));
            methods.forEach(m->{publicTypes.add(m.returns().name());m.parameters().forEach(f->publicTypes.add(f.type().name()));});
            for(String required:publicTypes) if(local.containsKey(required)||collections.containsKey(required))
                if(!exports.contains(required))errors.add(CompilerDiagnostic.error(export.location(),"Export "+identity+" requires explicit export "+required+" in its public contract."));
            Map<String,String> members=new TreeMap<>();
            if(generated==null) continue;
            var file=generated.stream().filter(f->(f.packageName()+"."+f.typeSpec().name()).equals(javaName)).findFirst().orElseThrow();
            var spec=file.typeSpec();members.put("@type",spec.kind()+":"+spec.superclass()+":"+spec.superinterfaces());
            for(var method:spec.methodSpecs()) if(method.modifiers().contains(Modifier.PUBLIC)) {
                String key=method.name()+"("+method.parameters().stream().map(p->p.type().toString()).collect(java.util.stream.Collectors.joining(","))+")";
                String value=method.returnType()+":"+method.modifiers()+":"+method.annotations()+":"+method.parameters().stream().map(p->p.name()+":"+p.annotations()).toList()+":"+method.exceptions();
                members.put(key,value);
            }
            String path=project.project().sourceRoot().relativize(source.path()).toString().replace('\\','/');
            var definition=new Definition(identity,symbol.kind().name(),javaName,List.copyOf(fields),List.copyOf(constants),element,kind,List.copyOf(methods),members,"vernac/"+path,at.line(),at.column(),"");
            definitions.add(withFingerprint(definition));
        }
        if(!errors.isEmpty())throw new SemanticValidationException(errors);
        Set<String> used=new TreeSet<>();
        Set<String> localSources=project.project().sources().stream().filter(s->!s.external()).map(s->s.path().toString()).collect(java.util.stream.Collectors.toSet());
        project.types().forEach((node,t)->{if(localSources.contains(node.location().sourceName()) && t instanceof ResolvedType.Declared d)used.add(d.symbol().identity().qualifiedName());});
        boolean expanded;
        do { expanded=false;for(var dependency:dependencies)for(var d:dependency.exports())if(used.contains(d.identity())) {
            for(var f:d.fields())expanded|=used.add(f.type().name());
            if(!d.element().isBlank())expanded|=used.add(d.element());
            for(var m:d.methods()){expanded|=used.add(m.returns().name());for(var f:m.parameters())expanded|=used.add(f.type().name());}
        }}while(expanded);
        // Explicit Java imports are also contracts; arbitrary Java body analysis is not claimed.
        for(var source:project.project().sources()) if(!source.external()) for(var top:source.unit().definitions())
            if(top instanceof ValueObjectNode vo) for(var i:vo.javaImports()) for(var dep:dependencies) for(var d:dep.exports()) if(d.javaName().equals(i.target()))used.add(d.identity());
        List<Requirement> requirements=new ArrayList<>();
        for(var dependency:dependencies) {
            List<Definition> required=dependency.exports().stream().filter(d->used.contains(d.identity())).toList();
            if(!required.isEmpty())requirements.add(new Requirement(dependency.module(),dependency.groupId(),dependency.artifactId(),dependency.version(),required));
        }
        definitions.sort(Comparator.comparing(Definition::identity));requirements.sort(Comparator.comparing(Requirement::module));
        Set<String> internal=new TreeSet<>(local.keySet());internal.addAll(collections.keySet());internal.removeAll(exports);
        return new ModuleDescriptor(1,CONTRACT,module.name(),group,artifact,version,definitions,requirements,List.copyOf(internal));
    }
    public static void validateExports(ModuleDefinition definition,ResolvedProject project) {
        publish(definition,project,null,"editor","editor","editor",List.of());
    }
    private static List<Method> methods(List<MethodNode> source,ResolvedProject project) {
        return source.stream().filter(m->m.accessModifier().equals("public")).map(m->new Method(m.name(),type(m.returnType(),project),m.parameters().stream().map(p->new Field(p.name(),type(p.type(),project))).toList()))
            .sorted(Comparator.comparing(m->m.name()+m.parameters())).toList();
    }
    private static Type type(TypeNode node,ResolvedProject project) {
        var t=project.typeOf(node);String name=t instanceof ResolvedType.Declared d?d.symbol().identity().qualifiedName():node.name();
        return new Type(name,node.isOptional());
    }
    public static String fingerprint(Definition d) {
        try {
            Object contract=List.of(CONTRACT,d.identity(),d.kind(),d.javaName(),d.fields(),d.constants(),d.element(),d.collectionKind(),d.methods(),new TreeMap<>(d.javaMembers()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsString(contract).getBytes(StandardCharsets.UTF_8)));
        } catch(Exception e) {throw new IllegalStateException("Could not fingerprint public module contract",e);}
    }
    private static Definition withFingerprint(Definition d) {return new Definition(d.identity(),d.kind(),d.javaName(),d.fields(),d.constants(),d.element(),d.collectionKind(),d.methods(),d.javaMembers(),d.source(),d.line(),d.column(),fingerprint(d));}
    public static Optional<String> difference(Definition expected,Definition actual) {
        if(expected.fingerprint().equals(actual.fingerprint()))return Optional.empty();
        if(!expected.kind().equals(actual.kind())||!expected.javaName().equals(actual.javaName()))return Optional.of("Changed type category or Java identity");
        if(!expected.fields().equals(actual.fields())) {
            for(var f:expected.fields()) {var match=actual.fields().stream().filter(a->a.name().equals(f.name())).findFirst();if(match.isEmpty())return Optional.of("Removed field: "+f.name());if(!match.get().equals(f))return Optional.of("Changed field: "+f.name()+" from "+f.type()+" to "+match.get().type());}
            return Optional.of("Changed field structure/order: "+actual.fields());
        }
        if(!expected.constants().equals(actual.constants()))return Optional.of("Changed enum constants");
        if(!expected.element().equals(actual.element())||!expected.collectionKind().equals(actual.collectionKind()))return Optional.of("Changed collection contract");
        for(var e:expected.javaMembers().entrySet()) if(!Objects.equals(e.getValue(),actual.javaMembers().get(e.getKey())))return Optional.of("Changed or removed Java member: "+e.getKey());
        for(var key:actual.javaMembers().keySet()) if(!expected.javaMembers().containsKey(key)) {
            String name=key.split("\\(",2)[0];
            if(expected.javaMembers().keySet().stream().anyMatch(k->k.startsWith(name+"(")))return Optional.of("Added overload requires consumer rebuild: "+key);
        }
        if(!actual.methods().containsAll(expected.methods()))return Optional.of("Changed public behavior signature");
        return Optional.empty();
    }
    public static void checkDependencies(List<ModuleDescriptor> modules) {
        Map<String,ModuleDescriptor> available=new TreeMap<>();Set<String> identities=new HashSet<>();
        for(var module:modules) {
            if(!org.vernac.language.VernacNames.isNamespace(module.module())||!module.module().contains("."))throw new IllegalArgumentException("Invalid module identity: "+module.module());
            if(available.putIfAbsent(module.module(),module)!=null)throw new IllegalArgumentException("Duplicate module identity: "+module.module());
            for(var d:module.exports()) {
                int dot=d.identity().lastIndexOf('.');
                if(dot<1||!org.vernac.language.VernacNames.isNamespace(d.identity())||!d.javaName().equals(d.identity().substring(0,dot)+".domain."+d.identity().substring(dot+1)))throw new IllegalArgumentException("Invalid exported type/Java identity: "+d.identity());
                if(!Set.of("ID","VALUE_OBJECT","ENUM","COLLECTION").contains(d.kind()))throw new IllegalArgumentException("Unsupported type-module export kind: "+d.kind());
                if(!identities.add(d.identity()))throw new IllegalArgumentException("Duplicate exported definition: "+d.identity());
                if(!fingerprint(d).equals(d.fingerprint()))throw new IllegalArgumentException("Invalid contract fingerprint: "+d.identity());
            }
            for(String internal:module.internalTypes())if(!identities.add(internal))throw new IllegalArgumentException("Duplicate internal/public dependency definition: "+internal);
        }
        for(var module:modules) for(var requirement:module.externalContracts()) {
            var provider=available.get(requirement.module());
            if(provider==null)throw new IllegalArgumentException("Module "+module.module()+" requires missing module "+requirement.module()+" ("+requirement.groupId()+":"+requirement.artifactId()+")");
            if(!provider.groupId().equals(requirement.groupId())||!provider.artifactId().equals(requirement.artifactId()))throw new IllegalArgumentException("Changed provider artifact for module "+requirement.module());
            for(var expected:requirement.definitions()) {
                var actual=provider.exports().stream().filter(d->d.identity().equals(expected.identity())).findFirst();
                var diff=actual.isEmpty()?Optional.of("Missing exported definition"):difference(expected,actual.get());
                if(diff.isPresent())throw new IllegalArgumentException("Module "+module.module()+" requires a different contract for "+expected.identity()+". Built against: "+requirement.version()+"; resolved: "+provider.coordinates()+". "+diff.get()+". Use a compatible dependency or rebuild "+module.module()+".");
            }
        }
    }
}
