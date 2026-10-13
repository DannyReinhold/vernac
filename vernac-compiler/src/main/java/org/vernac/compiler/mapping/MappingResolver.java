// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.mapping;

import java.util.*;
import com.palantir.javapoet.*;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.dto.ResolvedSchema;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.symbols.*;
import org.vernac.language.VernacNames;
import static org.vernac.compiler.mapping.ResolvedMapping.*;

/** Validates explicit source paths and builds a closed conversion plan. */
public final class MappingResolver {
    private final VernacProject project;
    private final Map<TypeNode,ResolvedType> domainTypes;
    private final List<CompilerDiagnostic> errors;
    private FileTypeScope scope;
    private String namespace;
    private String adapterName;
    private String dtoPackage;
    private final Map<String, Type> extraTypes = new LinkedHashMap<>();
    private final Map<String, List<Field>> extraFields = new LinkedHashMap<>();
    private final Map<String,ResolvedSchema> schemas=new LinkedHashMap<>();
    private final Map<String,ResolvedMapping> signatures=new LinkedHashMap<>();
    private final Set<String> dependencies=new LinkedHashSet<>();
    private final Map<String,Set<String>> graph=new LinkedHashMap<>();
    public MappingResolver(VernacProject project,Map<TypeNode,ResolvedType> domainTypes,List<CompilerDiagnostic> errors) {
        this.project=project;this.domainTypes=domainTypes;this.errors=errors;
    }
    public List<ResolvedMapping> resolve(OutboundAdapterNode adapter,String namespace,FileTypeScope scope,List<ResolvedSchema> schemas) {
        return resolve(adapter.name(), adapter.mappings(), namespace, scope, schemas, namespace + ".adapter.outbound.dto");
    }
    public List<ResolvedMapping> resolve(String name, List<MappingNode> declarations, String namespace, FileTypeScope scope, List<ResolvedSchema> schemas, String dtoPackage) {
        context(name,namespace,scope,schemas,dtoPackage);
        for(var m:declarations) {
            if(!VernacNames.isIdentifier(m.name()) || m.name().startsWith("__") || Set.of("getClass","wait","notify","notifyAll","equals","hashCode","toString","clone","finalize").contains(m.name()))
                error(m.location(),"Invalid or reserved mapping name: "+m.name());
            Type target=resolveType(m.targetType()), input=resolveType(m.sourceType());
            if(target!=null && input!=null && signatures.putIfAbsent(m.name(),new ResolvedMapping(m,target,input,List.of()))!=null)
                error(m.location(),"Duplicate mapping name: "+m.name()+". Mapping overloads are not supported.");
        }
        List<ResolvedMapping> result=new ArrayList<>();
        for(var m:signatures.values()) {
            dependencies.clear();
            if(m.source().javaBody().isPresent() || m.source().implementation().isPresent()) {
                validateJava(m);result.add(m);graph.put(m.source().name(),Set.of());continue;
            }
            if(m.target().kind()==Kind.PAGE || m.target().kind()==Kind.RESULT || m.target().kind()==Kind.SCALAR || m.target().kind()==Kind.SEQUENCE || isEnum(m.target())) {
                error(m.source().location(),"Declarative mapping targets require a DTO, value object or ID. Use a Java mapping for scalar, enum or collection roots.");continue;
            }
            List<Field> targetFields=fields(m.target());
            Map<String,MappingAssignmentNode> entries=new LinkedHashMap<>();
            for(var assignment:m.source().assignments()) {
                if(entries.putIfAbsent(assignment.target(),assignment)!=null)error(assignment.location(),"Duplicate mapping assignment: "+assignment.target());
                if(targetFields.stream().noneMatch(f->f.name().equals(assignment.target())))error(assignment.location(),"Unknown target field: "+assignment.target());
            }
            List<Assignment> assignments=new ArrayList<>();
            for(var target:targetFields) {
                var entry=entries.get(target.name());
                if(entry==null) {
                    if(!target.optional() && !target.patch()) error(m.source().location(),"Required target field is not mapped: "+target.name());
                    assignments.add(new Assignment(target,null,null));continue;
                }
                Path path=path(m.input(),entry);
                if(path==null)continue;
                if(path.patch()!=target.patch()) { error(entry.location(),"Ordinary values and PATCH fields cannot be mapped implicitly. Use a Java mapping for explicit update intent.");continue; }
                if(path.optional() && !target.optional()) {
                    error(entry.location(),path.patch()?"A clearable PATCH source cannot target a non-clearable field.":"An optional source cannot satisfy required target field '"+target.name()+"'. Use a Java mapping with explicit absence handling.");continue;
                }
                Conversion conversion=entry.mapping().isPresent()?named(path.result(),target.type(),entry.mapping().get(),entry.location()):automatic(path.result(),target.type(),new HashSet<>());
                if(conversion==null) error(entry.location(),"No exact conversion from "+path.result().identity()+" to "+target.type().identity()+". Specify using <mapping> or a Java mapping; no normalization or numeric conversion is inferred.");
                else assignments.add(new Assignment(target,path,conversion));
            }
            result.add(new ResolvedMapping(m.source(),m.target(),m.input(),assignments));
            graph.put(m.source().name(),Set.copyOf(dependencies));
        }
        for(var m:signatures.values())cycle(m.source().name(),new LinkedHashSet<>(),new HashSet<>());
        return List.copyOf(result);
    }
    /** Reuses the same scope and field model for tolerant editor queries. */
    public MappingResolver context(OutboundAdapterNode adapter,String namespace,FileTypeScope scope,List<ResolvedSchema> schemas) {
        return context(adapter.name(), namespace, scope, schemas, namespace + ".adapter.outbound.dto");
    }
    public MappingResolver context(String name,String namespace,FileTypeScope scope,List<ResolvedSchema> schemas,String dtoPackage) {
        this.adapterName=name;this.dtoPackage=dtoPackage;this.namespace=namespace;this.scope=scope;this.schemas.clear();
        schemas.forEach(s->this.schemas.put(s.source().name(),s));return this;
    }
    private void validateJava(ResolvedMapping m) {
        Map<String,String> names=new HashMap<>();
        for(var t:List.of(m.input(),m.target()))names.put(t.javaType().toString().substring(t.javaType().toString().lastIndexOf('.')+1),t.javaType().toString());
        for(var imp:m.source().javaImports()) {
            if(!qualified(imp.target())) { error(imp.location(),"Java mapping imports require fully qualified class names.");continue; }
            String name=imp.target().substring(imp.target().lastIndexOf('.')+1);
            String old=names.putIfAbsent(name,imp.target());
            if(old!=null && !old.equals(imp.target()))error(imp.location(),"Java mapping import conflicts with mapping type: "+name);
            var visible=scope.resolve(name,imp.location()).type().orElse(null);
            if(visible!=null && !JavaTypeNames.canonicalName(visible).equals(imp.target()))error(imp.location(),"Java mapping import conflicts with visible Vernac type: "+name);
        }
        m.source().implementation().ifPresent(name->{if(!qualified(name))error(m.source().location(),"implemented by requires a fully qualified Java class name.");});
    }
    private boolean qualified(String name) {return name.contains(".") && Arrays.stream(name.split("\\.")).allMatch(VernacNames::isIdentifier);}
    private void cycle(String name,Set<String> active,Set<String> done) {
        if(active.contains(name)) {error(signatures.get(name).source().location(),"Cyclic mapping invocation: "+String.join(" -> ",active)+" -> "+name);return;}
        if(!done.add(name))return;active.add(name);
        for(String next:graph.getOrDefault(name,Set.of()))cycle(next,active,done);
        active.remove(name);
    }
    public Conversion named(Type from,Type to,String name,SourceLocation at) {
        var m=signatures.get(name);
        if(m==null) {error(at,"Unknown adapter-local mapping: "+name);return null;}
        if(same(from,m.input()) && same(to,m.target())) {dependencies.add(name);return new Named(name);}
        if(from.kind()==Kind.PAGE && to.kind()==Kind.PAGE && to.pageTransport() && from.pageKind()==to.pageKind()
                && same(from.element(),m.input()) && same(to.element(),m.target())) {
            dependencies.add(name);return new Pages(to,new Named(name));
        }
        if(from.kind()==Kind.SEQUENCE && to.kind()==Kind.SEQUENCE && !(to.set() && !from.set()) && same(from.element(),m.input()) && same(to.element(),m.target())) {
            dependencies.add(name);return new Elements(to,new Named(name));
        }
        error(at,"Mapping '"+name+"' has incompatible source/target types.");return null;
    }
    public Conversion automatic(Type from,Type to,Set<String> visited) {
        if(same(from,to))return new Identity();
        if(!visited.add(from.identity()+" -> "+to.identity()))return null;
        if(from.kind()==Kind.PAGE && to.kind()==Kind.PAGE) {
            if(!to.pageTransport() || from.pageKind()!=to.pageKind()) return null;
            var element=automatic(from.element(),to.element(),visited);return element==null?null:new Pages(to,element);
        }
        if(from.kind()==Kind.PAGE || to.kind()==Kind.PAGE) return null;
        if(from.kind()==Kind.SEQUENCE && to.kind()==Kind.SEQUENCE) {
            // Turning a list into a set discards duplicates: it requires an explicit Java decision.
            if(to.set() && !from.set())return null;
            var element=automatic(from.element(),to.element(),visited);return element==null?null:new Elements(to,element);
        }
        List<Field> target=fields(to);
        if(to.kind()==Kind.DOMAIN && !isEnum(to) && target.size()==1 && !target.getFirst().optional()) {
            var next=automatic(from,target.getFirst().type(),new HashSet<>(visited));
            if(next!=null)return new Wrap(to,next);
        }
        List<Field> input=fields(from);
        if(from.kind()==Kind.DOMAIN && !isEnum(from) && input.size()==1 && !input.getFirst().optional()) {
            var field=input.getFirst();var next=automatic(field.type(),to,new HashSet<>(visited));
            if(next!=null)return new Unwrap(field,next);
        }
        return null;
    }
    public Path path(Type root,MappingAssignmentNode entry) {
        if(entry.path().isEmpty() || !entry.path().getFirst().equals("source")) {error(entry.location(),"Mapping source paths must start with source.");return null;}
        Type current=root;boolean optional=false,patch=false;List<Field> steps=new ArrayList<>();
        for(String name:entry.path().subList(1,entry.path().size())) {
            if(patch) {error(entry.location(),"PATCH paths must reference the whole field; transformations apply only to set values.");return null;}
            if(current.kind()==Kind.PAGE) {error(entry.location(),"Map a page as a whole using its entry mapping; metadata and items cannot be traversed separately.");return null;}
            if(current.kind()==Kind.SEQUENCE) {error(entry.location(),"Use a named element mapping to traverse a collection.");return null;}
            var next=fields(current).stream().filter(f->f.name().equals(name)).findFirst();
            if(next.isEmpty()) {error(entry.location(),"Unknown source field '"+name+"' on "+current.identity());return null;}
            Field f=next.get();
            if(optional && f.patch()) {error(entry.location(),"PATCH inside an optional source path requires an explicit Java mapping.");return null;}
            steps.add(f);optional|=f.optional();patch=f.patch();current=f.type();
        }
        return new Path(root,steps,current,optional,patch);
    }
    public Type resolveType(TypeNode node) {
        if(node.isOptional())error(node.location(),"Mapping root types must be non-optional; handle absence in fields or calling code.");
        if(node.paginated()) {
            Type values=resolveType(node.unpaged());
            if(values==null) return null;
            if(values.kind()!=Kind.SEQUENCE || values.symbol()==null || values.set()) { error(node.location(),"Page mapping roots require a named domain value list.");return null; }
            return page(node.pageKind(),values.element(),ParameterizedTypeName.get(ClassName.get("org.vernac.runtime.pagination",node.pageKind().javaName()),values.element().javaType(),values.javaType()),values.identity(),false);
        }
        if(node.name().equals("list") && node.typeArguments().size()==1) {
            Type element=resolveType(node.typeArguments().getFirst());return element==null?null:sequence(element,null,false);
        }
        if(!node.typeArguments().isEmpty()) {error(node.location(),"Only list<T> is a technical mapping container.");return null;}
        if (extraTypes.containsKey(node.name())) return extraTypes.get(node.name());
        var local=schemas.get(node.name());
        if(local!=null) {
            if(scope.resolve(node.name(),node.location()).type().orElse(null) instanceof ResolvedType.Declared)
                error(node.location(),"Ambiguous DTO/domain mapping type '"+node.name()+"'. Rename the local schema; qualify domain references when needed.");
            return dto(local);
        }
        var lookup=scope.resolve(node.name(),node.location());errors.addAll(lookup.diagnostics());
        return lookup.type().map(t->domain(t,node.location())).orElse(null);
    }
    public Type domain(ResolvedType resolved,SourceLocation at) {
        if(resolved instanceof ResolvedType.Page p) {
            var values=domain(p.collection(),at);
            return values==null?null:page(p.kind(),values.element(),org.vernac.compiler.generator.ResolvedJavaTypes.javaType(p),values.identity(),false);
        }
        if(resolved instanceof ResolvedType.Builtin b) {
            if(b.javaType()==org.vernac.runtime.EventId.class) {error(at,"EventId is not an ACL mapping scalar.");return null;}
            return scalar(b.javaType());
        }
        if(!(resolved instanceof ResolvedType.Declared d))return null;
        var symbol=d.symbol();
        if(symbol.kind()==TypeSymbol.Kind.COLLECTION) {
            var c=CollectionTypes.find(project,symbol.identity()).orElseThrow();
            if(!c.valueElements()) {error(at,"Entity/aggregate collections are not mapping payloads.");return null;}
            Type item=domain(new ResolvedType.Declared(project.symbols().find(symbol.identity().namespace()+"."+c.elementName()).orElseThrow()),at);
            return item==null?null:sequence(item,symbol,c.definition().kind()==CollectionDefinitionNode.Kind.SET);
        }
        if(!Set.of(TypeSymbol.Kind.ID,TypeSymbol.Kind.VALUE_OBJECT,TypeSymbol.Kind.ENUM).contains(symbol.kind())) {error(at,"Mapping payloads require IDs, values, enums or their collections; not "+symbol.kind());return null;}
        return new Type(symbol.identity().qualifiedName(),ClassName.get(symbol.identity().namespace()+".domain",symbol.identity().name()),Kind.DOMAIN,symbol,null,null,false);
    }
    private Type sequence(Type element,TypeSymbol symbol,boolean set) {
        TypeName java=symbol==null?ParameterizedTypeName.get(ClassName.get(List.class),element.javaType().box()):ClassName.get(symbol.identity().namespace()+".domain",symbol.identity().name());
        return new Type(symbol==null?"list<"+element.identity()+">":symbol.identity().qualifiedName(),java,Kind.SEQUENCE,symbol,null,element,set);
    }
    private Type scalar(Class<?> type) {TypeName java=TypeName.get(type);return new Type(java.box().toString(),java,Kind.SCALAR,null,null,null,false);}
    private Type dto(ResolvedSchema schema) {return new Type("dto:"+schema.source().name(),ClassName.get(dtoPackage,adapterName+"Dtos",schema.source().name()),Kind.DTO,null,schema,null,false);}
    private Type shape(ResolvedSchema.Shape shape) {
        if(shape instanceof ResolvedSchema.Scalar s)return scalar(s.javaType());
        if(shape instanceof ResolvedSchema.Nested n)return dto(schemas.get(n.schemaName()));
        if(shape instanceof ResolvedSchema.Page p) {
            var element=shape(p.element());
            return page(p.kind(),element,ParameterizedTypeName.get(ClassName.get("org.vernac.runtime.json.pagination",p.kind().javaName()+"Dto"),element.javaType().box()),"dto-list<"+element.identity()+">",true);
        }
        return sequence(shape(((ResolvedSchema.Sequence)shape).element()),null,false);
    }
    private Type page(TypeNode.PageKind kind,Type element,TypeName java,String collectionIdentity,boolean transport) {
        return new Type(kind.syntax()+" "+collectionIdentity,java,Kind.PAGE,null,null,element,false,kind,transport);
    }
    public void register(Type type, List<Field> fields, String... names) {
        for (String name : names) extraTypes.put(name,type);
        extraFields.put(type.identity(), List.copyOf(fields));
    }
    public List<Field> fields(Type type) {
        if (extraFields.containsKey(type.identity())) return extraFields.get(type.identity());
        if(type.kind()==Kind.DTO)return type.schema().fields().stream().map(f->new Field(f.source().name(),shape(f.shape()),f.source().type().isOptional(),f.source().patch())).toList();
        if(type.kind()!=Kind.DOMAIN || isEnum(type))return List.of();
        if(type.symbol().kind()==TypeSymbol.Kind.ID)return List.of(new Field("value",scalar(java.util.UUID.class),false,false));
        var node=project.sources().stream().flatMap(s->s.unit().valueObjects().stream().filter(v->s.unit().namespace().equals(type.symbol().identity().namespace()) && v.name().equals(type.symbol().identity().name()))).findFirst().orElseThrow();
        List<Field> fields=new ArrayList<>();
        for(var f:node.fields()) {var resolved=domainTypes.get(f.type());if(resolved!=null) {Type ft=domain(resolved,f.location());if(ft!=null)fields.add(new Field(f.name(),ft,f.type().isOptional(),false));}}
        return fields;
    }
    private boolean isEnum(Type t) {return t.symbol()!=null && t.symbol().kind()==TypeSymbol.Kind.ENUM;}
    private boolean same(Type a,Type b) {return a.identity().equals(b.identity());}
    private void error(SourceLocation at,String message) {errors.add(CompilerDiagnostic.error(at,message));}
}
