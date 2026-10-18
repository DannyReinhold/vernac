// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import java.util.*;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.symbols.*;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.util.MemberNames;
import org.vernac.language.VernacNames;

/** Project-wide port signatures and complete adapter implementations. */
final class PortContracts {
    private static final Set<String> RESERVED = Set.of("self","invocation","emit","getClass","toString","hashCode",
            "equals","notify","notifyAll","wait","clone","finalize");

    void validate(PortNode port, FileTypeScope scope, Map<TypeNode,ResolvedType> types,
            List<CompilerDiagnostic> errors, VernacProject project) {
        if(port.methods().isEmpty()) error(errors,port.location(),"A port requires at least one operation.");
        Set<String> signatures=new HashSet<>();
        for(var operation:port.methods()) {
            var method=operation.method();
            validateMethod(method,scope,types,errors,project,true);
            if(!signatures.add(signature(method,types))) error(errors,method.location(),"Duplicate port operation signature: "+method.name());
        }
    }

    void validate(OutboundAdapterNode adapter, FileTypeScope scope, Map<TypeNode,ResolvedType> types,
            List<CompilerDiagnostic> errors, VernacProject project, String namespace) {
        var lookup=scope.resolve(adapter.portType().name(),adapter.location());
        errors.addAll(lookup.diagnostics());
        TypeSymbol target=null;
        if(lookup.type().orElse(null) instanceof ResolvedType.Declared d && d.symbol().kind()==TypeSymbol.Kind.PORT
                && !adapter.portType().isOptional() && adapter.portType().typeArguments().isEmpty()) {
            types.put(adapter.portType(),d); target=d.symbol();
        } else error(errors,adapter.location(),"An adapter must implement a non-optional Vernac port.");
        List<FieldNode> dependencies=new ArrayList<>(adapter.dependencies()); dependencies.addAll(adapter.javaDependencies());
        for (var config : adapter.configuration()) {
            dependencies.add(new FieldNode(config.location(), new TypeNode(config.location(), config.type()), config.name(), false));
            if (!AdapterConfigurationTypes.TYPES.containsKey(config.type()))
                error(errors, config.location(), "Configuration type must be String, URI, Duration, Path, int, long or boolean.");
            if (!config.property().matches("[a-zA-Z0-9][a-zA-Z0-9_.-]*"))
                error(errors, config.location(), "Configuration requires a literal property key using letters, digits, dots, hyphens or underscores.");
            if (adapter.rest().isPresent() && adapter.rest().get().base().equals(config.name()) && !config.type().equals("URI"))
                error(errors, config.location(), "REST base configuration must have type URI.");
        }
        names(dependencies,errors);
        for(var dep:adapter.dependencies()) {
            var found=scope.resolve(dep.type().name(),dep.location()); errors.addAll(found.diagnostics());
            found.type().ifPresent(t->types.put(dep.type(),t));
            if(dep.type().isOptional() || !dep.type().typeArguments().isEmpty()
                    || !(found.type().orElse(null) instanceof ResolvedType.Declared d)
                    || !Set.of(TypeSymbol.Kind.DOMAIN_SERVICE,TypeSymbol.Kind.PORT,TypeSymbol.Kind.REPOSITORY).contains(d.symbol().kind()))
                error(errors,dep.location(),"Adapter uses requires a non-optional domain service, port or repository.");
        }
        var visible=new HashMap<>(BehaviorImports.visible(project,scope));
        for(var source:project.sources()) for(var symbol:project.symbols().inNamespace(source.unit().namespace())) {
            var candidate=scope.resolve(symbol.identity().name(),adapter.location()).type().orElse(null);
            if(candidate instanceof ResolvedType.Declared d && d.symbol().equals(symbol))
                visible.putIfAbsent(symbol.identity().name(),JavaTypeNames.canonicalName(d));
        }
        for(var imported:adapter.javaImports()) {
            if(!imported.target().contains(".") || Arrays.stream(imported.target().split("\\.")).anyMatch(n->!VernacNames.isIdentifier(n)))
                error(errors,imported.location(),"Expected a fully qualified Java import.");
            String simple=imported.target().substring(imported.target().lastIndexOf('.')+1);
            String previous=visible.putIfAbsent(simple,imported.target());
            if(previous!=null && !previous.equals(imported.target())) error(errors,imported.location(),"Java import conflicts with visible type: "+simple);
        }
        for(var dep:adapter.javaDependencies()) {
            String name=dep.type().name();
            if(!name.contains(".") || dep.type().isOptional() || !dep.type().typeArguments().isEmpty()
                    || Arrays.stream(name.split("\\.")).anyMatch(n->!VernacNames.isIdentifier(n)))
                error(errors,dep.location(),"java uses requires a fully qualified, non-optional Java class type.");
            if(project.symbols().find(name).isPresent()) error(errors,dep.location(),"Use uses for Vernac dependencies, not java uses.");
        }
        Map<String,MethodNode> methods=new LinkedHashMap<>(); Set<String> lowered=new HashSet<>();
        for(var operation:adapter.methods()) {
            var method=operation.method(); boolean publicMethod=method.accessModifier().equals("public");
            validateMethod(method,scope,types,errors,project,publicMethod);
            method.implementation().ifPresent(name->{
                org.vernac.compiler.util.JavaImplementations.validate(name,adapter.javaImports(),method.location(),errors);
            });
            if(!operation.validations().isEmpty()) error(errors,method.location(),"Adapter preconditions are not part of this slice; validate domain values or use behavior code.");
            if(!publicMethod && method.implementation().isPresent()) error(errors,method.location(),"Private adapter helpers must be inline.");
            if(method.parameters().isEmpty() && dependencies.stream().anyMatch(d->d.name().equals(method.name())))
                error(errors,method.location(),"Adapter operation conflicts with dependency accessor: "+method.name());
            String key=signature(method,types);
            if(publicMethod && methods.putIfAbsent(key,method)!=null) error(errors,method.location(),"Duplicate adapter operation: "+method.name());
            String receiver=publicMethod ? namespace+".adapter.outbound.access."+adapter.name()+"Access" : "";
            String loweredKey=org.vernac.compiler.util.JavaMethodSignatures.implementation(method.name(),receiver,method.parameters(),types);
            if(!lowered.add(loweredKey)) error(errors,method.location(),"Duplicate adapter behavior signature after Java lowering: "+loweredKey+". Nullability and return types cannot distinguish overloads.");
        }
        if(target!=null) {
            final TypeSymbol portSymbol=target;
            var source=project.sources().stream().filter(s->s.path().equals(portSymbol.sourceFile())).findFirst().orElseThrow();
            var port=source.unit().ports().stream().filter(p->p.name().equals(portSymbol.identity().name())).findFirst().orElseThrow();
            Set<String> namespaces=new HashSet<>(); project.sources().forEach(s->namespaces.add(s.unit().namespace()));
            var portScope=new FileTypeScope(source.unit(),project.symbols(),namespaces);
            Set<String> expected=new HashSet<>();
            for(var declaration:port.methods()) {
                var method=declaration.method();
                resolve(method.returnType(),portScope,types,errors,project,true,true);
                for(var field:method.parameters()) resolve(field.type(),portScope,types,errors,project,true,false);
                String key=signature(method,types); expected.add(key);
                MethodNode impl=methods.get(key);
                if(impl==null) error(errors,adapter.location(),"Adapter '"+adapter.name()+"' is missing port operation: "+method.name());
                else {
                    boolean matches=canonical(method.returnType(),types).equals(canonical(impl.returnType(),types))
                            && method.returnType().isOptional()==impl.returnType().isOptional();
                    for(int i=0;i<method.parameters().size();i++) matches &= method.parameters().get(i).type().isOptional()==impl.parameters().get(i).type().isOptional();
                    if(!matches) error(errors,impl.location(),"Adapter signature including optionality must match port operation: "+method.name());
                }
            }
            for(var entry:methods.entrySet()) if(!expected.contains(entry.getKey()))
                error(errors,entry.getValue().location(),"Public adapter operation is not declared by its port: "+entry.getValue().name());
        }
    }
    private void validateMethod(MethodNode method,FileTypeScope scope,Map<TypeNode,ResolvedType> types,
            List<CompilerDiagnostic> errors,VernacProject project,boolean domainOnly) {
        if(!VernacNames.isIdentifier(method.name()) || RESERVED.contains(method.name()) || method.name().startsWith("__"))
            error(errors,method.location(),"Operation conflicts with reserved/generated name: "+method.name());
        names(method.parameters(),errors);
        resolve(method.returnType(),scope,types,errors,project,domainOnly,true);
        for(var p:method.parameters()) resolve(p.type(),scope,types,errors,project,domainOnly,false);
    }
    private void resolve(TypeNode ref,FileTypeScope scope,Map<TypeNode,ResolvedType> types,
            List<CompilerDiagnostic> errors,VernacProject project,boolean domainOnly,boolean result) {
        if(result && ref.name().equals("void") && !ref.isOptional() && ref.typeArguments().isEmpty()) {
            types.put(ref,new ResolvedType.VoidReturn());return;
        }
        var found=scope.resolve(ref.name(),ref.location());errors.addAll(found.diagnostics());
        if(!ref.typeArguments().isEmpty()) error(errors,ref.location(),"Use declared Vernac collections in port signatures.");
        found.type().ifPresent(type->{
            types.put(ref,type);
            boolean allowed=type instanceof ResolvedType.Builtin && !domainOnly;
            if(type instanceof ResolvedType.Declared d) {
                allowed=Set.of(TypeSymbol.Kind.ID,TypeSymbol.Kind.VALUE_OBJECT,TypeSymbol.Kind.ENUM).contains(d.symbol().kind());
                if(d.symbol().kind()==TypeSymbol.Kind.COLLECTION) allowed=CollectionTypes.find(project,d.symbol().identity())
                    .flatMap(c->project.symbols().find(d.symbol().identity().namespace()+"."+c.elementName()))
                    .map(e->Set.of(TypeSymbol.Kind.ID,TypeSymbol.Kind.VALUE_OBJECT,TypeSymbol.Kind.ENUM).contains(e.kind())).orElse(false);
            }
            if(!allowed) error(errors,ref.location(),"Port signatures require IDs, value objects, enums or their collections; entities, aggregates and raw Java types are not allowed.");
            if(ref.isOptional() && type instanceof ResolvedType.Builtin b && b.javaType().isPrimitive())
                error(errors,ref.location(),"Optional primitive types are not supported.");
        });
    }
    private void names(List<FieldNode> fields,List<CompilerDiagnostic> errors) {
        errors.addAll(MemberNames.duplicates(fields,"port/adapter parameter or dependency",""));
        for(var f:fields) if(!VernacNames.isIdentifier(f.name()) || RESERVED.contains(f.name()) || f.name().startsWith("__") || f.isMutable())
            error(errors,f.location(),"Invalid or reserved port/adapter member name: "+f.name());
    }
    private String signature(MethodNode m,Map<TypeNode,ResolvedType> types) {
        return org.vernac.compiler.util.JavaMethodSignatures.signature(m.name(),m.parameters(),types);
    }
    private String canonical(TypeNode ref,Map<TypeNode,ResolvedType> types) {
        var type=types.get(ref);return type==null ? ref.name() : JavaTypeNames.canonicalName(type);
    }
    private static void error(List<CompilerDiagnostic> errors,SourceLocation at,String message) {
        errors.add(CompilerDiagnostic.error(at,message));
    }
}
