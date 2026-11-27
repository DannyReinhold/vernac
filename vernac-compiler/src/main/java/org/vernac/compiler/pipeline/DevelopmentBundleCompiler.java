// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.antlr.v4.runtime.tree.TerminalNode;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.ast.KeywordExpression;
import org.vernac.compiler.ast.WebViewNode;
import org.vernac.compiler.modules.ModuleDescriptor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Produces in-memory overrides for a fixed packaged application and module configuration. */
public final class DevelopmentBundleCompiler {
    public enum Status { READY, INVALID, REBUILD_REQUIRED }

    public record BehaviorPatch(String implementation, int program, byte[] layout, byte[] sourceMap) {
        public BehaviorPatch {
            layout = layout.clone();
            sourceMap = sourceMap.clone();
        }

        @Override public byte[] layout() { return layout.clone(); }
        @Override public byte[] sourceMap() { return sourceMap.clone(); }
    }

    /** Complete set of overrides relative to the packaged app. An empty bundle restores packaged resources. */
    public record Bundle(List<BehaviorPatch> behaviors, Map<String, String> templates) {
        public Bundle {
            behaviors = List.copyOf(behaviors);
            templates = Map.copyOf(templates);
        }
    }

    public record Result(long revision, Status status, List<CompilerDiagnostic> diagnostics, Optional<Bundle> bundle) {
        public Result {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private record SourcePart(String unchangedText, String replaceableKind) { }
    private record Range(int start, int end, String kind) { }
    private record Artifacts(Map<NativeBehaviorSourceMaps.ProgramKey, NativeBehaviorSourceMaps.Program> programs,
                             Map<String, String> templates, Map<Path, List<SourcePart>> bridgeInputs) { }

    private final WorkspaceCheckSession checkSession;
    private final VersionedSources.Snapshot packagedSources;
    private final Artifacts packaged;

    /**
     * Opens a session against the production source snapshot that produced the running classes.
     * Callers must recreate the session after changes to Java, dependencies, configuration or other resources.
     */
    public DevelopmentBundleCompiler(Path sourceRoot, Map<Path, String> configuration, List<ModuleDescriptor> modules,
                                     VersionedSources.Snapshot packagedSources) throws IOException {
        checkSession = new WorkspaceCheckSession(sourceRoot, configuration, modules);
        this.packagedSources = normalize(packagedSources);
        var check = checkSession.check(this.packagedSources);
        if (hasErrors(check)) throw new SemanticValidationException(check.diagnostics());
        packaged = artifacts(this.packagedSources, check.project().orElseThrow(), Map.of(), Map.of());
    }

    public Result compile(VersionedSources.Snapshot target) throws IOException {
        target = normalize(target);
        var check = checkSession.check(target);
        if (hasErrors(check)) return result(target, check, Status.INVALID, null);
        var candidate = artifacts(target, check.project().orElseThrow(), packagedSources.sources(), packaged.bridgeInputs());
        if (!packaged.bridgeInputs().equals(candidate.bridgeInputs())
                || !packaged.programs().keySet().equals(candidate.programs().keySet())
                || !packaged.templates().keySet().equals(candidate.templates().keySet())) {
            return result(target, check, Status.REBUILD_REQUIRED, null);
        }

        var behaviors = new ArrayList<BehaviorPatch>();
        candidate.programs().forEach((key, program) -> {
            var original = packaged.programs().get(key);
            if (!Arrays.equals(program.layout(), original.layout())
                    || !Arrays.equals(program.sourceMap(), original.sourceMap())) {
                behaviors.add(new BehaviorPatch(key.implementation(), key.index(), program.layout(), program.sourceMap()));
            }
        });
        behaviors.sort(Comparator.comparing(BehaviorPatch::implementation).thenComparingInt(BehaviorPatch::program));
        var templates = new HashMap<>(candidate.templates());
        templates.entrySet().removeIf(entry -> entry.getValue().equals(packaged.templates().get(entry.getKey())));
        return result(target, check, Status.READY, new Bundle(behaviors, templates));
    }

    private static VersionedSources.Snapshot normalize(VersionedSources.Snapshot snapshot) {
        var sources = new HashMap<Path, String>();
        snapshot.sources().forEach((path, text) -> {
            var absolute = path.toAbsolutePath().normalize();
            if (sources.putIfAbsent(absolute, text) != null) {
                throw new IllegalArgumentException("Duplicate source path: " + absolute);
            }
        });
        return new VersionedSources.Snapshot(snapshot.revision(), sources);
    }

    private static Result result(VersionedSources.Snapshot target, WorkspaceCheckSession.Check check,
                                 Status status, Bundle bundle) {
        return new Result(target.revision(), status, check.diagnostics(), Optional.ofNullable(bundle));
    }

    private static boolean hasErrors(WorkspaceCheckSession.Check check) {
        return check.diagnostics().stream().anyMatch(diagnostic -> diagnostic.severity() == CompilerDiagnostic.Severity.ERROR);
    }

    private static Artifacts artifacts(VersionedSources.Snapshot snapshot, ResolvedProject project,
                                       Map<Path, String> previousSources, Map<Path, List<SourcePart>> previousInputs)
            throws IOException {
        var literals = new HashMap<Path, List<KeywordExpression.Literal>>();
        var programs = NativeBehaviorSourceMaps.collect(project, literal ->
                literals.computeIfAbsent(Path.of(literal.location().sourceName()), ignored -> new ArrayList<>()).add(literal));
        var inputs = new HashMap<Path, List<SourcePart>>();
        for (var source : project.project().sources()) {
            if (source.external()) continue;
            var path = source.path();
            String text = snapshot.sources().get(path);
            if (text.equals(previousSources.get(path))) {
                inputs.put(path, previousInputs.get(path));
            } else {
                var parts = bridgeInputs(path, text, literals.getOrDefault(path, List.of()),
                        source.unit().definitions().stream().anyMatch(WebViewNode.class::isInstance));
                inputs.put(path, parts);
            }
        }
        return new Artifacts(programs, project.webTemplates(), Map.copyOf(inputs));
    }

    private static List<SourcePart> bridgeInputs(Path path, String text, List<KeywordExpression.Literal> literals,
                                                 boolean hasViews) {
        // Retain all source bytes except the ranges whose values are fully supplied by a resource.
        // In particular, even whitespace and comments in copied Java remain significant.
        var ranges = new ArrayList<Range>();
        var lineStarts = new ArrayList<Integer>();
        lineStarts.add(0);
        for (int i = text.indexOf('\n'); i >= 0; i = text.indexOf('\n', i + 1)) lineStarts.add(i + 1);
        for (var literal : literals) {
            int line = literal.location().line() - 1;
            int start = text.offsetByCodePoints(lineStarts.get(line), literal.location().column() - 1);
            if (!text.startsWith(literal.spelling(), start)) {
                throw new IllegalStateException("Native literal location does not match the checked source: " + literal.location());
            }
            ranges.add(new Range(start, start + literal.spelling().length(), literal.kind().name()));
        }
        if (hasViews) {
            var tree = new VernacSourceParser().parser(path.toString(), text).compilationUnit();
            for (var declaration : tree.topLevelDeclaration()) {
                var view = declaration.webViewDefinition();
                if (view == null) continue;
                var headerEnd = view.children.stream().filter(TerminalNode.class::isInstance)
                        .map(TerminalNode.class::cast).filter(token -> token.getText().equals(")"))
                        .findFirst().orElseThrow().getSymbol().getStopIndex() + 1;
                ranges.add(new Range(text.offsetByCodePoints(0, headerEnd),
                        text.offsetByCodePoints(0, view.getStop().getStopIndex() + 1), "view"));
            }
        }
        ranges.sort(Comparator.comparingInt(Range::start));
        var parts = new ArrayList<SourcePart>();
        int last = 0;
        for (var range : ranges) {
            parts.add(new SourcePart(text.substring(last, range.start()), range.kind()));
            last = range.end();
        }
        parts.add(new SourcePart(text.substring(last), ""));
        return List.copyOf(parts);
    }
}
