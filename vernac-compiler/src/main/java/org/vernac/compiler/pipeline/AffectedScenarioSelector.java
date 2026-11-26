// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.vernac.compiler.pipeline.VernacScenarioCompiler.ScenarioReferences;

/** Computes a conservative execution list from the previous and current dependency data. */
public final class AffectedScenarioSelector {
    private AffectedScenarioSelector() { }

    /**
     * Edges point from a source to the sources it depends on. An empty set certifies a leaf.
     * Omit a source if its dependency information is incomplete.
     */
    public record Graph(Map<String, Set<String>> dependencies) {
        public Graph {
            var copy = new LinkedHashMap<String, Set<String>>();
            dependencies.forEach((source, inputs) -> copy.put(source, Set.copyOf(inputs)));
            dependencies = Map.copyOf(copy);
        }
    }

    public record Revision(List<ScenarioReferences> cases, Graph graph) {
        public Revision { cases = List.copyOf(cases); }
    }

    public enum Reason {
        NO_BASELINE, OPAQUE_CHANGE, NEW_OR_CHANGED_CASE, JAVA_FIXTURE, CHANGED_DEPENDENCY, INCOMPLETE_GRAPH,
        EXPLICIT_SELECTION
    }

    public record Selection(String id, Reason reason) { }

    /**
     * Changed sources use the same identities as scenario references and graph keys.
     * Set opaqueChange for changes the source graph cannot model, such as classpath or configuration.
     */
    public static List<Selection> select(Optional<Revision> previous, Revision current, Set<String> changed,
                                         boolean opaqueChange, String profile) {
        var oldCases = new LinkedHashMap<String, ScenarioReferences>();
        previous.ifPresent(revision -> revision.cases().forEach(test -> oldCases.put(test.id(), test)));
        var result = new java.util.ArrayList<Selection>();
        for (var test : current.cases()) {
            if (!VernacScenarioCompiler.includesProfile(profile, test.profile())) continue;
            var reason = reason(previous, current, oldCases.get(test.id()), test, changed, opaqueChange);
            reason.ifPresent(value -> result.add(new Selection(test.id(), value)));
        }
        return List.copyOf(result);
    }

    private static Optional<Reason> reason(Optional<Revision> previous, Revision current, ScenarioReferences old,
                                           ScenarioReferences test, Set<String> changed, boolean opaqueChange) {
        if (previous.isEmpty()) return Optional.of(Reason.NO_BASELINE);
        if (opaqueChange) return Optional.of(Reason.OPAQUE_CHANGE);
        if (!test.equals(old) || changed.contains(test.location().sourceName()))
            return Optional.of(Reason.NEW_OR_CHANGED_CASE);
        if (changed.isEmpty()) return Optional.empty();
        if (test.usesJavaFixture()) return Optional.of(Reason.JAVA_FIXTURE);

        var before = reach(old, previous.orElseThrow().graph(), changed);
        var after = reach(test, current.graph(), changed);
        if (before.affected() || after.affected()) return Optional.of(Reason.CHANGED_DEPENDENCY);
        if (before.incomplete() || after.incomplete()) return Optional.of(Reason.INCOMPLETE_GRAPH);
        return Optional.empty();
    }

    private record Reach(boolean affected, boolean incomplete) { }

    private static Reach reach(ScenarioReferences test, Graph graph, Set<String> changed) {
        var pending = new ArrayDeque<String>();
        pending.add(test.location().sourceName());
        test.directReferences().forEach(reference -> pending.add(reference.declaration().sourceName()));
        var visited = new HashSet<String>();
        boolean incomplete = false;
        while (!pending.isEmpty()) {
            var source = pending.removeFirst();
            if (!visited.add(source)) continue;
            if (changed.contains(source)) return new Reach(true, incomplete);
            var inputs = graph.dependencies().get(source);
            if (inputs == null) incomplete = true;
            else pending.addAll(inputs);
        }
        return new Reach(false, incomplete);
    }
}
