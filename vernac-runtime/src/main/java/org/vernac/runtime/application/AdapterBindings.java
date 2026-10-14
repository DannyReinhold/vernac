// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.application;

import java.util.*;
import org.jspecify.annotations.*;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.*;
import org.springframework.beans.factory.support.*;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/** Registers only selected, compiler-reviewed adapters. No arbitrary class loading. */
@NullMarked
public final class AdapterBindings implements BeanDefinitionRegistryPostProcessor, Ordered, org.springframework.beans.factory.BeanClassLoaderAware {
    public record Binding(Class<?> port,String identity,@Nullable String property,@Nullable String fixedAdapter) { }
    public static final String INITIALIZATION="__vernacModuleInitialization";
    public static final String STRATEGY="__vernacModuleMigrationStrategy";
    private ActivationPlan.@Nullable Plan activationPlan;
    private @Nullable String applicationSchema;
    private ClassLoader classLoader=AdapterBindings.class.getClassLoader();
    @Override public void setBeanClassLoader(ClassLoader loader){classLoader=loader;}
    public ActivationPlan.Plan plan(){return Objects.requireNonNull(activationPlan,"Activation plan has not been resolved");}
    public List<String> persistentModules(){return plan().modules().stream().filter(m->nodes.stream().anyMatch(n->n.module().equals(m)&&n.persistentModule())).toList();}
    private boolean persistentActive;
    private final Environment environment;
    private final List<Binding> bindings;
    private final Map<String,Class<?>> candidates;
    private final List<ActivationPlan.Node> nodes;
    private final List<String> activations,required;
    private final Map<String,Class<?>> functions;
    public AdapterBindings(Environment environment,List<Binding> bindings,Map<String,Class<?>> candidates) {
        this(environment,bindings,candidates,List.of(),List.of(),List.of(),Map.of());
    }
    public AdapterBindings(Environment environment,List<Binding> bindings,Map<String,Class<?>> candidates,
            List<ActivationPlan.Node> nodes,List<String> activations,List<String> required,Map<String,Class<?>> functions) {
        this.nodes=List.copyOf(nodes);this.activations=List.copyOf(activations);this.required=List.copyOf(required);this.functions=Map.copyOf(functions);
        this.environment=Objects.requireNonNull(environment);this.bindings=List.copyOf(bindings);this.candidates=Map.copyOf(candidates);
    }
    public AdapterBindings(Environment environment,List<Binding> bindings,Map<String,Class<?>> candidates,
            List<ActivationPlan.Node> nodes,List<String> activations,List<String> required,Map<String,Class<?>> functions,String applicationSchema) {
        this(environment,bindings,candidates,nodes,activations,required,functions);this.applicationSchema=applicationSchema;
    }
    @Override public int getOrder() {return Ordered.LOWEST_PRECEDENCE;}
    @Override public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        Set<String> bound=new HashSet<>();List<String> selectedAdapters=new ArrayList<>();
        for(var binding:bindings) {
            if(!bound.add(binding.identity()))throw failure(binding,"Duplicate binding.");
            String selected=binding.fixedAdapter();
            if(binding.property()!=null)selected=environment.getProperty(binding.property());
            if(selected==null||selected.isBlank())throw failure(binding,"Missing adapter selection"+(binding.property()==null?".":" in property '"+binding.property()+"'."));
            Class<?> implementation=candidates.get(selected);
            if(implementation==null||!binding.port().isAssignableFrom(implementation))throw failure(binding,"Selected identity is unavailable or does not implement this port. Available adapters: "+candidates.entrySet().stream().filter(e->binding.port().isAssignableFrom(e.getValue())).map(Map.Entry::getKey).sorted().toList()+". Selection value omitted.");
            if(registry.containsBeanDefinition(selected))throw failure(binding,"Adapter identity already registered: "+selected);
            for(String name:registry.getBeanDefinitionNames())if(implementation.getName().equals(registry.getBeanDefinition(name).getBeanClassName()))throw failure(binding,"Selected adapter is already registered as '"+name+"'. Avoid duplicate component scanning or bean declarations.");
            selectedAdapters.add(selected);
            var definition=new RootBeanDefinition(implementation);
            definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
            definition.setLazyInit(false);
            registry.registerBeanDefinition(selected,definition);
        }
        var plan=ActivationPlan.resolve(nodes,activations,selectedAdapters,required,applicationSchema!=null);activationPlan=plan;
        persistentActive=nodes.stream().anyMatch(n->n.persistentModule()&&plan.modules().contains(n.module()));
        if(persistentActive) {
            registerInfrastructure(registry,STRATEGY,"org.vernac.runtime.jdbc.modules.ModuleMigrationStrategy",this,applicationSchema);
            registerInfrastructure(registry,INITIALIZATION,"org.vernac.runtime.jdbc.modules.ModuleInitialization");
            for(String adapter:selectedAdapters)registry.getBeanDefinition(adapter).setDependsOn(INITIALIZATION);
        }
        for(var node:plan.beans()) {
            if(registry.containsBeanDefinition(node.identity()))throw new IllegalStateException("Module '"+node.module()+"': duplicate bean identity "+node.identity());
            if(node.kind().equals("INBOUND_HTTP_ADAPTER"))try {
                Class.forName("org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping",false,classLoader);
            }catch(ClassNotFoundException|LinkageError error){throw new IllegalStateException("Module HTTP entrypoint '"+node.identity()+"' requires vernac-runtime-http and Spring MVC (spring-boot-starter-web).",error);}
            Class<?> type=functions.get(node.identity());
            if(type==null)throw new IllegalStateException("Module '"+node.module()+"': unavailable compiled component "+node.identity());
            for(String name:registry.getBeanDefinitionNames())if(type.getName().equals(registry.getBeanDefinition(name).getBeanClassName()))throw new IllegalStateException("Module component already registered: "+node.identity()+". Avoid component scanning dependency modules.");
            var definition=new RootBeanDefinition(type);if(persistentActive)definition.setDependsOn(INITIALIZATION);definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);definition.setLazyInit(false);
            registry.registerBeanDefinition(node.identity(),definition);
        }
    }
    private void registerInfrastructure(BeanDefinitionRegistry registry,String name,String javaName,Object... args) {
        if(registry.containsBeanDefinition(name))throw new IllegalStateException("Duplicate Vernac module infrastructure bean: "+name);
        try {
            var definition=new RootBeanDefinition(Class.forName(javaName,false,classLoader));definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
            for(int i=0;i<args.length;i++)definition.getConstructorArgumentValues().addIndexedArgumentValue(i,args[i]);
            registry.registerBeanDefinition(name,definition);
        }catch(ClassNotFoundException|LinkageError e){throw new IllegalStateException("Persistent module activation requires vernac-jdbc-spring-boot-starter and Flyway on the application classpath.",e);}
    }
    @Override public void postProcessBeanFactory(ConfigurableListableBeanFactory factory) throws BeansException {
        if(persistentActive)try {
            var type=Class.forName("org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy",false,classLoader);
            var names=factory.getBeanNamesForType(type,true,false);
            if(names.length!=1||!names[0].equals(STRATEGY))throw new IllegalStateException("Persistent module activation supplies the Flyway migration strategy. Remove competing strategies; do not bypass module migration ordering.");
        }catch(ClassNotFoundException e){throw new IllegalStateException("Persistent modules require Spring Boot Flyway initialization",e);}
        for(var binding:bindings) {
            var names=factory.getBeanNamesForType(binding.port(),true,false);
            if(names.length!=1)throw failure(binding,"Expected exactly one selected implementation; found "+Arrays.toString(names)+". Remove competing bean registrations.");
        }
    }
    private static IllegalStateException failure(Binding binding,String reason) {return new IllegalStateException("Vernac binding '"+binding.identity()+"': "+reason);}
}
