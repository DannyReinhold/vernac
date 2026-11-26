// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import com.palantir.javapoet.JavaFile;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.modules.ModuleDescriptor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Plans scenario execution from checked source snapshots and a caller-confirmed baseline. */
public final class ScenarioPlanningSession {
    public record Inputs(VersionedSources.Snapshot main, Map<String, String> tests) {
        public Inputs { tests = Map.copyOf(tests); }
    }

    public static final class Prepared {
        private final ScenarioPlanningSession session;
        private final Inputs inputs;
        private final ResolvedProject project;
        private final String profile;
        private final AffectedScenarioSelector.Revision dependencies;
        private final VernacScenarioCompiler.Catalog catalog;

        private Prepared(ScenarioPlanningSession session, Inputs inputs, String profile,
                         AffectedScenarioSelector.Revision dependencies, VernacScenarioCompiler.Catalog catalog,
                         ResolvedProject project) {
            this.session = session;
            this.inputs = inputs;
            this.profile = profile;
            this.dependencies = dependencies;
            this.catalog = catalog;
            this.project = project;
        }

        public long revision() { return inputs.main().revision(); }

        public ResolvedProject project() { return project; }
    }

    public record Plan(Prepared target, List<AffectedScenarioSelector.Selection> selected) {
        public Plan { selected = List.copyOf(selected); }

        /** Emits the selected JUnit methods from this plan's already validated scenario sources. */
        public JavaFile testBridge() {
            var ids = selected.stream().map(AffectedScenarioSelector.Selection::id)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            return target.catalog.bridge(ids, target.profile);
        }
    }

    public record Diagnostics(long revision, List<CompilerDiagnostic> diagnostics) {
        public Diagnostics { diagnostics = List.copyOf(diagnostics); }

        public boolean valid() {
            return diagnostics.stream().noneMatch(diagnostic -> diagnostic.severity() == CompilerDiagnostic.Severity.ERROR);
        }
    }

    private record Analysis(Inputs inputs, Diagnostics result, ResolvedProject project,
                            VernacScenarioCompiler.Catalog catalog) { }

    private final WorkspaceCheckSession checks;
    private Analysis lastAnalysis;

    public ScenarioPlanningSession(Path root, Map<Path, String> configuration, List<ModuleDescriptor> modules) {
        checks = new WorkspaceCheckSession(root, configuration, modules);
    }

    public Diagnostics check(Inputs inputs) throws IOException {
        return analyze(inputs).result();
    }

    private synchronized Analysis analyze(Inputs inputs) throws IOException {
        if (lastAnalysis != null && lastAnalysis.inputs().equals(inputs)) return lastAnalysis;
        var check = checks.check(inputs.main());
        var diagnostics = new java.util.ArrayList<>(check.diagnostics());
        var project = check.project().orElse(null);
        VernacScenarioCompiler.Catalog catalog = null;
        if (project != null && diagnostics.stream().noneMatch(d -> d.severity() == CompilerDiagnostic.Severity.ERROR)) {
            try {
                catalog = new VernacScenarioCompiler().catalog(inputs.tests(), project);
            } catch (SemanticValidationException failure) {
                diagnostics.addAll(failure.diagnostics());
            }
        }
        lastAnalysis = new Analysis(inputs, new Diagnostics(check.revision(), diagnostics), project, catalog);
        return lastAnalysis;
    }

    /**
     * Use a previously verified execution baseline. Preparing a plan does not mark tests as passed.
     * Signal opaque changes such as Java, classpath or resource edits outside the two source snapshots.
     */
    public Plan plan(Optional<Prepared> baseline, Inputs target, boolean opaqueChange, String profile) throws IOException {
        var analysis = analyze(target);
        if (!analysis.result().valid()) throw new SemanticValidationException(analysis.result().diagnostics());
        var resolved = Objects.requireNonNull(analysis.project());
        var catalog = Objects.requireNonNull(analysis.catalog());
        var dependencies = ScenarioDependencyGraph.derive(resolved, target.tests().keySet(), catalog.references());
        var prepared = new Prepared(this, target, profile, dependencies, catalog, resolved);

        var previous = baseline.filter(candidate -> candidate.session == this && Objects.equals(candidate.profile, profile));
        var changed = new HashSet<String>();
        if (previous.isPresent()) {
            var old = previous.orElseThrow().inputs;
            changedPaths(old.main().sources(), target.main().sources()).forEach(path -> changed.add(path.toString()));
            changed.addAll(changedPaths(old.tests(), target.tests()));
        }
        var selected = AffectedScenarioSelector.select(previous.map(candidate -> candidate.dependencies),
                dependencies, changed, opaqueChange, profile);
        return new Plan(prepared, selected);
    }

    private static <K> Set<K> changedPaths(Map<K, String> old, Map<K, String> current) {
        var paths = new HashSet<>(old.keySet());
        paths.addAll(current.keySet());
        paths.removeIf(path -> Objects.equals(old.get(path), current.get(path)));
        return paths;
    }
}
