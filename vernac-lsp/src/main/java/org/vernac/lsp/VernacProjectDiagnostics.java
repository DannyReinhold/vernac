// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.LanguageClient;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.ast.SourceLocation;
import org.vernac.compiler.generator.NativeValidationCompiler;
import org.vernac.compiler.pipeline.ProjectTypeResolver;
import org.vernac.compiler.pipeline.ResolvedProject;
import org.vernac.compiler.pipeline.VersionedSources;
import org.vernac.compiler.pipeline.VernacProject;
import org.vernac.compiler.pipeline.VernacSourceFile;
import org.vernac.compiler.pipeline.VernacSourceParser;
import org.vernac.compiler.pipeline.VernacProjectLoader;
import org.vernac.compiler.pipeline.VernacScenarioCompiler;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

/** Serial project snapshots: editor text wins over disk until the document is closed. */
final class VernacProjectDiagnostics {
    private record Document(String uri, String text, int version) { }
    private final Map<Path, Document> open = new LinkedHashMap<>();
    private final Set<Path> folders = new LinkedHashSet<>();
    private final VernacProjectLoader loader = new VernacProjectLoader();
    private final Map<Path, VernacProjectLoader.ParseSession> parseSessions = new HashMap<>();
    private final Map<Path, VersionedSources> sourceSessions = new HashMap<>();
    private final Set<String> published = new LinkedHashSet<>();
    private final Map<Path, VernacProjectSymbols> symbolSnapshots = new HashMap<>();
    private record AnalysisInputs(VernacProject project, Map<Path, String> configurations) { }
    private final Map<Path, AnalysisInputs> analysisInputs = new HashMap<>();
    private final Map<Path, List<NativeValidationCompiler.Reference>> nativeReferences = new HashMap<>();
    private final Map<Path, ResolvedProject> scenarioModels = new HashMap<>();
    private final Map<Path, List<VernacScenarioCompiler.Reference>> scenarioReferences = new HashMap<>();
    private final Map<Path,List<String>> moduleInputStamps=new HashMap<>();
    private final Map<Path,Map<String,Location>> moduleLocations=new HashMap<>();
    private LanguageClient client;
    private boolean workspaceBound;

    synchronized void connect(LanguageClient client) { this.client = client; }

    synchronized void initialize(InitializeParams params) {
        parseSessions.clear();
        sourceSessions.clear();
        symbolSnapshots.clear();
        analysisInputs.clear();
        nativeReferences.clear();
        scenarioReferences.clear();
        scenarioModels.clear();
        moduleLocations.clear();
        moduleInputStamps.clear();
        folders.clear();
        if (params.getWorkspaceFolders() != null && !params.getWorkspaceFolders().isEmpty()) {
            params.getWorkspaceFolders().forEach(folder -> addFolder(folder.getUri()));
        } else {
            initializeLegacyRoot(params);
        }
        workspaceBound = !folders.isEmpty();
    }

    /** LSP retains these deprecated wire fields for clients without workspaceFolders. */
    @SuppressWarnings("deprecation")
    private void initializeLegacyRoot(InitializeParams params) {
        if (params.getRootUri() != null) {
            addFolder(params.getRootUri());
        } else if (params.getRootPath() != null) {
            folders.add(Path.of(params.getRootPath()).toAbsolutePath().normalize());
        }
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
        Path document = path(uri);
        var model = scenarioModels.get(document);
        if (model != null && open.containsKey(document)) return scenarioCompletions(open.get(document), model, position);
        var symbols = symbols(uri);
        if(uri.endsWith(".vapplication"))return symbols==null?List.of():applicationComplete(uri,position,symbols);
        if (symbols != null && open.containsKey(document) && document.toString().endsWith(".vernac")) {
            var typed = nativeCompletions(document, position, symbols);
            if (!typed.isEmpty()) return typed;
        }
        return symbols == null ? List.of() : uri.endsWith(".vmodule") ? symbols.moduleComplete(text(uri),position) : symbols.complete(path(uri), position);
    }

    synchronized String hover(String uri,Position at) {
        var symbols=symbols(uri);return symbols==null?"":symbols.hover(path(uri),at);
    }

    synchronized List<Location> definition(String uri, Position position) {
        Path file = path(uri);
        if (scenarioReferences.containsKey(file)) return scenarioDefinition(file, position);
        var nativeTarget = nativeDefinition(file, position);
        if (!nativeTarget.isEmpty()) return nativeTarget;
        var symbols = symbols(uri);
        if(uri.endsWith(".vapplication"))return symbols==null?List.of():symbols.applicationDefinition(text(uri),position,moduleLocations.getOrDefault(rootFor(path(uri)),Map.of()));
        return symbols == null ? List.of() : uri.endsWith(".vmodule") ? symbols.moduleDefinition(text(uri),position) : symbols.definition(path(uri), position);
    }

    private List<CompletionItem> applicationComplete(String uri,Position position,VernacProjectSymbols symbols) {
        String source=text(uri);
        if(source==null)return List.of();
        try {return symbols.applicationComplete(source,position,org.vernac.compiler.modules.ModuleArtifacts.editorModules(rootFor(path(uri))));}
        catch(java.io.IOException|RuntimeException e) {return symbols.applicationComplete(source,position);}
    }

    private VernacProjectSymbols symbols(String uri) {
        Path file = path(uri);
        if (file == null) return null;
        Path root = rootFor(file);
        if (root == null) return null;
        var stamp=moduleInputStamp(root);
        if(moduleInputStamps.containsKey(root)&&!stamp.equals(moduleInputStamps.get(root))) {
            // Build-produced indexes can change without a client file-watch notification.
            refresh();
        }
        moduleInputStamps.put(root,stamp);
        if(symbolSnapshots.containsKey(root))return symbolSnapshots.get(root);
        Map<Path, String> texts = new TreeMap<>();
        try {
            var session = sourceSessions.get(root);
            var sources = session == null ? VersionedSources.capture(root, Map.of()) : session.snapshot().sources();
            sources.forEach((source, content) -> {
                if (source.startsWith(root) && (open.containsKey(source) || !source.toString().contains(".module-contracts")))
                    texts.put(source, content);
            });
            Set<Path> external=new HashSet<>();
            Map<String,Location> moduleTargets=new HashMap<>();
            var dependencies=org.vernac.compiler.modules.ModuleArtifacts.editorModules(root);
            for(var module:dependencies) {
                Path manifest=org.vernac.compiler.modules.ModuleArtifacts.projectDirectory(root)
                    .resolve("target/vernac-module-contracts").resolve(module.module()).resolve("module.vmodule");
                String overview=org.vernac.compiler.modules.ModuleArtifacts.manifestView(module);
                Files.createDirectories(manifest.getParent());
                if(!Files.exists(manifest)||!Files.readString(manifest).equals(overview)) {
                    if(Files.exists(manifest))manifest.toFile().setWritable(true);
                    Files.writeString(manifest,overview);
                }
                manifest.toFile().setWritable(false);
                int nameStart=overview.indexOf("\nmodule ")+8;
                String prefix=overview.substring(0,nameStart);
                var nameAt=new Position((int)prefix.chars().filter(c->c=='\n').count(),nameStart-prefix.lastIndexOf('\n')-1);
                moduleTargets.put(module.module(),new Location(manifest.toUri().toString(),
                    new Range(nameAt,new Position(nameAt.getLine(),nameAt.getCharacter()+module.module().length()))));
                Set<String> namespaces=module.exports().stream().map(d->d.identity().substring(0,d.identity().lastIndexOf('.'))).collect(java.util.stream.Collectors.toSet());
                for(String namespace:namespaces) {
                    Path viewFile=org.vernac.compiler.modules.ModuleArtifacts.projectDirectory(root).resolve("target/vernac-module-contracts").resolve(module.module()).resolve(namespace.replace('.','/')).resolve("contract.vernac");
                    String view=org.vernac.compiler.modules.ModuleArtifacts.view(module,namespace);
                    // Navigation fallback is an explicit, non-executable dependency contract view.
                    java.nio.file.Files.createDirectories(viewFile.getParent());
                    if(!Files.exists(viewFile)||!Files.readString(viewFile).equals(view)) {
                        if(Files.exists(viewFile))viewFile.toFile().setWritable(true);
                        Files.writeString(viewFile,view);
                    }
                    viewFile.toFile().setWritable(false);
                    texts.put(viewFile,view);external.add(viewFile);
                }
            }
            var symbols = new VernacProjectSymbols(root, texts,external);
            Map<String,Location> origins=new HashMap<>();
            for(var entry:org.vernac.compiler.modules.ModuleSources.locations(root,dependencies).entrySet()) {
                var view=entry.getValue();int a=view.start(),b=view.end();
                String prefix=view.text().substring(0,a),end=view.text().substring(0,b);
                var start=new Position((int)prefix.chars().filter(c->c=='\n').count(),a-(prefix.lastIndexOf('\n')+1));
                var finish=new Position((int)end.chars().filter(c->c=='\n').count(),b-(end.lastIndexOf('\n')+1));
                origins.put(entry.getKey(),new Location(view.path().toUri().toString(),new Range(start,finish)));
            }
            symbols.dependencyLocations(origins);
            moduleLocations.put(root,Map.copyOf(moduleTargets));
            moduleInputStamps.put(root,stamp);
            symbolSnapshots.put(root, symbols);
            return symbols;
        } catch (IOException | RuntimeException e) {
            if (client != null) client.logMessage(new MessageParams(MessageType.Error,
                    "Could not read Vernac symbols: " + e.getMessage()));
            return null;
        }
    }

    private static List<String> moduleInputStamp(Path root) {
        Path target=org.vernac.compiler.modules.ModuleArtifacts.projectDirectory(root).resolve("target");
        List<String> stamps=new ArrayList<>();
        for(String name:List.of("vernac-module-index.json","vernac-module-sources.json")) {
            try {
                var attributes=Files.readAttributes(target.resolve(name),java.nio.file.attribute.BasicFileAttributes.class);
                stamps.add(name+":"+attributes.lastModifiedTime()+":"+attributes.size()+":"+attributes.fileKey());
            }catch(IOException e){stamps.add(name+":unavailable");}
        }
        return List.copyOf(stamps);
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
        analysisInputs.clear();
        nativeReferences.clear();
        scenarioReferences.clear();
        scenarioModels.clear();
        moduleLocations.clear();
        moduleInputStamps.clear();
        Map<String, List<Diagnostic>> diagnostics = new LinkedHashMap<>();
        Set<Path> roots = new LinkedHashSet<>();
        for (var entry : open.entrySet()) {
            diagnostics.put(entry.getValue().uri(), new ArrayList<>());
            Path root = rootFor(entry.getKey());
            if (root == null) {
                diagnostics.get(entry.getValue().uri()).add(problem(
                        entry.getKey().toString().endsWith(".vapplication")
                            ? "Application definitions must be named vernac-application.vapplication and stored beside the Maven project's pom.xml."
                            : entry.getKey().toString().endsWith(".vmodule")
                            ? "Place vernac-module.vmodule in its source namespace directory or beside the Maven project's pom.xml."
                            : "Place this file below a workspace src/main/vernac or src/test/vernac directory."));
            } else roots.add(root);
        }
        parseSessions.keySet().retainAll(roots);
        sourceSessions.keySet().retainAll(roots);
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
        Path workspace = root.getParent().getParent().getParent();
        Path testRoot = workspace.resolve("src/test/vernac");
        Map<Path, String> modelBuffers = new LinkedHashMap<>();
        Map<Path, String> testBuffers = new LinkedHashMap<>();
        open.forEach((path, document) -> {
            if (!root.equals(rootFor(path)) || !path.toString().endsWith(".vernac")) return;
            if (path.startsWith(root)) modelBuffers.put(path, document.text());
            else if (path.startsWith(testRoot)) testBuffers.put(path, document.text());
        });
        List<CompilerDiagnostic> problems = new ArrayList<>();
        VersionedSources.Snapshot snapshot;
        try {
            var captured = VersionedSources.capture(root, modelBuffers);
            captured.putAll(VersionedSources.capture(testRoot, testBuffers));
            var sources = sourceSessions.computeIfAbsent(root, ignored -> new VersionedSources(workspace, Map.of()));
            snapshot = sources.reconcile(sources.snapshot().revision(), captured);
            Map<Path, String> model = new LinkedHashMap<>();
            Map<String, String> tests = new TreeMap<>();
            snapshot.sources().forEach((path, text) -> {
                if (path.startsWith(root)) model.put(path, text);
                else tests.put(path.toString(), text);
            });
            var project = parseSessions.computeIfAbsent(root, loader::openSession).loadSnapshotForAnalysis(model, problems);
            project=org.vernac.compiler.modules.ModuleArtifacts.merge(project,org.vernac.compiler.modules.ModuleArtifacts.editorModules(root),problems);
            Map<Path,String> configurationSnapshots=new LinkedHashMap<>();
            open.forEach((path, document) -> {
                if(root.equals(rootFor(path)) && (path.toString().endsWith(".vapplication") || path.toString().endsWith(".vmodule")))
                    configurationSnapshots.put(path,document.text());
            });
            analysisInputs.put(root, new AnalysisInputs(project, Map.copyOf(configurationSnapshots)));
            var resolved = new ProjectTypeResolver().resolve(project, configurationSnapshots, reference -> {
                Path source = Path.of(reference.source().sourceName());
                nativeReferences.computeIfAbsent(source, ignored -> new ArrayList<>()).add(reference);
            });
            problems.addAll(resolved.diagnostics());
            if (problems.stream().noneMatch(diagnostic -> diagnostic.severity() == CompilerDiagnostic.Severity.ERROR))
                analyzeScenarios(tests, resolved, problems);
            Path application=org.vernac.compiler.modules.ModuleArtifacts.projectDirectory(root).resolve(org.vernac.compiler.application.ApplicationDefinition.FILE);
            var appSnapshot=open.get(application);
            if(appSnapshot!=null||Files.isRegularFile(application)) {
                try {
                    var definition=org.vernac.compiler.application.ApplicationDefinition.parse(application,appSnapshot==null?Files.readString(application):appSnapshot.text());
                    problems.addAll(definition.diagnostics(resolved,org.vernac.compiler.modules.ModuleArtifacts.editorModules(root)));
                    try {definition.requireBuildSupport();}catch(SemanticValidationException e){problems.addAll(e.diagnostics());}
                }catch(SemanticValidationException e){problems.addAll(e.diagnostics());}
            }
            Path manifest=org.vernac.compiler.modules.ModuleArtifacts.projectDirectory(root).resolve(org.vernac.compiler.modules.ModuleDefinition.FILE);
            var snapshot=open.get(manifest);
            if(snapshot!=null||Files.isRegularFile(manifest)) {
                var definition=org.vernac.compiler.modules.ModuleDefinition.parse(manifest,snapshot==null?Files.readString(manifest):snapshot.text());
                org.vernac.compiler.modules.ModuleContracts.validateExports(definition,resolved);
            }
        } catch (SemanticValidationException e) {
            problems.addAll(e.diagnostics());
        } catch (IOException | RuntimeException e) {
            if (client != null) client.logMessage(new MessageParams(MessageType.Error,
                    "Vernac project analysis failed for " + root + ": " + e));
            open.keySet().stream().filter(path->root.equals(rootFor(path))).forEach(path ->
                diagnostics.computeIfAbsent(uri(path), ignored -> new ArrayList<>())
                    .add(problem("Could not analyze Vernac project: " + e.getMessage())));
            return;
        }
        for (var diagnostic : problems.stream().distinct().toList()) {
            Path file = Path.of(diagnostic.location().sourceName()).toAbsolutePath().normalize();
            Diagnostic converted = problem(diagnostic.message());
            converted.setSeverity(diagnostic.severity() == CompilerDiagnostic.Severity.WARNING
                    ? DiagnosticSeverity.Warning : DiagnosticSeverity.Error);
            int line = Math.max(0, diagnostic.location().line() - 1);
            int column = Math.max(0, diagnostic.location().column() - 1);
            // ANTLR counts code points; LSP's default position encoding is UTF-16.
            try {
                String text = snapshot.sources().get(file);
                if (text == null) text = open.containsKey(file) ? open.get(file).text() : Files.readString(file);
                String[] lines = text.split("\n", -1);
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

    private void analyzeScenarios(Map<String, String> tests, ResolvedProject main, List<CompilerDiagnostic> problems) {
        var compiler = new VernacScenarioCompiler();
        tests.keySet().forEach(file -> scenarioModels.put(Path.of(file), main));
        try {
            for (var reference : compiler.validate(tests, main)) {
                Path file = Path.of(reference.source().sourceName());
                scenarioReferences.computeIfAbsent(file, ignored -> new ArrayList<>()).add(reference);
            }
        } catch (SemanticValidationException e) {
            problems.addAll(e.diagnostics());
        }
    }

    private List<CompletionItem> scenarioCompletions(Document document, ResolvedProject model, Position position) {
        String text = document.text();
        int lineStart = 0;
        for (int line = 0; line < position.getLine(); line++) {
            int newline = text.indexOf('\n', lineStart);
            if (newline < 0) return List.of();
            lineStart = newline + 1;
        }
        int offset = lineStart + position.getCharacter();
        int lineEnd = text.indexOf('\n', lineStart);
        if (offset < lineStart || offset > (lineEnd < 0 ? text.length() : lineEnd)) return List.of();
        var completion = new VernacScenarioCompiler().complete(document.uri(), text, offset, model);
        if (completion.candidates().isEmpty() || completion.start() < lineStart) return List.of();
        var range = new Range(new Position(position.getLine(), completion.start() - lineStart), position);
        return completion.candidates().stream().map(candidate -> {
            var item = new CompletionItem(candidate);
            item.setKind(CompletionItemKind.Reference);
            item.setTextEdit(org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(new TextEdit(range, candidate)));
            return item;
        }).toList();
    }

    private List<CompletionItem> nativeCompletions(Path file, Position position, VernacProjectSymbols symbols) {
        var inputs = analysisInputs.get(rootFor(file));
        if (inputs == null) return List.of();
        var probe = symbols.nativeProbe(file, position, false);
        if (probe == null) return List.of();
        var items = nativeCompletions(file, inputs, probe, false);
        if (!items.isEmpty()) return items;
        var keyword = symbols.nativeProbe(file, position, true);
        return keyword == null ? List.of() : nativeCompletions(file, inputs, keyword, true);
    }

    private List<CompletionItem> nativeCompletions(Path file, AnalysisInputs inputs,
                                                   VernacProjectSymbols.NativeProbe probe, boolean insertKeyword) {
        try {
            var unit = new VernacSourceParser().parse(file.toString(), probe.text());
            var sources = new ArrayList<>(inputs.project().sources());
            sources.removeIf(source -> source.path().equals(file));
            sources.add(new VernacSourceFile(file, unit));
            var project = loader.index(inputs.project().sourceRoot(), sources);
            return new ProjectTypeResolver().completeNative(project, inputs.configurations(), probe.cursor()).stream()
                    .filter(candidate -> candidate.name().startsWith(probe.prefix()))
                    .sorted(Comparator.comparing(NativeValidationCompiler.Completion::name))
                    .map(candidate -> nativeItem(candidate, probe.replace(), insertKeyword))
                    .toList();
        } catch (SemanticValidationException ignored) {
            // A different unfinished statement can prevent a typed completion probe.
            return List.of();
        }
    }

    private static CompletionItem nativeItem(NativeValidationCompiler.Completion candidate, Range replace,
                                             boolean insertKeyword) {
        String label = candidate.name();
        String insertion = candidate.name();
        var arguments = insertKeyword && candidate.keyword() && candidate.arguments().isEmpty()
                ? List.of(candidate.name()) : candidate.arguments();
        if (!arguments.isEmpty()) {
            var selector = new StringBuilder();
            var snippet = new StringJoiner(" ");
            for (int index = 0; index < arguments.size(); index++) {
                String argument = arguments.get(index);
                String part = index == 0 ? candidate.name() : argument;
                selector.append(part).append(':');
                snippet.add(part + ": ${" + (index + 1) + ":" + argument.replace("$", "\\$") + "}");
            }
            label = selector.toString();
            insertion = snippet.toString();
        }
        var item = new CompletionItem(label);
        item.setKind(candidate.keyword() ? CompletionItemKind.Method : CompletionItemKind.Field);
        if (!arguments.isEmpty()) item.setInsertTextFormat(InsertTextFormat.Snippet);
        item.setTextEdit(org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(new TextEdit(replace, insertion)));
        return item;
    }

    private List<Location> nativeDefinition(Path file, Position position) {
        var references = nativeReferences.get(file);
        if (references == null) return List.of();
        try {
            String source = sourceText(file);
            for (var reference : references) {
                if (!contains(source, reference.source(), reference.width(), position)) continue;
                var target = declarationLocation(reference.declaration());
                if (target != null) return List.of(target);
            }
        } catch (IOException | InvalidPathException ignored) {
            // A declaration may no longer be available between editor refreshes.
        }
        return List.of();
    }

    private List<Location> scenarioDefinition(Path file, Position position) {
        try {
            String source = sourceText(file);
            for (var reference : scenarioReferences.getOrDefault(file, List.of())) {
                if (!contains(source, reference.source(), reference.width(), position)) continue;
                var target = declarationLocation(reference.declaration());
                if (target != null) return List.of(target);
            }
        } catch (IOException | InvalidPathException ignored) {
            // A declaration may no longer be available between editor refreshes.
        }
        return List.of();
    }

    private static boolean contains(String text, SourceLocation source, int width, Position position) {
        if (source.line() - 1 != position.getLine()) return false;
        var start = sourcePosition(text, source, 0);
        var end = sourcePosition(text, source, width);
        return start != null && end != null && position.getCharacter() >= start.getCharacter()
                && position.getCharacter() < end.getCharacter();
    }

    private Location declarationLocation(SourceLocation declaration) throws IOException {
        Path target = Path.of(declaration.sourceName());
        if (!target.isAbsolute() || (!open.containsKey(target) && !Files.isRegularFile(target))) return null;
        var at = sourcePosition(sourceText(target), declaration, 0);
        return at == null ? null : new Location(uri(target), new Range(at, at));
    }

    private String sourceText(Path file) throws IOException {
        var document = open.get(file);
        return document == null ? Files.readString(file) : document.text();
    }

    private static Position sourcePosition(String source, SourceLocation location, int advance) {
        String[] lines = source.split("\n", -1);
        int line = location.line() - 1;
        int column = location.column() - 1 + advance;
        if (line < 0 || line >= lines.length || column < 0
                || column > lines[line].codePointCount(0, lines[line].length())) return null;
        return new Position(line, lines[line].offsetByCodePoints(0, column));
    }

    private String uri(Path path) {
        Document document = open.get(path);
        return document == null ? path.toUri().toString() : document.uri();
    }

    private Path rootFor(Path file) {
        if (workspaceBound && folders.stream().noneMatch(file::startsWith)) return null;
        for(Path candidate=file.getParent();candidate!=null;candidate=candidate.getParent())
            if(candidate.endsWith(Path.of("target","vernac-module-contracts")))
                return candidate.getParent().getParent().resolve("src/main/vernac");
        if(file.getFileName().toString().endsWith(".vapplication")) {
            return file.getFileName().toString().equals(org.vernac.compiler.application.ApplicationDefinition.FILE)
                    && Files.isRegularFile(file.getParent().resolve("pom.xml")) ? file.getParent().resolve("src/main/vernac") : null;
        }
        if(file.getFileName().toString().endsWith(".vmodule")) {
            if (!file.getFileName().toString().equals(org.vernac.compiler.modules.ModuleDefinition.FILE)) return null;
            if (Files.isRegularFile(file.getParent().resolve("pom.xml"))) return file.getParent().resolve("src/main/vernac");
        }
        for (Path candidate = file.getParent(); candidate != null; candidate = candidate.getParent()) {
            if(candidate.endsWith(Path.of("target","vernac-module-contracts"))) return candidate.getParent().getParent().resolve("src/main/vernac");
            if (candidate.endsWith(Path.of("src", "main", "vernac"))) return candidate;
            if (candidate.endsWith(Path.of("src", "test", "vernac")))
                return candidate.getParent().getParent().resolve("main/vernac");
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
