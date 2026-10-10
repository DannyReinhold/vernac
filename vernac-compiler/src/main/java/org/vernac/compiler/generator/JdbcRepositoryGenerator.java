// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.generator;

import com.palantir.javapoet.*;
import javax.lang.model.element.Modifier;
import java.util.*;
import org.jspecify.annotations.NullMarked;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.persistence.*;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.symbols.*;

/** Typed domain bindings compiled from the same plan that produces PostgreSQL DDL. */
public final class JdbcRepositoryGenerator {
    private static final ClassName M=ClassName.get("org.vernac.runtime.jdbc","JdbcMapping");
    private static final ClassName STORE=ClassName.get("org.vernac.runtime.jdbc","JdbcAggregateStore");
    private static final ClassName JDBC=ClassName.get("org.springframework.jdbc.core.namedparam","NamedParameterJdbcTemplate");
    private final ResolvedProject project;
    private final SchemaModel schema;
    private TypeSpec.Builder currentAdapter;
    private int bindingNumber;
    public JdbcRepositoryGenerator(ResolvedProject project,SchemaModel schema) { this.project=project; this.schema=schema; }

    public List<JavaFile> generate(StoragePlan plan) {
        ClassName aggregate=ClassName.get(plan.aggregateNamespace()+".domain",plan.aggregate().name());
        ClassName id=(ClassName)ResolvedJavaTypes.javaType(project.typeOf(plan.aggregate().idDefinition().type()));
        ClassName api=ClassName.get(plan.repositoryNamespace()+".domain",plan.repository().name());
        var contract=TypeSpec.interfaceBuilder(api).addModifiers(Modifier.PUBLIC).addAnnotation(NullMarked.class);
        contract.addMethod(MethodSpec.methodBuilder("byId").addModifiers(Modifier.PUBLIC,Modifier.ABSTRACT).returns(aggregate).addParameter(id,"id").build());
        contract.addMethod(MethodSpec.methodBuilder("save").addModifiers(Modifier.PUBLIC,Modifier.ABSTRACT).returns(aggregate).addParameter(aggregate,"aggregate").build());
        contract.addMethod(MethodSpec.methodBuilder("delete").addModifiers(Modifier.PUBLIC,Modifier.ABSTRACT).addParameter(aggregate,"aggregate").build());
        var adapter=TypeSpec.classBuilder("Jdbc"+api.simpleName()).addModifiers(Modifier.PUBLIC).addSuperinterface(api).addAnnotation(NullMarked.class)
                .addAnnotation(ClassName.get("org.springframework.stereotype","Repository"))
                .addAnnotation(AnnotationSpec.builder(ClassName.get("org.springframework.transaction.annotation","Transactional"))
                        .addMember("propagation","$T.MANDATORY",ClassName.get("org.springframework.transaction.annotation","Propagation")).build());
        currentAdapter=adapter; bindingNumber=0;
        TypeName storeType=ParameterizedTypeName.get(STORE,aggregate);
        adapter.addField(storeType,"store",Modifier.PRIVATE,Modifier.FINAL);
        ClassName dispatcher=ClassName.get("org.vernac.runtime.outbox","EventDispatcher");
        adapter.addField(dispatcher,"events",Modifier.PRIVATE,Modifier.FINAL);
        adapter.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(JDBC,"jdbc").addParameter(dispatcher,"events")
                .addStatement("this.store = new $T<>(jdbc, $T.class, rootMapping(), entityMappings(), tables())",STORE,aggregate)
                .addStatement("this.events = $T.requireNonNull(events)",Objects.class).build());
        adapter.addMethod(MethodSpec.methodBuilder("byId").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC).returns(aggregate).addParameter(id,"id")
                .addStatement("return store.byId(id.value())").build());
        adapter.addMethod(MethodSpec.methodBuilder("save").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC).returns(aggregate).addParameter(aggregate,"aggregate")
                .addStatement("store.save(aggregate)")
                .addStatement("events.dispatch($S, aggregate.id().asString(), aggregate.pullDomainEvents())",aggregate.canonicalName())
                .addStatement("return aggregate").build());
        adapter.addMethod(MethodSpec.methodBuilder("delete").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC).addParameter(aggregate,"aggregate")
                .addStatement("store.delete(aggregate)").build());
        queryMethods(plan, contract, adapter);
        List<CodeBlock> entities=new ArrayList<>(),tables=new ArrayList<>();
        for(var relation:plan.relations()) {
            var table=relation.table();
            List<CodeBlock> columns=table.columns().stream().map(c->CodeBlock.of("new $T.Column($S, $S)",M,SqlNames.physical(c.name()),c.sqlType())).toList();
            tables.add(CodeBlock.of("new $T.Table($S, $S, $L, $L, $L)",M,table.sqlName(),relation.predicate(),relation.depth(),list(columns),strings(table.primaryKey())));
            if(table.primaryKey().equals(List.of("id"))) {
                adapter.addMethod(MethodSpec.methodBuilder("rootMapping").addModifiers(Modifier.PRIVATE,Modifier.STATIC).returns(M.nestedClass("Domain"))
                        .addStatement("return $L",domain(relation,aggregate,id,true)).build());
            } else if(table.primaryKey().equals(List.of("@aggregateId","id"))) {
                var symbol=project.project().symbols().find(table.origin()).orElseThrow();
                EntityNode node=null;
                for(var source:project.project().sources()) for(var def:source.unit().definitions())
                    if(def instanceof EntityNode e && (source.unit().namespace()+"."+e.name()).equals(table.origin())) node=e;
                entities.add(domain(relation,ResolvedJavaTypes.domainClass(symbol.identity()),
                        (ClassName)ResolvedJavaTypes.javaType(project.typeOf(Objects.requireNonNull(node).idDefinition().type())),false));
            }
        }
        adapter.addMethod(MethodSpec.methodBuilder("entityMappings").addModifiers(Modifier.PRIVATE,Modifier.STATIC)
                .returns(ParameterizedTypeName.get(ClassName.get(List.class),M.nestedClass("Domain"))).addStatement("return $L",list(entities)).build());
        adapter.addMethod(MethodSpec.methodBuilder("tables").addModifiers(Modifier.PRIVATE,Modifier.STATIC)
                .returns(ParameterizedTypeName.get(ClassName.get(List.class),M.nestedClass("Table"))).addStatement("return $L",list(tables)).build());
        return List.of(JavaFile.builder(api.packageName(),contract.build()).indent("    ").skipJavaLangImports(true).build(),
                JavaFile.builder(plan.repositoryNamespace()+".adapter.outbound.jdbc",adapter.build()).indent("    ").skipJavaLangImports(true).build());
    }
    private void queryMethods(StoragePlan plan, TypeSpec.Builder contract, TypeSpec.Builder adapter) {
        ClassName queryType=ClassName.get("org.vernac.runtime.jdbc","ScalarQuery");
        for(var method:plan.repository().findMethods()) {
            var query=Objects.requireNonNull(project.queries().get(method),"Unresolved repository query");
            TypeName result=ResolvedJavaTypes.javaType(query.result());
            TypeName returns=query.singleton()?ParameterizedTypeName.get(ClassName.get(Optional.class),result):result;
            var api=MethodSpec.methodBuilder(method.name()).addModifiers(Modifier.PUBLIC,Modifier.ABSTRACT).returns(returns);
            var impl=MethodSpec.methodBuilder(method.name()).addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(returns);
            for(var p:method.parameters()) {
                TypeName type=ResolvedJavaTypes.javaType(project.typeOf(p.type()));
                api.addParameter(type,p.name()); impl.addParameter(type,p.name());
                if(!type.isPrimitive()) impl.addStatement("$T.requireNonNull($L, $S)",Objects.class,p.name(),"Query parameter "+p.name()+" must not be null");
            }
            impl.addStatement("$T __vernacQuery = new $T()",queryType,queryType);
            List<CodeBlock> clauses=new ArrayList<>();
            for(var predicate:query.predicates()) {
                var f=predicate.field(); var columns=queryColumns(plan,f);
                if(predicate.parameter()==null) {
                    clauses.add(CodeBlock.of("$T.presence($S, $L)",queryType,SqlNames.physical(columns.getFirst()),predicate.operator().equals("present")));
                } else {
                    String scalar=f.scalar();
                    CodeBlock value=CodeBlock.of("$L$L",predicate.parameter().name(),(Set.of("like","contains","starts","ends").contains(predicate.operator()) || f.accessor().isEmpty())?"":"."+f.accessor()+"()");
                    if(scalar.equals("enum")) {
                        scalar="String";
                        var symbol=((ResolvedType.Declared)f.type()).symbol();
                        var cases=CodeBlock.builder().add("switch ($L) {\n",predicate.parameter().name());
                        schema.enumCodes().get(symbol.identity().qualifiedName()).forEach((constant,code)->cases.add("case $L -> $S;\n",constant,code));
                        value=cases.add("}").build();
                    }
                    clauses.add(CodeBlock.of("__vernacQuery.compare($S, $L, $S, $L)",scalar,strings(columns),predicate.operator(),value));
                }
            }
            List<CodeBlock> orders=new ArrayList<>();
            for(var o:query.orders()) orders.add(CodeBlock.of("$T.order($S, $L, $L)",queryType,o.field().scalar(),strings(queryColumns(plan,o.field())),o.descending()));
            impl.addStatement("var __vernacResults = store.find($T.join($S, $L), $T.join($S, $L), __vernacQuery.parameters(), $L, $S)",
                    String.class," AND ",list(clauses),String.class,", ",list(orders),query.singleton(),plan.repositoryNamespace()+"."+plan.repository().name()+"."+method.name());
            if(query.singleton()) impl.addStatement("return __vernacResults.stream().findFirst()");
            else impl.addStatement("return $T.of(__vernacResults)",result);
            contract.addMethod(api.build()); adapter.addMethod(impl.build());
        }
    }
    private List<String> queryColumns(StoragePlan plan, org.vernac.compiler.query.ResolvedQuery.Field field) {
        if(field.name().equals("id")) return List.of("id");
        if(field.name().equals("createdAt") || field.name().equals("updatedAt"))
            return List.of("@"+field.name(),"@nanoRemainder:@"+field.name());
        var root=plan.relations().stream().filter(r->r.table().primaryKey().equals(List.of("id"))).findFirst().orElseThrow();
        var value=root.properties().stream().filter(p->p.name().equals(field.name())).findFirst().orElseThrow().value();
        if(!value.children().isEmpty()) value=value.children().getFirst().value();
        return value.columns();
    }
    private CodeBlock domain(StoragePlan.Relation relation,ClassName owner,ClassName id,boolean root) {
        List<CodeBlock> fields=new ArrayList<>(),args=new ArrayList<>();
        args.add(CodeBlock.of("$T.of(id)",id));
        for(int i=0;i<relation.properties().size();i++) {
            var p=relation.properties().get(i); fields.add(property(owner,p));
            args.add(CodeBlock.of("($T) values[$L]",ResolvedJavaTypes.javaType(p.value().type()).box(),i));
        }
        if(root) {
            int n=fields.size();
            fields.add(CodeBlock.of("new $T.Field(value -> (($T) value).createdAt(), $T.scalar($S, false, $S, $S))",M,owner,M,"Instant","@createdAt","@nanoRemainder:@createdAt"));
            fields.add(CodeBlock.of("new $T.Field(value -> (($T) value).updatedAt(), $T.scalar($S, false, $S, $S))",M,owner,M,"Instant","@updatedAt","@nanoRemainder:@updatedAt"));
            fields.add(CodeBlock.of("new $T.Field(value -> (($T) value).persistenceState().version(), $T.scalar($S, false, $S))",M,owner,M,"long","@version"));
            args.add(CodeBlock.of("($T) values[$L]",java.time.Instant.class,n));
            args.add(CodeBlock.of("($T) values[$L]",java.time.Instant.class,n+1));
            args.add(CodeBlock.of("($T) values[$L]",Long.class,n+2));
        }
        return CodeBlock.of("new $T.Domain($S, value -> (($T) value).id().value(), $L, (id, values) -> $T.reconstitute($L))",
                M,relation.table().sqlName(),owner,list(fields),owner,CodeBlock.join(args,", "));
    }
    private CodeBlock property(ClassName owner,StoragePlan.Property p) {
        return CodeBlock.of("new $T.Field(value -> (($T) value).$L()$L, $L)",M,owner,p.name(),p.value().optional()?".orElse(null)":"",value(p.value()));
    }
    private CodeBlock value(StoragePlan.Value v) {
        String method="valueMapping" + bindingNumber++;
        CodeBlock expression=inlineValue(v);
        currentAdapter.addMethod(MethodSpec.methodBuilder(method).addModifiers(Modifier.PRIVATE,Modifier.STATIC)
                .returns(M.nestedClass("Value")).addStatement("return $L",expression).build());
        return CodeBlock.of("$L()",method);
    }
    private CodeBlock inlineValue(StoragePlan.Value v) {
        if(v.type() instanceof ResolvedType.Builtin b) return scalar(v,b.javaType().getSimpleName());
        var symbol=((ResolvedType.Declared)v.type()).symbol(); ClassName type=ResolvedJavaTypes.domainClass(symbol.identity());
        return switch(symbol.kind()) {
            case ID -> CodeBlock.of("$T.converted($L, value -> (($T) value).value(), value -> $T.of(($T) value))",M,scalar(v,"UUID"),type,type,UUID.class);
            case ENUM -> {
                var codes=schema.enumCodes().get(symbol.identity().qualifiedName());
                var encode=CodeBlock.builder().add("value -> switch (($T) value) {\n",type);
                var decode=CodeBlock.builder().add("value -> switch (($T) value) {\n",String.class);
                codes.forEach((constant,code)-> { encode.add("case $L -> $S;\n",constant,code);decode.add("case $S -> $T.$L;\n",code,type,constant); });
                encode.add("}");decode.add("default -> throw new $T($S + value);\n}",IllegalArgumentException.class,"Unknown persisted enum code: ");
                yield CodeBlock.of("$T.converted($L, $L, $L)",M,scalar(v,"String"),encode.build(),decode.build());
            }
            case ENTITY -> CodeBlock.of("$T.entity($S, $S, $L)",M,sqlTable(v.relation()),SqlNames.physical(v.columns().getFirst()),v.optional());
            case COLLECTION -> {
                var element=v.children().getFirst().value(); TypeName item=ResolvedJavaTypes.javaType(element.type());
                yield CodeBlock.of("$T.collection($S, $S, $S, $L, $L, $L, $L, items -> $T.of(items.stream().map($T.class::cast).toList()))",
                        M,sqlTable(v.relation()),v.ownerColumn(),v.ownerKey(),nullable(v.marker()),v.optional(),v.ordered(),value(element),type,item);
            }
            case VALUE_OBJECT -> {
                List<CodeBlock> fields=v.children().stream().map(p->property(type,p)).toList();
                List<CodeBlock> args=new ArrayList<>();
                for(int i=0;i<v.children().size();i++) args.add(CodeBlock.of("($T) values[$L]",ResolvedJavaTypes.javaType(v.children().get(i).value().type()).box(),i));
                yield CodeBlock.of("$T.object($L, $L, $L, $L, values -> $T.of($L))",M,v.optional(),nullable(v.witness()),nullable(v.marker()),list(fields),type,CodeBlock.join(args,", "));
            }
            default -> throw new IllegalArgumentException("Unsupported persistence binding: "+symbol.identity());
        };
    }
    private CodeBlock scalar(StoragePlan.Value v,String type) {
        return CodeBlock.of("$T.scalar($S, $L, $L)",M,type,v.optional(),CodeBlock.join(v.columns().stream().map(c->CodeBlock.of("$S",SqlNames.physical(c))).toList(),", "));
    }
    private String sqlTable(String identity) { return schema.tables().stream().filter(t->t.identity().equals(identity)).findFirst().orElseThrow().sqlName(); }
    private static CodeBlock nullable(String value) { return value==null?CodeBlock.of("null"):CodeBlock.of("$S",SqlNames.physical(value)); }
    private static CodeBlock strings(List<String> values) { return list(values.stream().map(s->CodeBlock.of("$S",SqlNames.physical(s))).toList()); }
    private static CodeBlock list(List<CodeBlock> values) { if(values.isEmpty()) return CodeBlock.of("$T.of()",List.class);
        return CodeBlock.builder().add("$T.of(\n",List.class).indent().add(CodeBlock.join(values,",\n")).unindent().add("\n)").build(); }
}
