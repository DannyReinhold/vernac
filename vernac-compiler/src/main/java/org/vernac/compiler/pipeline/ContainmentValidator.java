// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.symbols.*;
import java.util.*;

/** Type-level containment, including optional fields and collection elements. IDs are leaves. */
final class ContainmentValidator {
    private record Edge(String owner, String target, FieldNode field) { }
    List<CompilerDiagnostic> validate(VernacProject project, Map<TypeNode, ResolvedType> types) {
        Map<String,List<Edge>> graph = new LinkedHashMap<>();
        for (var source : project.sources()) for (var definition : source.unit().definitions()) {
            var model = MutableDomain.of(definition);
            if (model.isEmpty()) continue;
            String owner = source.unit().namespace() + "." + model.get().name();
            List<Edge> edges = new ArrayList<>();
            graph.put(owner, edges);
            for (var field : model.get().fields()) {
                if (!(types.get(field.type()) instanceof ResolvedType.Declared declared)) continue;
                TypeSymbol target = declared.symbol();
                if (target.kind() == TypeSymbol.Kind.COLLECTION) {
                    var c = CollectionTypes.find(project, target.identity());
                    if (c.isEmpty()) continue;
                    target = project.symbols().find(target.identity().namespace() + "." + c.get().elementName()).orElse(null);
                }
                if (target != null && target.kind() == TypeSymbol.Kind.ENTITY)
                    edges.add(new Edge(owner, target.identity().qualifiedName(), field));
            }
        }
        List<CompilerDiagnostic> errors = new ArrayList<>();
        Set<String> complete = new HashSet<>();
        LinkedHashSet<String> active = new LinkedHashSet<>();
        List<Edge> path = new ArrayList<>();
        for (String owner : graph.keySet()) visit(owner, graph, complete, active, path, errors);
        return errors;
    }
    private void visit(String owner, Map<String,List<Edge>> graph, Set<String> complete,
                       Set<String> active, List<Edge> path, List<CompilerDiagnostic> errors) {
        if (complete.contains(owner)) return;
        active.add(owner);
        for (Edge edge : graph.getOrDefault(owner, List.of())) {
            path.add(edge);
            if (active.contains(edge.target())) {
                int start = 0;
                while (start < path.size() && !path.get(start).owner().equals(edge.target())) start++;
                String cycle = String.join(" -> ", path.subList(start, path.size()).stream()
                        .map(e -> e.owner()+"."+e.field().name()).toList()) + " -> " + edge.target();
                errors.add(CompilerDiagnostic.error(edge.field().location(), "Cyclic containment: " + cycle
                        + ". Use an id for the back-reference. Optional fields and collections also count as containment."));
            } else visit(edge.target(), graph, complete, active, path, errors);
            path.removeLast();
        }
        active.remove(owner);
        complete.add(owner);
    }
}
