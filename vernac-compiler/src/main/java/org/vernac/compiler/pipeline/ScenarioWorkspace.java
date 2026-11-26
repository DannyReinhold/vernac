// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.vernac.compiler.modules.ModuleDescriptor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Workspace service for revisioned production and scenario source edits, with one verified runner baseline. */
public final class ScenarioWorkspace {
    public record Overlay(long baseRevision, ScenarioWorkspace workspace) { }

    private final Path root;
    private final Path main;
    private final Path tests;
    private final Map<Path, String> configuration;
    private final List<ModuleDescriptor> modules;
    private final String profile;
    private final VersionedSources sources;
    private final ScenarioPlanningSession planning;
    private final ScenarioRunSession runner;

    public ScenarioWorkspace(Path root, Map<Path, String> initialSources, Map<Path, String> configuration,
                             List<ModuleDescriptor> modules, ScenarioRunSession.Executor executor, String profile) {
        this.root = root.toAbsolutePath().normalize();
        main = this.root.resolve("src/main/vernac");
        tests = this.root.resolve("src/test/vernac");
        this.configuration = Map.copyOf(configuration);
        this.modules = List.copyOf(modules);
        this.profile = profile;
        initialSources.keySet().forEach(this::requireSourcePath);
        sources = new VersionedSources(this.root, initialSources);
        planning = new ScenarioPlanningSession(main, this.configuration, this.modules);
        runner = new ScenarioRunSession(planning, executor, profile);
    }

    /** Opens a private client workspace with its own revision sequence and verified test baseline. */
    public Overlay openOverlay(long baseRevision, ScenarioRunSession.Executor executor) {
        var snapshot = sources.snapshot(baseRevision);
        return new Overlay(snapshot.revision(),
                new ScenarioWorkspace(root, snapshot.sources(), configuration, modules, executor, profile));
    }

    public long revision() {
        return sources.snapshot().revision();
    }

    public long apply(long baseRevision, List<VersionedSources.Edit> edits) {
        var batch = List.copyOf(edits);
        sources.snapshot(baseRevision);
        for (var edit : batch) {
            switch (edit) {
                case VersionedSources.Put put -> requireSourcePath(put.path());
                case VersionedSources.Delete delete -> requireSourcePath(delete.path());
                case VersionedSources.Move move -> {
                    requireSourcePath(move.from());
                    requireSourcePath(move.to());
                }
            }
        }
        return sources.apply(baseRevision, batch).revision();
    }

    public ScenarioPlanningSession.Diagnostics check(long revision) throws IOException {
        return planning.check(inputs(revision));
    }

    /** Captures the requested revision before entering the runner, so concurrent edits cannot change this run. */
    public ScenarioRunSession.Run run(long revision, boolean opaqueChange) throws IOException {
        return runner.run(inputs(revision), opaqueChange);
    }

    public ScenarioRunSession.Run runScenario(long revision, String id) throws IOException {
        return runner.runScenario(inputs(revision), id);
    }

    private ScenarioPlanningSession.Inputs inputs(long revision) {
        var snapshot = sources.snapshot(revision);
        var modelSources = new LinkedHashMap<Path, String>();
        var testSources = new LinkedHashMap<String, String>();
        snapshot.sources().forEach((path, text) -> {
            if (path.startsWith(main)) modelSources.put(path, text);
            else testSources.put(root.relativize(path).toString().replace('\\', '/'), text);
        });
        return new ScenarioPlanningSession.Inputs(
                new VersionedSources.Snapshot(snapshot.revision(), modelSources), testSources);
    }

    private void requireSourcePath(Path path) {
        var normalized = root.resolve(path).normalize();
        if (!normalized.startsWith(main) && !normalized.startsWith(tests))
            throw new IllegalArgumentException("Source must belong to the workspace's Vernac roots: " + path);
    }
}
