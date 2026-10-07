// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.LanguageClient;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.pipeline.ProjectTypeResolver;
import org.vernac.compiler.pipeline.VernacProjectLoader;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

/** Serial project snapshots: editor text wins over disk until the document is closed. */
final class VernacProjectDiagnostics {
    private record Document(String uri, String text, int version) { }
    private final Map<Path, Document> open = new LinkedHashMap<>();
    private final Set<Path> folders = new LinkedHashSet<>();
    private final Set<String> published = new LinkedHashSet<>();
    private final Map<Path, VernacProjectSymbols> symbolSnapshots = new HashMap<>();
    private LanguageClient client;
    private boolean workspaceBound;

    synchronized void connect(LanguageClient client) { this.client = client; }

    synchronized void initialize(InitializeParams params) {
        symbolSnapshots.clear();
        folders.clear();
        if (params.getWorkspaceFolders() != null && !params.getWorkspaceFolders().isEmpty()) {
            params.getWorkspaceFolders().forEach(folder -> addFolder(folder.getUri()));
        } else if (params.getRootUri() != null) {
            addFolder(params.getRootUri());
        } else if (params.getRootPath() != null) {
            folders.add(Path.of(params.getRootPath()).toAbsolutePath().normalize());
        }
        workspaceBound = !folders.isEmpty();
    }

    synchronized void foldersChanged(DidChangeWorkspaceFoldersParams params) {
        workspaceBound = true;
        params.getEvent().getRemoved().forEach(folder -> folders.remove(path(folder.getUri())));
        params.getEvent().getAdded().forEach(folder -> addFolder(folder.getUri()));
        refresh();
    }

    private void addFolder(String uri) {
        Path path = path(uri);
        if (path != null) folders.add(path);
    }

    synchronized String text(String uri) {
        Document document = open.get(path(uri));
        return document == null ? null : document.text();
    }

    synchronized List<CompletionItem> complete(String uri, Position position) {
        var symbols = symbols(uri);
        return symbols == null ? List.of() : symbols.complete(path(uri), position);
    }

    synchronized List<Location> definition(String uri, Position position) {
        var symbols = symbols(uri);
        return symbols == null ? List.of() : symbols.definition(path(uri), position);
    }

    private VernacProjectSymbols symbols(String uri) {
        Path file = path(uri);
        if (file == null) return null;
        Path root = rootFor(file);
        if (root == null) return null;
        if (symbolSnapshots.containsKey(root)) return symbolSnapshots.get(root);
        Map<Path, String> texts = new TreeMap<>();
        try {
            if (Files.isDirectory(root)) {
                try (var paths = Files.walk(root)) {
                    for (Path source : paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
                            && p.toString().endsWith(".vernac")).toList()) {
                        Document document = open.get(source);
                        texts.put(source, document == null ? Files.readString(source) : document.text());
                    }
                }
            }
            open.forEach((source, document) -> {
                if (root.equals(rootFor(source))) texts.put(source, document.text());
            });
            var symbols = new VernacProjectSymbols(root, texts);
            symbolSnapshots.put(root, symbols);
            return symbols;
        } catch (IOException e) {
            if (client != null) client.logMessage(new MessageParams(MessageType.Error,
                    "Could not read Vernac symbols: " + e.getMessage()));
            return null;
        }
    }

    synchronized void open(String uri, String text, int version) {
        Path path = path(uri);
        if (path == null) {
            publish(uri, List.of(problem("Save this document under src/main/vernac to enable project analysis.")), version);
            return;
        }
        open.put(path, new Document(uri, text, version));
        refresh();
    }

    synchronized void change(String uri, String text, int version) {
        Path path = path(uri);
        Document previous = open.get(path);
        if (previous == null || version <= previous.version()) return;
        open.put(path, new Document(uri, text, version));
        refresh();
    }

    synchronized void close(String uri) {
        if (path(uri) == null) publish(uri, List.of(), null);
        open.remove(path(uri));
        refresh();
    }

    /** Save/watch notifications reread disk while preserving all open editor snapshots. */
    synchronized void refresh() {
        symbolSnapshots.clear();
        Map<String, List<Diagnostic>> diagnostics = new LinkedHashMap<>();
        Set<Path> roots = new LinkedHashSet<>();
        for (var entry : open.entrySet()) {
            diagnostics.put(entry.getValue().uri(), new ArrayList<>());
            Path root = rootFor(entry.getKey());
            if (root == null) {
                diagnostics.get(entry.getValue().uri()).add(problem(
                        "Place this file below a workspace src/main/vernac directory; its namespace must match the relative directory."));
            } else roots.add(root);
        }
        for (Path root : roots) analyze(root, diagnostics);
        Set<String> targets = new LinkedHashSet<>(published);
        targets.addAll(diagnostics.keySet());
        for (String uri : targets) {
            Document document = open.get(path(uri));
            publish(uri, diagnostics.getOrDefault(uri, List.of()), document == null ? null : document.version());
        }
        published.clear();
        published.addAll(diagnostics.keySet());
    }

    private void analyze(Path root, Map<String, List<Diagnostic>> diagnostics) {
        Map<Path, String> snapshots = new LinkedHashMap<>();
        open.forEach((path, document) -> {
            if (root.equals(rootFor(path))) snapshots.put(path, document.text());
        });
        List<CompilerDiagnostic> problems;
        try {
            var project = new VernacProjectLoader().load(root, snapshots);
            problems = new ProjectTypeResolver().resolve(project).diagnostics();
        } catch (SemanticValidationException e) {
            problems = e.diagnostics();
        } catch (IOException | RuntimeException e) {
            if (client != null) client.logMessage(new MessageParams(MessageType.Error,
                    "Vernac project analysis failed for " + root + ": " + e));
            snapshots.keySet().forEach(path -> diagnostics.computeIfAbsent(uri(path), ignored -> new ArrayList<>())
                    .add(problem("Could not analyze Vernac project: " + e.getMessage())));
            return;
        }
        for (var diagnostic : problems) {
            Path file = Path.of(diagnostic.location().sourceName()).toAbsolutePath().normalize();
            Diagnostic converted = problem(diagnostic.message());
            converted.setSeverity(diagnostic.severity() == CompilerDiagnostic.Severity.WARNING
                    ? DiagnosticSeverity.Warning : DiagnosticSeverity.Error);
            int line = Math.max(0, diagnostic.location().line() - 1);
            int column = Math.max(0, diagnostic.location().column() - 1);
            // ANTLR counts code points; LSP's default position encoding is UTF-16.
            try {
                String text = snapshots.containsKey(file) ? snapshots.get(file) : Files.readString(file);
                String[] lines = text.split("\\R", -1);
                line = Math.min(line, lines.length - 1);
                String current = lines[line];
                column = current.offsetByCodePoints(0, Math.min(column, current.codePointCount(0, current.length())));
                int end = column < current.length() ? column + Character.charCount(current.codePointAt(column)) : column;
                converted.setRange(new Range(new Position(line, column), new Position(line, end)));
            } catch (IOException e) {
                converted.setRange(new Range(new Position(line, column), new Position(line, column)));
            }
            diagnostics.computeIfAbsent(uri(file), ignored -> new ArrayList<>()).add(converted);
        }
    }

    private String uri(Path path) {
        Document document = open.get(path);
        return document == null ? path.toUri().toString() : document.uri();
    }

    private Path rootFor(Path file) {
        if (workspaceBound && folders.stream().noneMatch(file::startsWith)) return null;
        for (Path candidate = file.getParent(); candidate != null; candidate = candidate.getParent()) {
            if (candidate.endsWith(Path.of("src", "main", "vernac"))) return candidate;
        }
        return null;
    }

    private static Path path(String uri) {
        try {
            URI parsed = URI.create(uri);
            return "file".equalsIgnoreCase(parsed.getScheme()) ? Path.of(parsed).toAbsolutePath().normalize() : null;
        } catch (IllegalArgumentException e) { return null; }
    }

    private static Diagnostic problem(String message) {
        Diagnostic diagnostic = new Diagnostic(new Range(new Position(0, 0), new Position(0, 0)), message);
        diagnostic.setSource("vernac");
        diagnostic.setSeverity(DiagnosticSeverity.Error);
        return diagnostic;
    }

    private void publish(String uri, List<Diagnostic> diagnostics, Integer version) {
        if (client != null) {
            var params = new PublishDiagnosticsParams(uri, diagnostics);
            params.setVersion(version);
            client.publishDiagnostics(params);
        }
    }
}
