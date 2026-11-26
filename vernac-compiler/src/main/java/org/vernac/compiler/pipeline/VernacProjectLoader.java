// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.symbols.*;
import org.vernac.language.VernacNames;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/** Discovers and parses all source files before building the declaration index. */
public final class VernacProjectLoader {
    public VernacProject load(Path sourceRoot) throws IOException {
        return load(sourceRoot, Map.of());
    }

    /** Open editor snapshots override disk contents; unsaved new files are included. */
    public VernacProject load(Path sourceRoot, Map<Path, String> snapshots) throws IOException {
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        VernacProject project = loadSources(sourceRoot, snapshots, diagnostics, false, null, true).project();
        if (!diagnostics.isEmpty()) throw new SemanticValidationException(diagnostics);
        return project;
    }

    /** Collects source errors while retaining complete declarations for editor analysis. */
    public VernacProject loadForAnalysis(Path sourceRoot, Map<Path, String> snapshots,
                                        List<CompilerDiagnostic> diagnostics) throws IOException {
        return loadSources(sourceRoot, snapshots, diagnostics, true, null, true).project();
    }

    /** Opens a bounded parse session. Use a separate session for each client's source overlays. */
    public ParseSession openSession(Path sourceRoot) {
        return new ParseSession(sourceRoot.toAbsolutePath().normalize());
    }

    public record ParseStatistics(int parsed, int reused) { }

    public final class ParseSession {
        private final Path sourceRoot;
        private Map<Path, ParsedSource> strict = Map.of();
        private Map<Path, ParsedSource> editor = Map.of();
        private ParseStatistics statistics = new ParseStatistics(0, 0);

        private ParseSession(Path sourceRoot) {
            this.sourceRoot = sourceRoot;
        }

        public synchronized VernacProject load(Map<Path, String> snapshots) throws IOException {
            return loadStrict(snapshots, true);
        }

        /** Loads a complete source snapshot. Absent paths are deletions and disk is not consulted. */
        public synchronized VernacProject loadSnapshot(Map<Path, String> sources) throws IOException {
            return loadStrict(sources, false);
        }

        private VernacProject loadStrict(Map<Path, String> snapshots, boolean includeDisk) throws IOException {
            var diagnostics = new ArrayList<CompilerDiagnostic>();
            var revision = loadSources(sourceRoot, snapshots, diagnostics, false, strict, includeDisk);
            strict = revision.entries();
            statistics = revision.statistics();
            if (!diagnostics.isEmpty()) throw new SemanticValidationException(diagnostics);
            return revision.project();
        }

        public synchronized VernacProject loadForAnalysis(Map<Path, String> snapshots,
                                                            List<CompilerDiagnostic> diagnostics) throws IOException {
            var revision = loadSources(sourceRoot, snapshots, diagnostics, true, editor, true);
            editor = revision.entries();
            statistics = revision.statistics();
            return revision.project();
        }

        public synchronized ParseStatistics statistics() {
            return statistics;
        }
    }

    private record ParsedSource(String text, Optional<CompilationUnitNode> unit,
                                List<CompilerDiagnostic> diagnostics) { }

    private record ParseRevision(VernacProject project, Map<Path, ParsedSource> entries,
                                 ParseStatistics statistics) { }

    private ParseRevision loadSources(Path sourceRoot, Map<Path, String> snapshots,
            List<CompilerDiagnostic> diagnostics, boolean editorRecovery, Map<Path, ParsedSource> previous,
            boolean includeDisk) throws IOException {
        Path root = sourceRoot.toAbsolutePath().normalize();
        Map<Path, String> overlays = new TreeMap<>();
        for (var entry : snapshots.entrySet()) {
            Path path = entry.getKey().toAbsolutePath().normalize();
            if (!path.startsWith(root) || !path.getFileName().toString().endsWith(".vernac")) {
                throw new IllegalArgumentException("Snapshot is not a Vernac source below " + root + ": " + path);
            }
            overlays.put(path, entry.getValue());
        }
        if (includeDisk && !Files.isDirectory(root) && (Files.exists(root) || overlays.isEmpty())) {
            throw new IOException("Vernac source root is not a directory: " + root);
        }
        Set<Path> paths = new TreeSet<>(overlays.keySet());
        if (includeDisk && Files.isDirectory(root)) {
            try (var files = Files.walk(root)) {
                files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> path.getFileName().toString().endsWith(".vernac"))
                        .forEach(paths::add);
            }
        }
        List<VernacSourceFile> sources = new ArrayList<>();
        Map<Path, ParsedSource> entries = previous == null ? null : new HashMap<>();
        int reused = 0;
        for (Path path : paths) {
            String text = overlays.containsKey(path) ? overlays.get(path) : Files.readString(path);
            var parsed = previous == null ? null : previous.get(path);
            if (parsed != null && parsed.text().equals(text)) {
                reused++;
            } else {
                parsed = parse(path, text, editorRecovery);
            }
            if (entries != null) entries.put(path, parsed);
            diagnostics.addAll(parsed.diagnostics());
            if (parsed.unit().isPresent()) {
                var unit = parsed.unit().orElseThrow();
                sources.add(new VernacSourceFile(path, unit));
                validateNamespace(root, path, unit, diagnostics);
            }
        }
        var project = index(root, sources, diagnostics);
        return new ParseRevision(project, entries == null ? Map.of() : Map.copyOf(entries),
                new ParseStatistics(paths.size() - reused, reused));
    }

    private static ParsedSource parse(Path path, String text, boolean editorRecovery) {
        var diagnostics = new ArrayList<CompilerDiagnostic>();
        Optional<CompilationUnitNode> unit = Optional.empty();
        try {
            unit = editorRecovery
                    ? new EditorSourceParser().analyze(path.toString(), text, diagnostics).map(EditorSourceParser.Analysis::unit)
                    : Optional.of(new VernacSourceParser().parse(path.toString(), text));
        } catch (SemanticValidationException failure) {
            diagnostics.addAll(failure.diagnostics());
        }
        return new ParsedSource(text, unit, List.copyOf(diagnostics));
    }

    /** Indexes parsed snapshots; filesystem layout is checked by load, not by this method. */
    public VernacProject index(Path sourceRoot, List<VernacSourceFile> sources) {
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        VernacProject project = index(sourceRoot, sources, diagnostics);
        if (!diagnostics.isEmpty()) throw new SemanticValidationException(diagnostics);
        return project;
    }

    public VernacProject index(Path sourceRoot, List<VernacSourceFile> sources,
                                List<CompilerDiagnostic> diagnostics) {
        Path root = sourceRoot.toAbsolutePath().normalize();
        List<TypeSymbol> symbols = new ArrayList<>();
        for (var source : sources) {
            if (!VernacNames.isNamespace(source.unit().namespace())) {
                diagnostics.add(CompilerDiagnostic.error(source.unit().location(),
                        "Invalid namespace '" + source.unit().namespace() + "'."));
                continue;
            }
            for (var definition : source.unit().definitions()) {
                if (definition instanceof RoleNode || definition instanceof WebViewNode) continue;
                var declaration = describe(definition);
                addSymbol(source, declaration.name(), declaration.kind(), definition.location(), symbols, diagnostics);
                CollectionDeclaration.of(definition).forEach(collection ->
                        addSymbol(source, collection.name(), TypeSymbol.Kind.COLLECTION,
                                collection.definition().location(), symbols, diagnostics));
            }
        }
        org.vernac.compiler.vocabulary.Vocabulary.collect(root,sources,diagnostics);
        var index = new ProjectSymbolIndex(symbols);
        for (var problem : index.problems()) {
            String origins = problem.declarations().stream().map(symbol -> symbol.location().toString())
                    .collect(Collectors.joining(", "));
            for (var symbol : problem.declarations()) {
                diagnostics.add(CompilerDiagnostic.error(symbol.location(),
                        problem.message() + " Declaration locations: " + origins));
            }
        }
        return new VernacProject(root, sources, index);
    }

    private void validateNamespace(Path root, Path file, CompilationUnitNode unit,
                                   List<CompilerDiagnostic> diagnostics) {
        if (!VernacNames.isNamespace(unit.namespace())) {
            diagnostics.add(CompilerDiagnostic.error(unit.location(),
                    "Invalid namespace '" + unit.namespace() + "': expected a Java-compatible qualified name."));
            return;
        }
        Path expectedDirectory = root.resolve(unit.namespace().replace('.', '/'));
        if (!file.getParent().equals(expectedDirectory)) {
            diagnostics.add(CompilerDiagnostic.error(unit.location(),
                    "Namespace '" + unit.namespace() + "' requires directory '"
                            + root.relativize(expectedDirectory) + "' relative to the source root, but file is in '"
                            + root.relativize(file.getParent()) + "'. Move the file or correct its namespace."));
        }
    }

    private void addSymbol(VernacSourceFile source, String name, TypeSymbol.Kind kind,
                           SourceLocation location, List<TypeSymbol> symbols,
                           List<CompilerDiagnostic> diagnostics) {
        if (name.startsWith("__Vernac")) {
            diagnostics.add(CompilerDiagnostic.error(location, "Type prefix '__Vernac' is reserved for generated implementation types."));
            return;
        }
        try {
            var identity = new TypeIdentity(source.unit().namespace(), name);
            symbols.add(new TypeSymbol(identity, kind, source.path(), location));
        } catch (IllegalArgumentException e) {
            diagnostics.add(CompilerDiagnostic.error(location, e.getMessage()));
        }
    }

    private record Declaration(String name, TypeSymbol.Kind kind) { }

    private Declaration describe(TopLevelDefinition definition) {
        return switch (definition) {
            case IdDeclarationNode node -> new Declaration(node.name(), TypeSymbol.Kind.ID);
            case ValueObjectNode node -> new Declaration(node.name(), node.isEnum()
                    ? TypeSymbol.Kind.ENUM : TypeSymbol.Kind.VALUE_OBJECT);
            case EntityNode node -> new Declaration(node.name(), TypeSymbol.Kind.ENTITY);
            case AggregateNode node -> new Declaration(node.name(), TypeSymbol.Kind.AGGREGATE);
            case EventNode node -> new Declaration(node.name(), TypeSymbol.Kind.EVENT);
            case RepositoryNode node -> new Declaration(node.name(), TypeSymbol.Kind.REPOSITORY);
            case InboundHttpAdapterNode node -> new Declaration(node.name(), TypeSymbol.Kind.INBOUND_HTTP_ADAPTER);
            case OutboundAdapterNode node -> new Declaration(node.name(), TypeSymbol.Kind.OUTBOUND_ADAPTER);
            case PortNode node -> new Declaration(node.name(), TypeSymbol.Kind.PORT);
            case WebViewNode node -> throw new IllegalArgumentException("Views are not domain types");
            case RoleNode node -> throw new IllegalArgumentException("Roles are not domain types");
            case PermissionNode node -> new Declaration(node.name(), TypeSymbol.Kind.PERMISSION);
            case UseCaseNode node -> new Declaration(node.name(), TypeSymbol.Kind.USE_CASE);
            case DomainServiceNode node -> new Declaration(node.name(), TypeSymbol.Kind.DOMAIN_SERVICE);
            case ListenerNode node -> new Declaration(node.listenerName(), TypeSymbol.Kind.LISTENER);
        };
    }
}
