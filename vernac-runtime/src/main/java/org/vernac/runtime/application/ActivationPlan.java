// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.application;
import java.util.*;

/** Shared data-only activation graph. Neither class loading nor Spring execution occurs here. */
@org.jspecify.annotations.NullMarked
public final class ActivationPlan {
    private ActivationPlan() { }
    public record Node(String identity,String module,String kind,String javaName,List<String> dependencies,boolean persistentModule) {
        public Node(String identity,String module,String kind,String javaName,List<String> dependencies){this(identity,module,kind,javaName,dependencies,false);}
        public Node {dependencies=List.copyOf(dependencies);}
        public boolean functional() {return isFunctionalKind(kind); }
    }
    /** Single classification shared by compiler diagnostics, completion and runtime activation. */
    public static boolean isFunctionalKind(String kind) {
        return Set.of("USE_CASE","DOMAIN_SERVICE","REPOSITORY","LISTENER","DEFERRED_LISTENER",
            "EVENT_STORAGE","INBOUND_HTTP_ADAPTER","INBOUND_HTTP_ADVICE").contains(kind);
    }
    public record Plan(List<String> modules,List<Node> beans) {public Plan {modules=List.copyOf(modules);beans=List.copyOf(beans);}}
    public static Plan resolve(List<Node> nodes,List<String> roots,List<String> selected,List<String> required) {
        return resolve(nodes,roots,selected,required,false);
    }
    public static Plan resolve(List<Node> nodes,List<String> roots,List<String> selected,List<String> required,boolean persistentSupported) {
        Map<String,Node> byId=new TreeMap<>();
        for(var node:nodes)if(byId.putIfAbsent(node.identity(),node)!=null)throw new IllegalArgumentException("Duplicate runtime identity: "+node.identity());
        Set<String> active=new TreeSet<>();
        for(String root:roots) {
            if(nodes.stream().noneMatch(n->n.module().equals(root)&&(n.functional()||n.persistentModule())))throw new IllegalArgumentException("Module '"+root+"' has no activatable functions.");
            active.add(root);
        }
        for(String identity:selected) {
            var node=byId.get(identity);if(node==null)continue; // Legacy/local Java fixture without module metadata.
            if(node.persistentModule()&&!persistentSupported)throw requiresCoordinator(node.module());
            for(String dependency:node.dependencies()) {var target=byId.get(dependency);if(target!=null&&target.functional())active.add(target.module());}
        }
        boolean changed;
        do {changed=false;for(var node:nodes)if(node.functional()&&active.contains(node.module()))for(String dependency:node.dependencies()) {
            var target=byId.get(dependency);if(target!=null&&target.functional())changed|=active.add(target.module());
        }}while(changed);
        for(var node:nodes)if(active.contains(node.module())&&node.persistentModule()&&!persistentSupported)throw requiresCoordinator(node.module());
        Map<String,Set<String>> graph=new TreeMap<>();active.forEach(m->graph.put(m,new TreeSet<>()));
        for(var node:nodes)if(node.functional()&&active.contains(node.module()))for(String dependency:node.dependencies()) {
            var target=byId.get(dependency);if(target!=null&&target.functional()&&!target.module().equals(node.module()))graph.get(node.module()).add(target.module());
        }
        List<String> ordered=new ArrayList<>();Set<String> done=new HashSet<>();List<String> path=new ArrayList<>();
        for(String module:active)visit(module,graph,done,path,ordered);
        for(String identity:required) {
            var node=byId.get(identity);
            if(node!=null&&node.functional()&&!active.contains(node.module()))throw new IllegalArgumentException("Application requires '"+identity+"' from inactive module '"+node.module()+"'. Add activate "+node.module()+"; or select an implementation requiring that module.");
        }
        List<Node> beans=new ArrayList<>();for(String module:ordered)nodes.stream().filter(n->n.functional()&&n.module().equals(module)).sorted(Comparator.comparing(Node::identity)).forEach(beans::add);
        return new Plan(ordered,beans);
    }
    private static IllegalArgumentException requiresCoordinator(String module) {
        return new IllegalArgumentException("Persistent module '"+module+"' requires the migration coordinator. Use generated application registration with vernac-jdbc-spring-boot-starter; do not bypass initialization through component scanning.");
    }
    private static void visit(String module,Map<String,Set<String>> graph,Set<String> done,List<String> path,List<String> ordered) {
        if(done.contains(module))return;
        if(path.contains(module))throw new IllegalArgumentException("Functional module dependency cycle: "+String.join(" -> ",path)+" -> "+module);
        path.add(module);for(String dependency:graph.get(module))visit(dependency,graph,done,path,ordered);
        path.removeLast();done.add(module);ordered.add(module);
    }
}
