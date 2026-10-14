// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.application;

import java.util.*;
import com.palantir.javapoet.*;
import javax.lang.model.element.Modifier;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.modules.ModuleDescriptor;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.symbols.ResolvedType;

/** Generates explicit Spring registration; adapters remain ordinary compiled classes. */
public final class ApplicationBindings {
    private ApplicationBindings() { }
    private static final ClassName BINDINGS=ClassName.get("org.vernac.runtime.application","AdapterBindings");
    private static ClassName declared(String identity) {
        int dot=identity.lastIndexOf('.');
        return ClassName.get(identity.substring(0,dot),identity.substring(dot+1));
    }
    private static final ClassName PLAN=ClassName.get("org.vernac.runtime.application","ActivationPlan");
    public static List<org.vernac.runtime.application.ActivationPlan.Node> nodes(ResolvedProject project,List<ModuleDescriptor> modules) {
        List<org.vernac.runtime.application.ActivationPlan.Node> nodes=new ArrayList<>();
        for(var module:modules)if(module.persistent())nodes.add(new org.vernac.runtime.application.ActivationPlan.Node("@migration:"+module.module(),module.module(),"MIGRATIONS","",List.of(),true));
        for(var module:modules)for(var component:module.components())nodes.add(new org.vernac.runtime.application.ActivationPlan.Node(component.identity(),module.module(),component.kind(),component.javaName(),component.dependencies(),module.persistent()));
        for(var source:project.project().sources())if(!source.external())for(var top:source.unit().definitions())if(top instanceof OutboundAdapterNode adapter)
            nodes.add(new org.vernac.runtime.application.ActivationPlan.Node(source.unit().namespace()+"."+adapter.name(),"@application","OUTBOUND_ADAPTER",source.unit().namespace()+".adapter.outbound."+adapter.name(),dependencies(adapter.dependencies(),project)));
        return List.copyOf(nodes);
    }
    private static List<String> dependencies(List<FieldNode> fields,ResolvedProject project) {
        return fields.stream().map(f->project.types().get(f.type())).filter(t->t instanceof ResolvedType.Declared).map(t->((ResolvedType.Declared)t).symbol().identity().qualifiedName()).toList();
    }
    public static List<String> requiredFunctions(ResolvedProject project,List<ModuleDescriptor> modules) {
        Set<String> foreign=new HashSet<>();for(var module:modules)for(var component:module.components())if(!component.kind().equals("OUTBOUND_ADAPTER"))foreign.add(component.identity());
        Set<String> required=new TreeSet<>();
        for(var source:project.project().sources())if(!source.external())for(var top:source.unit().definitions()) {
            List<FieldNode> fields=top instanceof UseCaseNode u?u.injections():top instanceof DomainServiceNode service?service.injections():top instanceof ListenerNode listener?listener.operation().injections():top instanceof InboundHttpAdapterNode inbound?inbound.dependencies():List.of();
            if(top instanceof ListenerNode listener && listener.deferred()) {
                var event=project.typeOf(listener.eventType());
                if(event instanceof ResolvedType.Declared declared && project.project().sources().stream().anyMatch(s->s.external()&&s.path().equals(declared.symbol().sourceFile()))) {
                    String storage="@storage:"+declared.symbol().identity().qualifiedName();
                    if(!foreign.contains(storage))throw new IllegalArgumentException("Dependency event has no prepared module storage: "+declared.symbol().identity().qualifiedName()+". Rebuild its producer with reviewed event migrations.");
                    required.add(storage);
                }
            }
            dependencies(fields,project).stream().filter(foreign::contains).forEach(required::add);
        }
        return List.copyOf(required);
    }
    /** Union of selected/profile-eligible components determines the application's owned delivery schema. */
    public static boolean requiresDelivery(ApplicationDefinition app,ResolvedProject project,List<ModuleDescriptor> modules) {
        Set<String> selected=new TreeSet<>();
        for(var binding:app.bindings()) {
            if(binding.adapter()!=null)selected.add(binding.adapter());
            else {
                for(var module:modules)for(var d:module.exports())if(d.kind().equals("OUTBOUND_ADAPTER")&&d.element().equals(binding.port()))selected.add(d.identity());
                for(var source:project.project().sources())if(!source.external())for(var top:source.unit().definitions())if(top instanceof OutboundAdapterNode adapter
                        && project.typeOf(adapter.portType()) instanceof ResolvedType.Declared port && port.symbol().identity().qualifiedName().equals(binding.port()))selected.add(source.unit().namespace()+"."+adapter.name());
            }
        }
        var plan=org.vernac.runtime.application.ActivationPlan.resolve(nodes(project,modules),app.activations().stream().map(ApplicationDefinition.Activation::module).toList(),List.copyOf(selected),List.of(),true);
        return plan.beans().stream().anyMatch(n->n.kind().equals("DEFERRED_LISTENER"));
    }
    private static CodeBlock strings(List<String> values) {
        return CodeBlock.of("$T.of($L)",List.class,CodeBlock.join(values.stream().map(v->CodeBlock.of("$S",v)).toList(),", "));
    }
    public static String schemaResource(String application){return "META-INF/vernac/applications/"+application+"/schema.json";}
    public static final String IMPORTS="META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
    public record Generated(List<JavaFile> files,Optional<String> configuration) { }
    public static Generated generate(ApplicationDefinition app,ResolvedProject project,List<ModuleDescriptor> modules,List<JavaFile> original,boolean publishing) {
        Set<String> bound=new TreeSet<>();app.bindings().forEach(b->bound.add(b.port()));
        Map<String,String> adapters=new TreeMap<>();
        for(var module:modules)for(var d:module.exports())if(d.kind().equals("OUTBOUND_ADAPTER")&&bound.contains(d.element()))adapters.put(d.identity(),d.javaName());
        Set<String> strip=new HashSet<>(),explicitHttp=new HashSet<>();
        for(var source:project.project().sources())if(!source.external())for(var top:source.unit().definitions()) {
            if(publishing&&top instanceof InboundHttpAdapterNode inbound) {
                explicitHttp.add(source.unit().namespace()+".adapter.inbound.http."+inbound.name());
                explicitHttp.add(source.unit().namespace()+".adapter.inbound.http."+inbound.name()+"Errors");
            }
            if(publishing&&top instanceof EventNode event)strip.add(source.unit().namespace()+".adapter.outbound.jdbc.event.Jdbc"+event.name()+"Events");
            if(publishing&&top instanceof RepositoryNode repository)strip.add(source.unit().namespace()+".adapter.outbound.jdbc.Jdbc"+repository.name());
            if(publishing&&top instanceof DomainServiceNode service)strip.add(source.unit().namespace()+".domain."+service.name());
            if(publishing&&top instanceof ListenerNode listener)strip.add(source.unit().namespace()+".adapter.inbound.event."+listener.name());
            if(publishing&&top instanceof UseCaseNode usecase)strip.add(source.unit().namespace()+".usecase."+usecase.name());
            if(top instanceof OutboundAdapterNode adapter) {
            var resolved=project.typeOf(adapter.portType());
            if(resolved instanceof ResolvedType.Declared port) {
                String javaName=source.unit().namespace()+".adapter.outbound."+adapter.name();
                if(publishing||bound.contains(port.symbol().identity().qualifiedName()))strip.add(javaName);
                if(bound.contains(port.symbol().identity().qualifiedName()))adapters.put(source.unit().namespace()+"."+adapter.name(),javaName);
            }
        }
        }
        List<JavaFile> files=new ArrayList<>();
        for(var file:original) {
            if(strip.contains(file.packageName()+"."+file.typeSpec().name())||explicitHttp.contains(file.packageName()+"."+file.typeSpec().name())) {
                var spec=file.typeSpec();
                var builder=TypeSpec.classBuilder(spec.name()).addModifiers(spec.modifiers().toArray(Modifier[]::new))
                    .addAnnotations(spec.annotations().stream().filter(a->!Set.of("org.springframework.stereotype.Component","org.springframework.stereotype.Service","org.springframework.stereotype.Repository").contains(a.type().toString())).toList())
                    .addJavadoc(spec.javadoc()).superclass(spec.superclass()).addSuperinterfaces(spec.superinterfaces())
                    .addTypeVariables(spec.typeVariables()).addFields(spec.fieldSpecs()).addMethods(spec.methodSpecs()).addTypes(spec.typeSpecs());
                if(explicitHttp.contains(file.packageName()+"."+file.typeSpec().name()))builder.addAnnotation(org.vernac.runtime.application.ExplicitModuleComponent.class);
                if(!spec.staticBlock().isEmpty())builder.addStaticBlock(spec.staticBlock());
                if(!spec.initializerBlock().isEmpty())builder.addInitializerBlock(spec.initializerBlock());
                files.add(JavaFile.builder(file.packageName(),builder.build()).indent("    ").skipJavaLangImports(true).build());
            } else files.add(file);
        }
        if(app.bindings().isEmpty()&&app.activations().isEmpty())return new Generated(List.copyOf(files),Optional.empty());
        var owner=ClassName.get(app.name()+".configuration","__VernacApplication");
        var bindings=CodeBlock.builder().add("$T.of(\n",List.class);int index=0;
        for(var binding:app.bindings()) {
            if(index++>0)bindings.add(",\n");
            String port=project.project().symbols().find(binding.port()).orElseThrow().identity().namespace()+".domain."+project.project().symbols().find(binding.port()).orElseThrow().identity().name();
            bindings.add("new $T.Binding($T.class, $S, $L, $L)",BINDINGS,declared(port),binding.port(),binding.property()==null?CodeBlock.of("null"):CodeBlock.of("$S",binding.property()),binding.adapter()==null?CodeBlock.of("null"):CodeBlock.of("$S",binding.adapter()));
        }
        bindings.add("\n)");
        var candidates=CodeBlock.builder().add("$T.ofEntries(\n",Map.class);index=0;
        for(var entry:adapters.entrySet()){if(index++>0)candidates.add(",\n");candidates.add("$T.entry($S, $T.class)",Map.class,entry.getKey(),declared(entry.getValue()));}candidates.add("\n)");
        var graph=CodeBlock.builder().add("$T.of(\n",List.class);index=0;
        var functions=CodeBlock.builder().add("$T.ofEntries(\n",Map.class);int functionCount=0;
        for(var node:nodes(project,modules)) {
            if(index++>0)graph.add(",\n");graph.add("new $T.Node($S,$S,$S,$S,$L,$L)",PLAN,node.identity(),node.module(),node.kind(),node.javaName(),strings(node.dependencies()),node.persistentModule());
            if(node.functional()) {if(functionCount++>0)functions.add(",\n");functions.add("$T.entry($S,$T.class)",Map.class,node.identity(),declared(node.javaName()));}
        }
        graph.add("\n)");functions.add("\n)");
        var constructor=CodeBlock.builder().add("new $T(environment, $L, $L, $L, $L, $L, $L",BINDINGS,bindings.build(),candidates.build(),graph.build(),strings(app.activations().stream().map(ApplicationDefinition.Activation::module).toList()),strings(requiredFunctions(project,modules)),functions.build());
        if(modules.stream().anyMatch(ModuleDescriptor::persistent))constructor.add(", $S",schemaResource(app.name()));constructor.add(")");
        var config=TypeSpec.classBuilder(owner).addModifiers(Modifier.PUBLIC).addAnnotation(org.jspecify.annotations.NullMarked.class)
            .addAnnotation(AnnotationSpec.builder(ClassName.get("org.springframework.context.annotation","Configuration")).addMember("proxyBeanMethods","false").build())
            .addAnnotation(ClassName.get("org.springframework.transaction.annotation","EnableTransactionManagement"))
            .addMethod(MethodSpec.methodBuilder("__vernacAdapterBindings").addModifiers(Modifier.PUBLIC,Modifier.STATIC)
                .addAnnotation(ClassName.get("org.springframework.context.annotation","Bean"))
                .returns(BINDINGS)
                .addParameter(ClassName.get("org.springframework.core.env","Environment"),"environment")
                .addStatement("return $L",constructor.build()).build()).build();
        files.add(JavaFile.builder(owner.packageName(),config).indent("    ").build());
        return new Generated(List.copyOf(files),Optional.of(owner.canonicalName()));
    }
}
