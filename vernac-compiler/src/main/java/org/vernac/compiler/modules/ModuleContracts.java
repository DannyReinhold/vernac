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
            String name=top instanceof IdDeclarationNode id?id.name():top instanceof ValueObjectNode vo?vo.name():top instanceof PortNode port?port.name():top instanceof OutboundAdapterNode adapter?adapter.name():top instanceof DomainServiceNode service?service.name():top instanceof UseCaseNode usecase?usecase.name():top instanceof EntityNode entity?entity.name():top instanceof AggregateNode aggregate?aggregate.name():top instanceof RepositoryNode repository?repository.name():top instanceof EventNode event?event.name():top instanceof ListenerNode listener?listener.name():top instanceof InboundHttpAdapterNode inbound?inbound.name():null;
            if(name==null)errors.add(CompilerDiagnostic.error(top.location(),"Unsupported module definition."));
            if(name!=null) {String identity=source.unit().namespace()+"."+name;local.put(identity,top);origins.put(identity,source);}
            for(var c:CollectionDeclaration.of(top)) {String identity=source.unit().namespace()+"."+c.name();collections.put(identity,c);origins.put(identity,source);}
        }
        for(var export:module.exports()) {
            String identity=export.name();var source=origins.get(identity);
            if(source==null) {errors.add(CompilerDiagnostic.error(export.location(),"Unknown local type-module export: "+identity+". This slice exports reviewed domain, repository, port, adapter and functional contracts; foreign re-exports are forbidden."));continue;}
            if(local.get(identity) instanceof InboundHttpAdapterNode) {errors.add(CompilerDiagnostic.error(export.location(),"Inbound HTTP adapters are internal entrypoints, not exported callable types: "+identity+". Activate the module to register its routes."));continue;}
            if(local.get(identity) instanceof ListenerNode) {errors.add(CompilerDiagnostic.error(export.location(),"Listeners are internal runtime receivers, not exported APIs: "+identity+". Export the event contract and activate the receiving module."));continue;}
            var symbol=project.project().symbols().find(identity).orElseThrow();String javaName=symbol.identity().namespace()+(symbol.kind()==TypeSymbol.Kind.OUTBOUND_ADAPTER?".adapter.outbound.":symbol.kind()==TypeSymbol.Kind.USE_CASE?".usecase.":".domain.")+symbol.identity().name();
            var fields=new ArrayList<Field>();var constants=new ArrayList<String>();var methods=new ArrayList<Method>();String element="",kind="";SourceLocation at;
            if(collections.containsKey(identity)) {
                var c=collections.get(identity);element=symbol.identity().namespace()+"."+c.elementName();kind=c.definition().kind().name();at=c.definition().location();
                methods.addAll(methods(c.definition().customMethods(),project));
                if(!exports.contains(element))errors.add(CompilerDiagnostic.error(export.location(),"Export "+identity+" requires an explicit export of collection element "+element));
            } else {
                var top=local.get(identity);at=top.location();
                var domain=MutableDomain.of(top);
                if(domain.isPresent()) {
                    element=type(domain.get().id().type(),project).name();
                    for(var f:domain.get().fields())fields.add(new Field(f.name(),type(f.type(),project),f.isMutable()));
                    methods.addAll(methods(domain.get().methods(),project));
                }
                if(top instanceof RepositoryNode repository) {
                    var target=(ResolvedType.Declared)project.scopes().get(source.path()).resolve(repository.aggregateName(),repository.location()).type().orElseThrow();
                    element=target.symbol().identity().qualifiedName();
                    var aggregate=(AggregateNode)local.get(element);
                    if(aggregate==null)throw new IllegalArgumentException("A module cannot publish a repository for a foreign aggregate: "+element);
                    var id=type(aggregate.idDefinition().type(),project);
                    methods.add(new Method("byId",new Type(element,false),List.of(new Field("id",id))));
                    methods.add(new Method("save",new Type(element,false),List.of(new Field("aggregate",new Type(element,false)))));
                    methods.add(new Method("delete",new Type("void",false),List.of(new Field("aggregate",new Type(element,false)))));
                    for(var query:repository.methods())methods.add(new Method(query.name(),type(query.returnType(),project),query.parameters().stream().map(f->new Field(f.name(),type(f.type(),project))).toList(),query.cursor()?"CURSOR_QUERY":query.offset()?(query.counting()?"COUNTED_OFFSET_QUERY":"OFFSET_QUERY"):"DEFAULT"));
                }
                if(top instanceof DomainServiceNode service)methods.addAll(methods(service.methods().stream().map(ServiceMethodNode::method).toList(),project));
                if(top instanceof UseCaseNode usecase) {
                    for(var f:usecase.resultFields())fields.add(new Field(f.name(),type(f.type(),project)));
                    Type returns=!usecase.resultFields().isEmpty()?new Type("@result",false):usecase.resultType().map(t->type(t,project)).orElse(new Type("void",false));
                    methods.add(new Method("execute",returns,usecase.parameters().stream().map(f->new Field(f.name(),type(f.type(),project))).toList()));
                }
                if(top instanceof OutboundAdapterNode adapter) {
                    element=type(adapter.portType(),project).name();

                    methods.addAll(methods(adapter.methods().stream().map(ServiceMethodNode::method).toList(),project));
                    if(local.containsKey(element)&&!exports.contains(element))errors.add(CompilerDiagnostic.error(export.location(),"Export "+identity+" requires explicit export "+element+" for its implemented port."));
                }
                if(top instanceof PortNode port)methods.addAll(methods(port.methods().stream().map(PortMethodNode::method).toList(),project));
                if(top instanceof EventNode event)for(var field:event.fields())fields.add(new Field(field.name(),type(field.type(),project)));
                if(top instanceof ValueObjectNode vo) {
                    for(var f:vo.fields())fields.add(new Field(f.name(),type(f.type(),project)));
                    vo.enumConstants().forEach(c->constants.add(c.name()));methods.addAll(methods(vo.methods(),project));
                }
            }
            Set<String> publicTypes=new TreeSet<>();if(!element.isBlank())publicTypes.add(element);fields.forEach(f->publicTypes.add(f.type().name()));
            methods.forEach(m->{publicTypes.add(m.returns().name());m.parameters().forEach(f->publicTypes.add(f.type().name()));});
            for(String required:publicTypes) if(local.containsKey(required)||collections.containsKey(required))
                if(!exports.contains(required))errors.add(CompilerDiagnostic.error(export.location(),"Export "+identity+" requires explicit export "+required+" in its public contract."));
            Map<String,String> members=new TreeMap<>();
            if(generated==null) continue;
            var file=generated.stream().filter(f->(f.packageName()+"."+f.typeSpec().name()).equals(javaName)).findFirst().orElseThrow();
            var declared=local.get(identity);
            if(declared instanceof EventNode event) {
                members.put("@recorded",Boolean.toString(event.recorded()));
                String storage=symbol.identity().namespace()+".adapter.outbound.jdbc.event.Jdbc"+event.name()+"Events";
                members.put("@storage",Boolean.toString(generated.stream().anyMatch(f->(f.packageName()+"."+f.typeSpec().name()).equals(storage))));
            }
            if(declared instanceof OutboundAdapterNode adapter)members.put("@configuration",adapter.configuration().stream().map(c->c.type()+":"+c.name()+":"+c.property()).toList().toString());
            var spec=file.typeSpec();members.put("@type",spec.kind()+":"+spec.superclass()+":"+spec.superinterfaces());
            for(var method:spec.methodSpecs()) if(method.modifiers().contains(Modifier.PUBLIC)&&!(Set.of("DOMAIN_SERVICE","USE_CASE").contains(symbol.kind().name())&&method.isConstructor())) {
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
        List<Component> components=new ArrayList<>();
        for(var entry:local.entrySet()) {
            var top=entry.getValue();var symbol=project.project().symbols().find(entry.getKey()).orElseThrow();
            List<FieldNode> componentDependencies=top instanceof DomainServiceNode service?service.injections():top instanceof UseCaseNode usecase?usecase.injections():top instanceof OutboundAdapterNode adapter?adapter.dependencies():top instanceof RepositoryNode?List.of():top instanceof ListenerNode listener?listener.operation().injections():top instanceof InboundHttpAdapterNode inbound?inbound.dependencies():null;
            if(top instanceof EventNode event) {
                String javaName=symbol.identity().namespace()+".adapter.outbound.jdbc.event.Jdbc"+event.name()+"Events";
                if(generated!=null && generated.stream().anyMatch(f->(f.packageName()+"."+f.typeSpec().name()).equals(javaName)))
                    components.add(new Component("@storage:"+entry.getKey(),"EVENT_STORAGE",javaName,List.of()));
            }
            if(componentDependencies==null)continue;
            String javaName=top instanceof RepositoryNode?symbol.identity().namespace()+".adapter.outbound.jdbc.Jdbc"+symbol.identity().name():org.vernac.compiler.symbols.JavaTypeNames.canonicalName(new ResolvedType.Declared(symbol));
            var required=new ArrayList<>(componentDependencies.stream().map(f->type(f.type(),project).name()).toList());
            if(top instanceof ListenerNode listener && listener.deferred())required.add("@storage:"+type(listener.eventType(),project).name());
            components.add(new Component(entry.getKey(),top instanceof ListenerNode listener && listener.deferred()?"DEFERRED_LISTENER":symbol.kind().name(),javaName,required));
            if(top instanceof InboundHttpAdapterNode)components.add(new Component("@http-errors:"+entry.getKey(),"INBOUND_HTTP_ADVICE",javaName+"Errors",List.of(entry.getKey())));
        }
        if(generated!=null)for(var component:components)for(String dependency:component.dependencies())if(dependency.startsWith("@storage:")&&components.stream().noneMatch(c->c.identity().equals(dependency))&&dependencies.stream().flatMap(m->m.components().stream()).noneMatch(c->c.identity().equals(dependency)))
            errors.add(CompilerDiagnostic.error(local.get(component.identity()).location(),"Deferred listener requires prepared event storage "+dependency+". Rebuild the producer with reviewed event migrations."));
        if(!errors.isEmpty())throw new SemanticValidationException(errors);
        return new ModuleDescriptor(1,CONTRACT,module.name(),group,artifact,version,definitions,requirements,List.copyOf(internal),components,components.stream().anyMatch(c->Set.of("REPOSITORY","EVENT_STORAGE","DEFERRED_LISTENER").contains(c.kind())));
    }
    public static void validateExports(ModuleDefinition definition,ResolvedProject project) {
        publish(definition,project,null,"editor","editor","editor",List.of());
    }
    private static List<Method> methods(List<MethodNode> source,ResolvedProject project) {
        return source.stream().filter(m->m.accessModifier().equals("public")).map(m->new Method(m.name(),type(m.returnType(),project),m.parameters().stream().map(p->new Field(p.name(),type(p.type(),project))).toList(),m.mode().name()))
            .sorted(Comparator.comparing(m->m.name()+m.parameters())).toList();
    }
    private static Type type(TypeNode node,ResolvedProject project) {
        var t=project.typeOf(node);String name=t instanceof ResolvedType.Declared d?d.symbol().identity().qualifiedName():t instanceof ResolvedType.Page page?page.collection().symbol().identity().qualifiedName():node.name();
        return new Type(name,node.isOptional(),node.pageKind().name());
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
            for(var f:expected.fields()) {var match=actual.fields().stream().filter(a->a.name().equals(f.name())).findFirst();if(match.isEmpty())return Optional.of("Removed field: "+f.name());if(!match.get().equals(f))return Optional.of("Changed field: "+f.name()+" from "+f.type()+" (mutable="+f.mutable()+") to "+match.get().type()+" (mutable="+match.get().mutable()+")");}
            return Optional.of("Changed field structure/order: "+actual.fields());
        }
        if(Set.of("PORT","REPOSITORY").contains(expected.kind())&&!expected.methods().equals(actual.methods()))return Optional.of("Changed port/repository operations require implementation rebuild: "+expected.identity());
        if(!expected.constants().equals(actual.constants()))return Optional.of("Changed enum constants");
        if(!expected.element().equals(actual.element())||!expected.collectionKind().equals(actual.collectionKind()))return Optional.of("Changed identity, aggregate target or collection contract");
        for(var e:expected.javaMembers().entrySet()) if(!Objects.equals(e.getValue(),actual.javaMembers().get(e.getKey())))return Optional.of("Changed or removed Java member: "+e.getKey());
        for(var key:actual.javaMembers().keySet()) if(!expected.javaMembers().containsKey(key)) {
            String name=key.split("\\(",2)[0];
            if(expected.javaMembers().keySet().stream().anyMatch(k->k.startsWith(name+"(")))return Optional.of("Added overload requires consumer rebuild: "+key);
        }
        if(!actual.methods().containsAll(expected.methods()))return Optional.of("Changed public behavior signature or read/modify/query mode");
        return Optional.empty();
    }
    public static void checkDependencies(List<ModuleDescriptor> modules) {
        Map<String,ModuleDescriptor> available=new TreeMap<>();Set<String> identities=new HashSet<>();
        for(var module:modules) {
            if(!org.vernac.language.VernacNames.isNamespace(module.module())||!module.module().contains("."))throw new IllegalArgumentException("Invalid module identity: "+module.module());
            if(available.putIfAbsent(module.module(),module)!=null)throw new IllegalArgumentException("Duplicate module identity: "+module.module());
            for(var d:module.exports()) {
                int dot=d.identity().lastIndexOf('.');
                if(dot<1||!org.vernac.language.VernacNames.isNamespace(d.identity())||!d.javaName().equals(d.identity().substring(0,dot)+(d.kind().equals("OUTBOUND_ADAPTER")?".adapter.outbound.":d.kind().equals("USE_CASE")?".usecase.":".domain.")+d.identity().substring(dot+1)))throw new IllegalArgumentException("Invalid exported type/Java identity: "+d.identity());
                if(!Set.of("ID","VALUE_OBJECT","ENUM","COLLECTION","PORT","OUTBOUND_ADAPTER","DOMAIN_SERVICE","USE_CASE","ENTITY","AGGREGATE","REPOSITORY","EVENT").contains(d.kind()))throw new IllegalArgumentException("Unsupported type-module export kind: "+d.kind());
                if(!identities.add(d.identity()))throw new IllegalArgumentException("Duplicate exported definition: "+d.identity());
                if(!fingerprint(d).equals(d.fingerprint()))throw new IllegalArgumentException("Invalid contract fingerprint: "+d.identity());
            }
            if(module.components().stream().anyMatch(c->c.kind().equals("REPOSITORY"))&&!module.persistent())throw new IllegalArgumentException("Repository module must declare persistence ownership: "+module.module());
            Set<String> componentIds=new HashSet<>();
            for(var component:module.components()) {
                if(!componentIds.add(component.identity())||!Set.of("USE_CASE","DOMAIN_SERVICE","OUTBOUND_ADAPTER","REPOSITORY","LISTENER","DEFERRED_LISTENER","EVENT_STORAGE","INBOUND_HTTP_ADAPTER","INBOUND_HTTP_ADVICE").contains(component.kind()))throw new IllegalArgumentException("Invalid/duplicate runtime component: "+component.identity());
                if(component.kind().equals("INBOUND_HTTP_ADVICE")) {
                    String identity=component.identity().startsWith("@http-errors:")?component.identity().substring(13):"";
                    int dot=identity.lastIndexOf('.');
                    if(dot<1 || !module.internalTypes().contains(identity)
                        || !component.javaName().equals(identity.substring(0,dot)+".adapter.inbound.http."+identity.substring(dot+1)+"Errors")
                        || !component.dependencies().equals(List.of(identity))
                        || module.components().stream().noneMatch(c->c.identity().equals(identity)&&c.kind().equals("INBOUND_HTTP_ADAPTER")))throw new IllegalArgumentException("Invalid module HTTP advice ownership: "+component.identity());
                    continue;
                }
                if(component.kind().equals("EVENT_STORAGE")) {
                    String identity=component.identity().startsWith("@storage:")?component.identity().substring(9):"";
                    int dot=identity.lastIndexOf('.');
                    if(!module.persistent() || dot<1 || !(module.internalTypes().contains(identity)||module.exports().stream().anyMatch(d->d.identity().equals(identity)&&d.kind().equals("EVENT")&&"true".equals(d.javaMembers().get("@storage"))))
                            || !component.javaName().equals(identity.substring(0,dot)+".adapter.outbound.jdbc.event.Jdbc"+identity.substring(dot+1)+"Events"))throw new IllegalArgumentException("Invalid event storage ownership: "+component.identity());
                    continue;
                }
                String suffix=component.kind().equals("REPOSITORY")?".adapter.outbound.jdbc.Jdbc":component.kind().equals("USE_CASE")?".usecase.":component.kind().equals("OUTBOUND_ADAPTER")?".adapter.outbound.":component.kind().equals("INBOUND_HTTP_ADAPTER")?".adapter.inbound.http.":Set.of("LISTENER","DEFERRED_LISTENER").contains(component.kind())?".adapter.inbound.event.":".domain.";
                if(!org.vernac.language.VernacNames.isNamespace(component.identity()) || (!module.internalTypes().contains(component.identity()) && module.exports().stream().noneMatch(d->d.identity().equals(component.identity())&&(d.kind().equals(component.kind())||d.kind().equals("LISTENER")&&component.kind().equals("DEFERRED_LISTENER"))&&(component.kind().equals("REPOSITORY") || d.javaName().equals(component.javaName())))))throw new IllegalArgumentException("Runtime component is not owned by module: "+component.identity());
                int dot=component.identity().lastIndexOf('.');
                if(dot<1||!component.javaName().equals(component.identity().substring(0,dot)+suffix+component.identity().substring(dot+1)))throw new IllegalArgumentException("Invalid runtime component Java identity: "+component.identity());
            }
            for(var component:module.components())if(component.kind().equals("INBOUND_HTTP_ADAPTER")&&!componentIds.contains("@http-errors:"+component.identity()))throw new IllegalArgumentException("Missing module HTTP advice: "+component.identity());
            for(var d:module.exports())if(d.kind().equals("EVENT")&&"true".equals(d.javaMembers().get("@storage"))&&!componentIds.contains("@storage:"+d.identity()))throw new IllegalArgumentException("Missing event storage component: "+d.identity());
            for(var d:module.exports())if(Set.of("USE_CASE","DOMAIN_SERVICE","OUTBOUND_ADAPTER","REPOSITORY").contains(d.kind())&&!componentIds.contains(d.identity()))throw new IllegalArgumentException("Missing runtime component: "+d.identity());
            for(String internal:module.internalTypes())if(!identities.add(internal))throw new IllegalArgumentException("Duplicate internal/public dependency definition: "+internal);
        }
        Set<String> storageIds=modules.stream().flatMap(m->m.components().stream()).filter(c->c.kind().equals("EVENT_STORAGE")).map(Component::identity).collect(java.util.stream.Collectors.toSet());
        for(var module:modules)for(var component:module.components())for(String dependency:component.dependencies())if(dependency.startsWith("@storage:")&&!storageIds.contains(dependency))throw new IllegalArgumentException("Missing producer event storage "+dependency+" for "+component.identity());
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
