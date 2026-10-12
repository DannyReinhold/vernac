// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.dto;
import java.util.*;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.symbols.BuiltinTypes;
import org.vernac.language.VernacNames;
import static org.vernac.compiler.dto.ResolvedSchema.*;

/** Local schema namespace and transport-specific constraints. */
public final class SchemaContracts {
    private static final Set<String> RESERVED = Set.of("of","toString","equals","hashCode","getClass","clone","finalize","notify","notifyAll","wait");
    public List<ResolvedSchema> resolve(OutboundAdapterNode adapter, List<CompilerDiagnostic> errors) {
        return resolve(adapter.name(), adapter.schemas(), errors);
    }
    public List<ResolvedSchema> resolve(String adapterName, List<SchemaNode> declarations, List<CompilerDiagnostic> errors) {
        Map<String,SchemaNode> names = new LinkedHashMap<>();
        for(var schema:declarations) {
            if(!VernacNames.isTypeName(schema.name())) error(errors,schema.location(),VernacNames.invalidNameMessage(schema.name()));
            if(names.putIfAbsent(schema.name(),schema)!=null) error(errors,schema.location(),"Duplicate adapter-local schema: " + schema.name());
            if(BuiltinTypes.names().contains(schema.name()) || schema.name().equals("list") || schema.name().equals(adapterName+"Dtos") || schema.name().startsWith("__"))
                error(errors,schema.location(),"Reserved DTO schema name: " + schema.name());
        }
        List<ResolvedSchema> result=new ArrayList<>();
        for(var schema:declarations) {
            if(!Set.of("exact","snake_case").contains(schema.naming())) error(errors,schema.location(),"DTO naming must be exact or snake_case.");
            Set<String> fields=new HashSet<>(),external=new HashSet<>();
            List<Field> resolved=new ArrayList<>();
            for(var field:schema.fields()) {
                if(!fields.add(field.name())) error(errors,field.location(),"Duplicate DTO field '"+field.name()+"'. Specify distinct explicit names.");
                if(RESERVED.contains(field.name()) || field.name().startsWith("__")) error(errors,field.location(),"DTO field conflicts with generated Java API: " + field.name());
                if(!VernacNames.isIdentifier(field.name())) error(errors,field.location(),VernacNames.invalidNameMessage(field.name()));
                if(field.absentAsNull() && (field.patch() || !field.type().isOptional())) error(errors,field.location(),"absent as null requires an ordinary optional DTO field, not a PATCH field.");
                String wire=field.externalName().orElseGet(()->schema.naming().equals("snake_case")?snakeCase(field.name()):field.name());
                if(!external.add(wire)) error(errors,field.location(),"Duplicate external JSON field name: " + wire);
                Shape shape=resolveType(field.type(),names,errors,false);
                if(shape!=null) resolved.add(new Field(field,wire,shape));
            }
            result.add(new ResolvedSchema(schema,resolved));
        }
        Map<String,ResolvedSchema> indexed=new LinkedHashMap<>();result.forEach(s->indexed.put(s.source().name(),s));
        Set<String> done=new HashSet<>();
        for(var schema:result) cycle(schema,indexed,new LinkedHashSet<>(),done,errors);
        return List.copyOf(result);
    }
    private Shape resolveType(TypeNode type,Map<String,SchemaNode> names,List<CompilerDiagnostic> errors,boolean element) {
        if(element && type.isOptional()) error(errors,type.location(),"DTO list elements cannot be optional.");
        if(type.name().equals("list") && type.typeArguments().size()==1) {
            Shape nested=resolveType(type.typeArguments().getFirst(),names,errors,true);
            return nested==null?null:new Sequence(nested);
        }
        if(!type.typeArguments().isEmpty()) { error(errors,type.location(),"Only list<T> is a supported DTO container."); return null; }
        var builtin=BuiltinTypes.find(type.name());
        if(builtin.isPresent() && !type.name().equals("EventId")) return new Scalar(builtin.get());
        if(names.containsKey(type.name())) return new Nested(type.name());
        error(errors,type.location(),"Unknown DTO type '"+type.name()+"'. Use an approved scalar or a schema declared in this adapter; domain and arbitrary Java types are not transport schemas.");return null;
    }
    private void cycle(ResolvedSchema schema,Map<String,ResolvedSchema> all,Set<String> active,Set<String> done,List<CompilerDiagnostic> errors) {
        String name=schema.source().name();
        if(active.contains(name)) { error(errors,schema.source().location(),"Cyclic DTO containment: "+String.join(" -> ",active)+" -> "+name);return; }
        if(done.contains(name))return;
        active.add(name);
        for(var field:schema.fields()) { Shape type=field.shape();while(type instanceof Sequence s)type=s.element();if(type instanceof Nested n)cycle(all.get(n.schemaName()),all,active,done,errors); }
        active.remove(name);done.add(name);
    }
    /** Code-point based, locale-independent acronym boundary rule. No dictionary. */
    public static String snakeCase(String name) {
        int[] c=name.codePoints().toArray();StringBuilder result=new StringBuilder();
        for(int i=0;i<c.length;i++) {
            if(i>0 && Character.isUpperCase(c[i]) && (Character.isLowerCase(c[i-1]) || Character.isDigit(c[i-1])
                    || Character.isUpperCase(c[i-1]) && i+1<c.length && Character.isLowerCase(c[i+1]))) result.append('_');
            result.appendCodePoint(Character.toLowerCase(c[i]));
        }
        return result.toString();
    }
    private static void error(List<CompilerDiagnostic> errors,SourceLocation at,String message) { errors.add(CompilerDiagnostic.error(at,message)); }
}
