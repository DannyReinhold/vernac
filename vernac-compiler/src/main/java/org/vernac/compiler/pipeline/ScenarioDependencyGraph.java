// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.ast.IdDeclarationNode;
import org.vernac.compiler.ast.ValueObjectNode;
import org.vernac.compiler.symbols.ResolvedType;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Derives the portion of the source graph that the compiler can certify as complete. */
public final class ScenarioDependencyGraph {
    private ScenarioDependencyGraph() { }

    public static AffectedScenarioSelector.Revision inspect(ResolvedProject project, Map<String, String> testSources) {
        if (project.diagnostics().stream().anyMatch(d -> d.severity() == CompilerDiagnostic.Severity.ERROR))
            throw new IllegalArgumentException("Scenario dependency analysis requires a successfully resolved model");
        var cases = new VernacScenarioCompiler().inspect(testSources, project);
        var graph = new LinkedHashMap<String, Set<String>>();
        testSources.keySet().forEach(source -> graph.put(source, Set.of()));
        for (var source : project.project().sources()) {
            String path = source.path().toString();
            // A production file with opaque dependencies must remain uncertified.
            graph.remove(path);
            if (source.external()) continue;
            var inputs = new LinkedHashSet<String>();
            if (declarativeDependencies(source, project, inputs)) graph.put(path, inputs);
        }
        return new AffectedScenarioSelector.Revision(cases, new AffectedScenarioSelector.Graph(graph));
    }

    private static boolean declarativeDependencies(VernacSourceFile source, ResolvedProject project, Set<String> inputs) {
        for (var definition : source.unit().definitions()) {
            switch (definition) {
                case IdDeclarationNode id -> {
                    if (!id.collections().isEmpty()) return false;
                }
                case ValueObjectNode value -> {
                    if (!value.methods().isEmpty() || !value.validations().isEmpty()
                            || !value.collections().isEmpty() || !value.javaImports().isEmpty()) return false;
                    for (var field : value.fields()) {
                        switch (project.types().get(field.type())) {
                            case ResolvedType.Declared declared -> inputs.add(declared.symbol().sourceFile().toString());
                            case ResolvedType.Builtin ignored -> { }
                            case null, default -> { return false; }
                        }
                    }
                }
                default -> { return false; }
            }
        }
        return true;
    }
}
