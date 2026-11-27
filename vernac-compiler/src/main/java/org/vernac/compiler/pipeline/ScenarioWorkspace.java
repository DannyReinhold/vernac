// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.application.ApplicationDefinition;
import org.vernac.compiler.modules.ModuleArtifacts;
import org.vernac.compiler.modules.ModuleDefinition;
import org.vernac.compiler.modules.ModuleDescriptor;
import org.vernac.compiler.tooling.VernacModelEdits;
import org.vernac.compiler.tooling.VernacModelQueries;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Workspace service for revisioned production and scenario source edits, with one verified runner baseline. */
public final class ScenarioWorkspace {
    public record Overlay(long baseRevision, ScenarioWorkspace workspace) { }

    record QueriedType(long revision, VernacModelQueries.Description type) { }

    record CheckedEdit(long revision, boolean applied, List<CompilerDiagnostic> diagnostics) { }

    private record SavedFiles(Map<Path, String> sources, Map<Path, String> configuration) { }

    private final Path root;
    private final Path main;
    private final Path tests;
    private final Map<Path, String> configuration;
    private final List<ModuleDescriptor> modules;
    private final String profile;
    private final VersionedSources sources;
    private final ScenarioPlanningSession planning;
    private final ScenarioRunSession runner;

    static ScenarioWorkspace openSaved(Path workspaceRoot, ScenarioRunSession.Executor executor, String profile)
            throws IOException {
        var root = workspaceRoot.toRealPath();
        if (!Files.isDirectory(root)) throw new IOException("Expected a workspace directory: " + root);
        var saved = readSavedFiles(root);
        return new ScenarioWorkspace(root, saved.sources(), saved.configuration(),
                ModuleArtifacts.editorModules(root.resolve("src/main/vernac")), executor, profile);
    }

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

    /** Publishes a complete saved-source scan against the expected shared revision. */
    long refreshSavedSources(long revision) throws IOException {
        var previous = sources.snapshot(revision).sources();
        var saved = readSavedFiles(root);
        if (!configuration.equals(saved.configuration()) || !modules.equals(ModuleArtifacts.editorModules(main))) {
            throw new IllegalStateException("Workspace configuration changed; restart the daemon to reload it");
        }
        var edits = new ArrayList<VersionedSources.Edit>();
        previous.keySet().stream().filter(path -> !saved.sources().containsKey(path))
                .forEach(path -> edits.add(new VersionedSources.Delete(path)));
        saved.sources().forEach((path, text) -> {
            if (!text.equals(previous.get(path))) edits.add(new VersionedSources.Put(path, text));
        });
        return apply(revision, edits);
    }

    private static SavedFiles readSavedFiles(Path root) throws IOException {
        var sources = new LinkedHashMap<Path, String>();
        var configuration = new LinkedHashMap<Path, String>();
        capture(root.resolve("src/main/vernac"), sources, configuration);
        capture(root.resolve("src/test/vernac"), sources, new LinkedHashMap<>());
        for (String name : List.of(ApplicationDefinition.FILE, ModuleDefinition.FILE)) {
            var path = root.resolve(name);
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) configuration.put(path, Files.readString(path));
        }
        return new SavedFiles(sources, configuration);
    }

    private static void capture(Path directory, Map<Path, String> sources, Map<Path, String> configuration)
            throws IOException {
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Expected a Vernac source directory: " + directory);
        }
        try (var paths = Files.walk(directory)) {
            for (var path : paths.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)).toList()) {
                String name = path.getFileName().toString();
                if (name.endsWith(".vernac")) sources.put(path, Files.readString(path));
                else if (name.equals(ModuleDefinition.FILE)) configuration.put(path, Files.readString(path));
            }
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    public long apply(long baseRevision, List<VersionedSources.Edit> edits) {
        var batch = validatedBatch(baseRevision, edits);
        return sources.apply(baseRevision, batch).revision();
    }

    CheckedEdit applyChecked(long baseRevision, List<VersionedSources.Edit> edits) throws IOException {
        var batch = validatedBatch(baseRevision, edits);
        var candidate = sources.preview(baseRevision, batch);
        var result = planning.check(inputs(candidate));
        if (!result.valid()) {
            sources.snapshot(baseRevision);
            return new CheckedEdit(baseRevision, false, result.diagnostics());
        }
        return new CheckedEdit(sources.apply(baseRevision, batch).revision(), true, result.diagnostics());
    }

    QueriedType describeType(long revision, String identity) throws IOException {
        var model = planning.model(inputs(revision));
        var type = new VernacModelQueries(model).describeType(identity)
                .orElseThrow(() -> new IllegalArgumentException("Unknown or unsupported model type: " + identity));
        return new QueriedType(revision, type);
    }

    VersionedSources.Put previewAddValueField(long revision, Path path, String identity, String name,
                                              String type, boolean optional) {
        var snapshot = sources.snapshot(revision);
        Path target = modelSourcePath(path);
        String text = snapshot.sources().get(target);
        if (text == null) throw new IllegalArgumentException("Source does not exist: " + path);
        var insertion = VernacModelEdits.addValueField(target, text, identity, name, type, optional);
        return new VersionedSources.Put(root.relativize(target), insertion.applyTo(text));
    }

    VersionedSources.Put previewCreateAggregate(long revision, Path path, String namespace, String name,
                                                 String idType, boolean declareId,
                                                 List<VernacModelEdits.AggregateField> fields) {
        var snapshot = sources.snapshot(revision);
        Path target = modelSourcePath(path);
        if (snapshot.sources().containsKey(target)) throw new IllegalArgumentException("Source already exists: " + path);
        String source = VernacModelEdits.aggregateSource(namespace, name, idType, declareId, fields);
        return new VersionedSources.Put(root.relativize(target), source);
    }

    private Path modelSourcePath(Path path) {
        var target = root.resolve(path).normalize();
        if (!target.startsWith(main) || !target.getFileName().toString().endsWith(".vernac"))
            throw new IllegalArgumentException("Expected a production Vernac source: " + path);
        return target;
    }

    private List<VersionedSources.Edit> validatedBatch(long baseRevision, List<VersionedSources.Edit> edits) {
        sources.snapshot(baseRevision);
        var batch = List.copyOf(edits);
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
        return batch;
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
        return inputs(sources.snapshot(revision));
    }

    private ScenarioPlanningSession.Inputs inputs(VersionedSources.Snapshot snapshot) {
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
