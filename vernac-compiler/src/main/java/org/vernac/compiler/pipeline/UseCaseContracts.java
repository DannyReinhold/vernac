// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import java.util.*;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.symbols.*;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.util.MemberNames;
import org.vernac.language.VernacNames;

/** Resolves usecase contracts before Java generation. No expression-based result inference. */
final class UseCaseContracts {
    private static final Set<String> GETTERS=Set.of("getClass","toString","hashCode","notify","notifyAll","wait","clone","finalize");
    void validate(UseCaseNode u, FileTypeScope scope, Map<TypeNode,ResolvedType> types,
            List<CompilerDiagnostic> errors, VernacProject project, String namespace) {
        if(u.name().equals("Result") && !u.resultFields().isEmpty()) error(errors,u.location(),"Usecase name Result conflicts with its nested Result record.");
        if(u.executeCount()!=1) error(errors,u.location(),"A usecase requires exactly one execute implementation.");
        var arguments=new ArrayList<>(u.parameters()); arguments.addAll(u.injections());
        names(arguments,errors); names(u.resultFields(),errors);
        for(var f:u.parameters()) { domain(f.type(),scope,types,errors,project); field(f,errors,true); }
        for(var f:u.resultFields()) { domain(f.type(),scope,types,errors,project); field(f,errors,true); }
        u.resultType().ifPresent(t -> domain(t,scope,types,errors,project));
        for(var f:u.injections()) {
            field(f,errors,false);
            var t=resolve(f.type(),scope,types,errors);
            if(f.type().isOptional() || !(t instanceof ResolvedType.Declared d)
                    || !Set.of(TypeSymbol.Kind.REPOSITORY,TypeSymbol.Kind.PORT,TypeSymbol.Kind.DOMAIN_SERVICE,TypeSymbol.Kind.USE_CASE).contains(d.symbol().kind()))
                error(errors,f.location(),"uses requires a non-optional repository, port, service or usecase.");
        }
        var visible=new HashMap<>(BehaviorImports.visible(project,scope));
        for(var source:project.sources()) for(var symbol:project.symbols().inNamespace(source.unit().namespace())) {
            var lookup=scope.resolve(symbol.identity().name(),u.location());
            if(lookup.type().orElse(null) instanceof ResolvedType.Declared d && d.symbol().equals(symbol))
                visible.putIfAbsent(symbol.identity().name(), javaName(d));
        }
        if(!u.resultFields().isEmpty() && visible.containsKey("Result"))
            error(errors,u.location(),"Visible type Result conflicts with the generated usecase Result. Use qualified domain references instead.");
        visible.put("Result",namespace+".usecase."+u.name()+".Result");
        visible.put("__VernacBehavior_"+u.name(),namespace+".usecase.__VernacBehavior_"+u.name());
        for(var i:u.javaImports()) {
            String simple=i.target().substring(i.target().lastIndexOf('.')+1);
            if(!i.target().contains(".") || Arrays.stream(i.target().split("\\.")).anyMatch(x -> !VernacNames.isIdentifier(x)))
                error(errors,i.location(),"Expected a fully qualified Java type import.");
            var previous=visible.putIfAbsent(simple,i.target());
            if(previous!=null && !previous.equals(i.target())) error(errors,i.location(),"Java import conflicts with visible type: "+simple);
        }
        u.implementation().ifPresent(target -> {
            if(Arrays.stream(target.split("\\.")).anyMatch(x -> !VernacNames.isIdentifier(x))) error(errors,u.location(),"Invalid implementation class name.");
        });
        Set<String> signatures=new HashSet<>();
        for(var m:u.methods()) {
            if(!m.accessModifier().equals("private") || m.implementation().isPresent() || (m.name().equals("execute") || GETTERS.contains(m.name()) || !VernacNames.isIdentifier(m.name())))
                error(errors,m.location(),"Usecase helpers must be private inline methods; execute is reserved.");
            if(m.returnType().name().equals("void") && m.returnType().isOptional()) error(errors,m.location(),"void cannot be optional.");
            names(m.parameters(),errors);
            for(var f:m.parameters()) { resolve(f.type(),scope,types,errors); field(f,errors,false); }
            if(!m.returnType().name().equals("Result") && !m.returnType().name().equals("void")) resolve(m.returnType(),scope,types,errors);
            else if(m.returnType().name().equals("Result") && u.resultFields().isEmpty()) error(errors,m.location(),"Result requires a declared composite result.");
            String signature=m.name()+m.parameters().stream().map(f -> {
                var t=types.get(f.type());return (t==null?f.type().name():javaName(t))+(f.type().isOptional()?"?":"");
            }).toList();
            if(!signatures.add(signature)) error(errors,m.location(),"Duplicate private helper signature: "+m.name());
        }
    }
    static String javaName(ResolvedType t) {
        if(t instanceof ResolvedType.Declared d) {
            String ns=d.symbol().identity().namespace(),name=d.symbol().identity().name();
            return ns+(d.symbol().kind()==TypeSymbol.Kind.USE_CASE?".usecase.":".domain.")+name;
        }
        return JavaTypeNames.canonicalName(t);
    }
    private ResolvedType resolve(TypeNode t,FileTypeScope scope,Map<TypeNode,ResolvedType> types,List<CompilerDiagnostic> errors) {
        var lookup=scope.resolve(t.name(),t.location()); errors.addAll(lookup.diagnostics());
        if(!t.typeArguments().isEmpty()) error(errors,t.location(),"Use declared Vernac collections, not Java generics.");
        if(t.isOptional() && lookup.type().orElse(null) instanceof ResolvedType.Builtin primitive && primitive.javaType().isPrimitive())
            error(errors,t.location(),"Primitive helper types cannot be optional; use the boxed type.");
        lookup.type().ifPresent(v -> types.put(t,v)); return lookup.type().orElse(null);
    }
    private void domain(TypeNode t,FileTypeScope scope,Map<TypeNode,ResolvedType> types,List<CompilerDiagnostic> errors,VernacProject project) {
        var resolved=resolve(t,scope,types,errors);
        boolean valid=resolved instanceof ResolvedType.Declared d && (Set.of(TypeSymbol.Kind.ID,TypeSymbol.Kind.VALUE_OBJECT,TypeSymbol.Kind.ENUM).contains(d.symbol().kind())
                || d.symbol().kind()==TypeSymbol.Kind.COLLECTION && CollectionTypes.find(project,d.symbol().identity()).map(c -> c.valueElements()).orElse(false));
        if(!valid) error(errors,t.location(),"Usecase inputs/results require IDs, value objects, enums or their collections; wrap Java scalars and pass entity/aggregate IDs.");
    }
    private void names(List<FieldNode> fields,List<CompilerDiagnostic> errors) {
        errors.addAll(MemberNames.duplicates(fields,"parameter/result",""));
    }
    private void field(FieldNode f,List<CompilerDiagnostic> errors,boolean getter) {
        if(!VernacNames.isIdentifier(f.name()) || f.name().equals("self") || f.name().startsWith("__vernac") || getter && GETTERS.contains(f.name()))
            error(errors,f.location(),"Invalid or generated member conflict: "+f.name()+". Specify another explicit name.");
        if(f.isMutable()) error(errors,f.location(),"Usecase contracts cannot declare mut parameters or results.");
    }
    private static void error(List<CompilerDiagnostic> errors,SourceLocation at,String text) { errors.add(CompilerDiagnostic.error(at,text)); }
}
