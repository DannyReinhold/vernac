// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class VernacProjectDiagnosticsTest {
    @TempDir Path directory;
    VernacLanguageServer server;
    RecordingClient client;
    Path root;

    @BeforeEach
    void setup() throws Exception {
        root = directory.resolve("src/main/vernac");
        Files.createDirectories(root);
        client = new RecordingClient();
        server = new VernacLanguageServer();
        server.connect(client);
        var params = new InitializeParams();
        params.setWorkspaceFolders(List.of(new WorkspaceFolder(directory.toUri().toString(), "test")));
        server.initialize(params).join();
    }

    Path file(String namespace, String name, String body) throws Exception {
        Path path = root.resolve(namespace.replace('.', '/')).resolve(name + ".vernac");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "namespace " + namespace + ";\n" + body);
        return path;
    }

    void open(Path path) throws Exception { open(path, Files.readString(path)); }
    void open(Path path, String text) {
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(path.toUri().toString(), "vernac", 1, text)));
    }
    void change(Path path, String text, int version) {
        server.getTextDocumentService().didChange(new DidChangeTextDocumentParams(
                new VersionedTextDocumentIdentifier(path.toUri().toString(), version),
                List.of(new TextDocumentContentChangeEvent(text))));
    }
    void close(Path path) {
        server.getTextDocumentService().didClose(new DidCloseTextDocumentParams(
                new TextDocumentIdentifier(path.toUri().toString())));
    }
    List<Diagnostic> diagnostics(Path path) { return client.latest.get(path.toUri().toString()).getDiagnostics(); }

    @Test
    void scenarioDiagnosticsFollowUnsavedTestAndModelEdits() throws Exception {
        Path model = file("model", "status", "value Status = OPEN | DONE;");
        Path scenario = directory.resolve("src/test/vernac/model/scenarios.vernac");
        String source = """
                namespace model;
                suite Lifecycle {
                    scenario opens {
                        given status = Status.OPEN;
                        expect status equals Status.OPEN;
                    }
                }
                """;
        open(scenario, source);
        assertTrue(diagnostics(scenario).isEmpty(), diagnostics(scenario).toString());
        assertEquals(model.toUri().toString(), definition(scenario, 4, source.split("\n")[4].indexOf("OPEN")).getFirst().getUri());

        change(scenario, source.replace("equals Status.OPEN", "equals true"), 2);
        assertTrue(diagnostics(scenario).stream().anyMatch(d -> d.getMessage().contains("Expected model.Status")));
        change(scenario, source, 3);
        assertTrue(diagnostics(scenario).isEmpty(), diagnostics(scenario).toString());

        open(model, "namespace model; value Status = DONE;");
        assertTrue(diagnostics(scenario).stream().anyMatch(d -> d.getMessage().contains("Unknown enum member")));
        assertTrue(diagnostics(model).isEmpty(), diagnostics(model).toString());
        change(model, Files.readString(model), 2);
        assertTrue(diagnostics(scenario).isEmpty(), diagnostics(scenario).toString());
    }

    @Test void domainFieldAndContainmentErrorsAppearAndClearWhileEditing() throws Exception {
        Path model = file("model", "model", "id Key; entity Node[Key](String text);");
        open(model);
        assertTrue(diagnostics(model).stream().anyMatch(d -> d.getMessage().contains("Wrap Java type")));
        change(model, "namespace model; id Key; entity Node[Key](Nodes children) list Nodes;", 2);
        assertTrue(diagnostics(model).stream().anyMatch(d -> d.getMessage().contains("Cyclic containment")));
        change(model, "namespace model; id Key; entity Node[Key](Key? parent);", 3);
        assertTrue(diagnostics(model).isEmpty(), diagnostics(model).toString());
    }

    @Test void valueStringRepresentationDiagnosticsFollowUnsavedEdits() throws Exception {
        Path model = file("representation", "secret", "value Secret(String encoded) behavior {public String toString(){return \"Secret[REDACTED]\";}};");
        open(model);
        assertTrue(diagnostics(model).isEmpty(), diagnostics(model).toString());
        change(model, "namespace representation; value Secret(String encoded) behavior {private String toString(){return \"x\";}};", 2);
        assertTrue(diagnostics(model).stream().anyMatch(d -> d.getMessage().contains("public String toString()")));
        change(model, "namespace representation; value Secret(String encoded) behavior {public String? toString(){return Optional.empty();}};", 3);
        assertTrue(diagnostics(model).stream().anyMatch(d -> d.getMessage().contains("non-null result")));
        change(model, "namespace representation; value Secret(String encoded) behavior {public String toString(){return \"Secret[REDACTED]\";}};", 4);
        assertTrue(diagnostics(model).isEmpty(), diagnostics(model).toString());
    }

    @Test void behaviorImportConflictIsReportedAndCleared() throws Exception {
        Path model = file("names", "name", "value Name(String) behavior { java imports { foreign.String; } };");
        open(model);
        assertTrue(diagnostics(model).stream().anyMatch(d -> d.getMessage().contains("conflicts with")));
        change(model, "namespace names; value Name(String) behavior { java imports { java.util.Locale; } "
                + "public String upper() { return self.string().toUpperCase(Locale.ROOT); } };", 2);
        assertTrue(diagnostics(model).isEmpty(), diagnostics(model).toString());
    }

    @Test
    void unusualEnumConstantsAreAcceptedHighlightedAndNotConfusedWithMethods() throws Exception {
        Path model = file("enums", "status", "value Status = MeinTyp | String | name | values | toString | custom | Größe;");
        open(model);
        assertTrue(diagnostics(model).isEmpty(), diagnostics(model).toString());
        var tokens = server.getTextDocumentService().semanticTokensFull(new SemanticTokensParams(
                new TextDocumentIdentifier(model.toUri().toString()))).join().getData();
        String[] lines = Files.readString(model).split("\n", -1);
        int line = 0, column = 0;
        List<String> constants = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i += 5) {
            int deltaLine = tokens.get(i);
            line += deltaLine;
            column = deltaLine == 0 ? column + tokens.get(i + 1) : tokens.get(i + 1);
            if (VernacLanguageServer.TOKEN_TYPES.get(tokens.get(i + 3)).equals(SemanticTokenTypes.EnumMember))
                constants.add(lines[line].substring(column, column + tokens.get(i + 2)));
        }
        assertEquals(List.of("MeinTyp", "String", "name", "values", "toString", "custom", "Größe"), constants);
        change(model, "namespace enums; value Status = name behavior { public String name() { return \"x\"; } };", 2);
        assertTrue(diagnostics(model).stream().anyMatch(d -> d.getMessage().contains("name()")
                && d.getMessage().contains("conflicts with")));
        change(model, "namespace enums; value Status = name behavior { public String toString() { return name(); } };", 3);
        assertTrue(diagnostics(model).isEmpty(), diagnostics(model).toString());
    }

    @Test
    void independentNamingDiagnosticsSurviveErrorsInOtherFiles() throws Exception {
        Path model = file("names", "model", "value IntValue(int); value Neu(int, IntValue);");
        Path broken = file("other", "broken", "value Broken(Missing);");
        open(model);
        open(broken);
        int diagnosticVersion = 1;
        // Exercise each earlier analysis stage without saving either editor snapshot.
        for (String body : List.of(
                "value Broken(Missing);",
                "value Broken(",
                "import absent.Type; value Broken(String);",
                "value Duplicate(String); value Duplicate(int);",
                "value String(int);")) {
            change(broken, "namespace other;\n" + body, ++diagnosticVersion);
            assertFalse(diagnostics(broken).isEmpty(), body);
            assertTrue(diagnostics(model).stream().anyMatch(d ->
                    d.getMessage().contains("Duplicate field name 'intValue'")), body);
            assertTrue(diagnostics(model).stream().noneMatch(d ->
                    d.getMessage().contains("Could not analyze")), body);
        }
        change(model, "namespace names; value IntValue(int); value Neu(int count, IntValue limit);", 2);
        assertTrue(diagnostics(model).isEmpty());
        assertFalse(diagnostics(broken).isEmpty());
        change(broken, "namespace other; value Fixed(String);", ++diagnosticVersion);
        assertTrue(diagnostics(broken).isEmpty());
    }

    @Test
    void unresolvedTypesDoNotHideLocalNamesOrInventSignatureConflicts() throws Exception {
        Path model = file("names", "partial", """
                value Partial(Missing first, Missing second, String toString, int count, long count);
                """);
        open(model);
        assertTrue(diagnostics(model).stream().anyMatch(d -> d.getMessage().contains("Missing")));
        assertTrue(diagnostics(model).stream().anyMatch(d -> d.getMessage().contains("Duplicate field name 'count'")));
        assertTrue(diagnostics(model).stream().anyMatch(d -> d.getMessage().contains("toString()")));
        assertTrue(diagnostics(model).stream().noneMatch(d -> d.getMessage().contains("Could not analyze")));
    }

    @Test
    void strictLoaderStillRejectsBrokenProjects() throws Exception {
        file("names", "valid", "value Title(String);");
        file("other", "broken", "value Broken(");
        assertThrows(org.vernac.compiler.analyzer.SemanticValidationException.class,
                () -> new org.vernac.compiler.pipeline.VernacProjectLoader().load(root));
    }

    @Test
    void reportsDerivedAndGeneratedNameConflictsAndClearsThemAfterCorrection() throws Exception {
        Path path = file("names", "model", """
                value IntValue(int value);
                value Pair(int, IntValue);
                value Label(String text, String toString);
                """);
        open(path);
        assertTrue(diagnostics(path).stream().anyMatch(d -> d.getMessage().contains("Duplicate field name 'intValue'")));
        assertTrue(diagnostics(path).stream().anyMatch(d -> d.getMessage().contains("toString()")
                && d.getMessage().contains("conflicts with")));
        assertTrue(diagnostics(path).stream().filter(d -> d.getMessage().contains("intValue"))
                .anyMatch(d -> d.getRange().getStart().getLine() == 2));
        change(path, """
                namespace names;
                value IntValue(int value);
                value Pair(int count, IntValue limit);
                value Label(String text, String description);
                """, 2);
        assertTrue(diagnostics(path).isEmpty(), diagnostics(path).toString());
    }

    @Test
    void resolvesSameNamespaceAndImportedTypesFromUnopenedFiles() throws Exception {
        file("tasks", "title", "value Title(String value);");
        file("people", "owner", "value Owner(String value);");
        Path summary = file("tasks", "summary", "import people.Owner;\nvalue Summary(Title title, Owner? owner);");
        open(summary);
        assertTrue(diagnostics(summary).isEmpty());
        assertEquals(1, client.latest.get(summary.toUri().toString()).getVersion());
    }

    @Test
    void unsavedChangesRevalidateDependentsAndCloseRestoresDisk() throws Exception {
        Path title = file("tasks", "title", "value Title(String value);");
        Path summary = file("tasks", "summary", "value Summary(Title title);");
        open(summary);
        open(title);
        change(title, "namespace tasks; value Renamed(String value);", 2);
        assertTrue(diagnostics(summary).stream().anyMatch(d -> d.getMessage().contains("Title")));
        assertEquals("namespace tasks;\nvalue Title(String value);", Files.readString(title));
        close(title);
        assertTrue(diagnostics(summary).isEmpty());
        assertTrue(diagnostics(title).isEmpty());
    }

    @Test
    void includesNewUnsavedFilesAndIgnoresStaleVersions() throws Exception {
        Path summary = file("tasks", "summary", "value Summary(Title title);");
        open(summary);
        Path title = root.resolve("tasks/new.vernac");
        open(title, "namespace tasks; value Title(String value);");
        assertTrue(diagnostics(summary).isEmpty());
        change(title, "namespace tasks; value Other(String value);", 3);
        assertFalse(diagnostics(summary).isEmpty());
        change(title, "namespace tasks; value Title(String value);", 2);
        assertFalse(diagnostics(summary).isEmpty());
        close(title);
        assertFalse(diagnostics(summary).isEmpty());
        assertFalse(Files.exists(title));
    }

    @Test
    void watchedCreationDeletionAndSaveRefreshDiskReferences() throws Exception {
        Path summary = file("tasks", "summary", "value Summary(Title title);");
        open(summary);
        Path title = file("tasks", "title", "value Title(String value);");
        server.getWorkspaceService().didChangeWatchedFiles(new DidChangeWatchedFilesParams(
                List.of(new FileEvent(title.toUri().toString(), FileChangeType.Created))));
        assertTrue(diagnostics(summary).isEmpty());
        Files.delete(title);
        server.getWorkspaceService().didChangeWatchedFiles(new DidChangeWatchedFilesParams(
                List.of(new FileEvent(title.toUri().toString(), FileChangeType.Deleted))));
        assertFalse(diagnostics(summary).isEmpty());
        file("tasks", "title", "value Title(String value);");
        server.getTextDocumentService().didSave(new DidSaveTextDocumentParams(
                new TextDocumentIdentifier(summary.toUri().toString())));
        assertTrue(diagnostics(summary).isEmpty());
    }

    @Test
    void clearsDiagnosticsOnClosedDeletedFiles() throws Exception {
        Path bad = file("tasks", "bad", "value Invalid(Missing value);");
        Path good = file("tasks", "good", "id TaskId;");
        open(good);
        assertFalse(diagnostics(bad).isEmpty());
        Files.delete(bad);
        server.getWorkspaceService().didChangeWatchedFiles(new DidChangeWatchedFilesParams(List.of()));
        assertTrue(diagnostics(bad).isEmpty());
    }

    @Test
    void sharesNamespaceValidationAndImportWarningSeverity() throws Exception {
        file("people", "owner", "value Owner(String value);");
        Path summary = file("tasks", "summary", "import people.Owner;\nimport people.Owner;\nvalue Summary(Owner owner);");
        open(summary);
        assertEquals(DiagnosticSeverity.Warning, diagnostics(summary).getFirst().getSeverity());
        change(summary, "namespace wrong; value Summary(String value);", 2);
        assertEquals(DiagnosticSeverity.Error, diagnostics(summary).getFirst().getSeverity());
        assertTrue(diagnostics(summary).getFirst().getMessage().contains("directory"));
    }

    @Test
    void syntaxErrorsArePublishedAndClearAfterRepair() throws Exception {
        Path title = file("tasks", "title", "value Title(String value);");
        open(title);
        change(title, "namespace tasks; value Title(", 2);
        assertFalse(diagnostics(title).isEmpty());
        change(title, Files.readString(title), 3);
        assertTrue(diagnostics(title).isEmpty());
    }

    @Test
    void moduleSourceRootsDoNotLeakTypesIntoEachOther() throws Exception {
        file("tasks", "title", "value Title(String value);");
        root = directory.resolve("second/src/main/vernac");
        Path summary = file("tasks", "summary", "value Summary(Title title);");
        open(summary);
        assertFalse(diagnostics(summary).isEmpty());
    }

    @Test
    void workspaceRemovalStopsProjectAnalysis() throws Exception {
        Path title = file("tasks", "title", "value Title(String value);");
        open(title);
        server.getWorkspaceService().didChangeWorkspaceFolders(new DidChangeWorkspaceFoldersParams(
                new WorkspaceFoldersChangeEvent(List.of(), List.of(new WorkspaceFolder(directory.toUri().toString(), "test")))));
        assertTrue(diagnostics(title).getFirst().getMessage().contains("workspace"));
    }

    @Test
    void registersFileWatcherOnlyWhenSupported() {
        var params = new InitializeParams();
        var capabilities = new ClientCapabilities();
        var workspace = new WorkspaceClientCapabilities();
        var watched = new DidChangeWatchedFilesCapabilities();
        watched.setDynamicRegistration(true);
        workspace.setDidChangeWatchedFiles(watched);
        capabilities.setWorkspace(workspace);
        params.setCapabilities(capabilities);
        var result = server.initialize(params).join();
        assertTrue(result.getCapabilities().getTextDocumentSync().getRight().getOpenClose());
        server.initialized(new InitializedParams());
        assertEquals("workspace/didChangeWatchedFiles", client.registration.getRegistrations().getFirst().getMethod());
    }

    @Test
    void usesUtf16ColumnsAfterSupplementaryCharacters() throws Exception {
        Path model = file("tasks", "unicode name", "/* 😀 */ value Model(Missing value);");
        open(model);
        Diagnostic diagnostic = diagnostics(model).getFirst();
        assertEquals(1, diagnostic.getRange().getStart().getLine());
        assertEquals("/* 😀 */ value Model(".length(), diagnostic.getRange().getStart().getCharacter());
    }

    @Test
    void matchesCompilerDiagnosticMessages() throws Exception {
        Path model = file("tasks", "invalid", "value Invalid(int? count);");
        open(model);
        var exception = assertThrows(org.vernac.compiler.analyzer.SemanticValidationException.class,
                () -> new org.vernac.compiler.pipeline.VernacCompiler().analyzeProject(root));
        assertEquals(exception.diagnostics().getFirst().message(), diagnostics(model).getFirst().getMessage());
    }

    @Test
    void watcherDoesNotOverwriteUnsavedEditorText() throws Exception {
        Path model = file("tasks", "model", "value Model(String value);");
        open(model);
        change(model, "namespace tasks; value Model(Missing value);", 2);
        server.getWorkspaceService().didChangeWatchedFiles(new DidChangeWatchedFilesParams(List.of()));
        assertFalse(diagnostics(model).isEmpty());
        assertEquals(2, client.latest.get(model.toUri().toString()).getVersion());
    }

    List<String> completion(Path path, int line, int column) {
        return server.getTextDocumentService().completion(new CompletionParams(
                new TextDocumentIdentifier(path.toUri().toString()), new Position(line, column)))
                .join().getLeft().stream().map(CompletionItem::getLabel).toList();
    }

    List<? extends Location> definition(Path path, int line, int column) {
        return server.getTextDocumentService().definition(new DefinitionParams(
                new TextDocumentIdentifier(path.toUri().toString()), new Position(line, column)))
                .join().getLeft();
    }

    @Test
    void completionAndNavigationFollowUnsavedRenameCloseAndDiskDeletion() throws Exception {
        Path title = file("tasks", "title", "value Title(String value);");
        Path draft = file("tasks", "draft", "value Draft(Title title);");
        open(draft);
        assertEquals(List.of("Title"), completion(draft, 1, "value Draft(Ti".length()));
        assertEquals(title.toUri().toString(), definition(draft, 1, 14).getFirst().getUri());
        open(title);
        change(title, "namespace tasks; value Renamed(String value);", 2);
        assertEquals(List.of(), completion(draft, 1, "value Draft(Ti".length()));
        assertEquals(List.of(), definition(draft, 1, 14));
        close(title);
        assertEquals(List.of("Title"), completion(draft, 1, "value Draft(Ti".length()));
        Files.delete(title);
        server.getWorkspaceService().didChangeWatchedFiles(new DidChangeWatchedFilesParams(
                List.of(new FileEvent(title.toUri().toString(), FileChangeType.Deleted))));
        assertEquals(List.of(), completion(draft, 1, "value Draft(Ti".length()));
        assertEquals(List.of(), definition(draft, 1, 14));
    }

    @Test
    void completionIncludesUnsavedNewFilesButNotOtherModuleRoots() throws Exception {
        Path draft = file("tasks", "draft", "value Draft(Ne");
        open(draft);
        Path other = directory.resolve("other/src/main/vernac/tasks/new.vernac");
        open(other, "namespace tasks; value NewTitle(String value);");
        assertEquals(List.of(), completion(draft, 1, "value Draft(Ne".length()));
        Path fresh = root.resolve("tasks/new.vernac");
        open(fresh, "namespace tasks; value NewTitle(String value);");
        assertEquals(List.of("NewTitle"), completion(draft, 1, "value Draft(Ne".length()));
        close(fresh);
        assertEquals(List.of(), completion(draft, 1, "value Draft(Ne".length()));
    }

    @Test
    void unicodeDiagnosticsAndSemanticTokensUseUtf16AndSingleLineRanges() throws Exception {
        String body = "/* 😀 */ id 𐐀Id; value 名称(String größe);\n/* comment\n😀 second */ value Prüfung(Missing größe);";
        Path model = file("de.aufträge", "unicode", body);
        open(model);
        var diagnostic = diagnostics(model).getFirst();
        assertEquals(new Position(3, "😀 second */ value Prüfung(".length()), diagnostic.getRange().getStart());
        var tokens = server.getTextDocumentService().semanticTokensFull(new SemanticTokensParams(
                new TextDocumentIdentifier(model.toUri().toString()))).join().getData();
        String[] lines = Files.readString(model).split("\n", -1);
        int line = 0, column = 0;
        List<String> types = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i += 5) {
            int deltaLine = tokens.get(i);
            line += deltaLine;
            column = deltaLine == 0 ? column + tokens.get(i + 1) : tokens.get(i + 1);
            int end = column + tokens.get(i + 2);
            assertTrue(end <= lines[line].length(), "Token spans a line boundary");
            assertFalse(column < lines[line].length() && Character.isLowSurrogate(lines[line].charAt(column)));
            assertFalse(end < lines[line].length() && Character.isLowSurrogate(lines[line].charAt(end)));
            if (tokens.get(i + 3) == 1) types.add(lines[line].substring(column, end));
        }
        assertTrue(types.containsAll(List.of("𐐀Id", "名称", "Prüfung", "Missing")), types.toString());
    }

    static class RecordingClient implements LanguageClient {
        final Map<String, PublishDiagnosticsParams> latest = new HashMap<>();
        RegistrationParams registration;
        @Override public void publishDiagnostics(PublishDiagnosticsParams params) { latest.put(params.getUri(), params); }
        @Override public void telemetryEvent(Object object) { }
        @Override public void showMessage(MessageParams params) { }
        @Override public void logMessage(MessageParams params) { }
        @Override public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> registerCapability(RegistrationParams params) {
            registration = params;
            return CompletableFuture.completedFuture(null);
        }
    }
    @Test
    void inboundAccessCompletesOnlyAtTheAdapterPolicyAndHighlightsItsKeywords() throws Exception {
        String adapter = "adapter Pages inbound mvc {base \"/\"; ";
        Path model = file("pages", "pages", adapter
                + "access authenticated; home {GET \"/\"; render \"home\";} }");
        open(model);
        assertTrue(diagnostics(model).isEmpty(), diagnostics(model).toString());
        var document = new TextDocumentIdentifier(model.toUri().toString());
        var service = server.getTextDocumentService();
        var tokens = service.semanticTokensFull(new SemanticTokensParams(document)).join().getData();
        String line = Files.readAllLines(model).get(1);
        List<String> keywords = new ArrayList<>();
        int tokenLine = 0, column = 0;
        for (int i = 0; i < tokens.size(); i += 5) {
            int deltaLine = tokens.get(i);
            tokenLine += deltaLine;
            column = deltaLine == 0 ? column + tokens.get(i + 1) : tokens.get(i + 1);
            if (tokenLine == 1 && VernacLanguageServer.TOKEN_TYPES.get(tokens.get(i + 3))
                    .equals(SemanticTokenTypes.Keyword)) {
                keywords.add(line.substring(column, column + tokens.get(i + 2)));
            }
        }
        assertTrue(keywords.containsAll(List.of("access", "authenticated")));

        assertEquals(List.of("access"), completionsAtEnd(document, adapter + "acc", 2));
        assertEquals(List.of("public", "authenticated"), completionsAtEnd(document, adapter + "access ", 3));
        assertEquals(List.of("authenticated"), completionsAtEnd(document,
                "adapter Api inbound http uses Find finder {base \"/\"; access aut", 4));
        assertFalse(completionsAtEnd(document, adapter + "home {GET \"/\"; acc", 5).contains("access"));
        assertFalse(completionsAtEnd(document, adapter + "access public; acc", 6).contains("access"));
    }

    @Test
    void highlightsNativeSelectorsAndOffersNativeSnippetsInAnUnfinishedBody() throws Exception {
        String declaration = "id InvoiceId; aggregate Invoice[InvoiceId]() behavior { read BigDecimal tax(int amount) ";
        String body = declaration + "vernac { let net = amount asDecimal. return net roundedTo: 2 mode: RoundingMode HALF_UP. } };";
        Path model = file("pages", "invoice", body);
        open(model);
        assertTrue(diagnostics(model).isEmpty(), diagnostics(model).toString());
        var document = new TextDocumentIdentifier(model.toUri().toString());
        var service = server.getTextDocumentService();
        var tokens = service.semanticTokensFull(new SemanticTokensParams(document)).join().getData();
        Map<String, String> categories = new HashMap<>();
        int line = 0, column = 0;
        for (int i = 0; i < tokens.size(); i += 5) {
            int deltaLine = tokens.get(i);
            line += deltaLine;
            column = deltaLine == 0 ? column + tokens.get(i + 1) : tokens.get(i + 1);
            if (line == 1) {
                categories.put(body.substring(column, column + tokens.get(i + 2)),
                        VernacLanguageServer.TOKEN_TYPES.get(tokens.get(i + 3)));
            }
        }
        assertEquals(SemanticTokenTypes.Keyword, categories.get("vernac"));
        assertEquals(SemanticTokenTypes.Keyword, categories.get("let"));
        assertEquals(SemanticTokenTypes.Function, categories.get("asDecimal"));
        assertEquals(SemanticTokenTypes.Function, categories.get("roundedTo"));
        assertEquals(SemanticTokenTypes.Function, categories.get("mode"));
        assertEquals(SemanticTokenTypes.EnumMember, categories.get("HALF_UP"));

        var nativeItems = completionsAtEnd(document, declaration + "vernac { if amount > 0 { ", 2);
        assertTrue(nativeItems.containsAll(List.of("let", "require", "return", "if", "match", "collect:into:",
                "roundedTo:mode:", "ifTrue:ifFalse:")), nativeItems.toString());
        assertFalse(nativeItems.contains("namespace"));
        assertFalse(completionsAtEnd(document, declaration + "{ ", 3).contains("collect:into:"));
        assertFalse(completionsAtEnd(document, body + " ", 4).contains("collect:into:"));
    }

    @Test
    void navigatesNativeBindingsInTheUnsavedBufferWithLexicalScope() throws Exception {
        Path model = file("billing", "invoice", """
                id InvoiceId;
                value Item(int amount) list Items;
                aggregate Invoice[InvoiceId](Items items) behavior {
                    read int total(int bonus) vernac {
                        let net = self items sumBy: [item | item amount + bonus].
                        if net > 0 {
                            let branch = net + 1.
                            return branch.
                        }
                        return net.
                    }
                };
                """);
        open(model);
        assertTrue(diagnostics(model).isEmpty(), diagnostics(model).toString());
        String text = Files.readString(model);
        assertBinding(model, text, "return branch", "branch", "let branch");
        assertBinding(model, text, "return net", "net", "let net");
        assertBinding(model, text, "item amount", "item", "[item");
        assertBinding(model, text, "+ bonus", "bonus", "int bonus");

        String unsaved = text.replace("let net", "/* 🧭 */ let net").replace("return net.", "return branch.");
        change(model, unsaved, 2);
        assertBinding(model, unsaved, "let branch = net", "net", "let net");
        int escaped = unsaved.lastIndexOf("return branch") + "return ".length();
        Position cursor = positionOf(unsaved, escaped);
        assertEquals(List.of(), definition(model, cursor.getLine(), cursor.getCharacter()));
    }

    @Test
    void navigatesMatchBindingsAndUseCaseInputs() throws Exception {
        Path model = file("billing", "quote", """
                value Rate(int amount);
                value Quote(Rate? rate);
                usecase Estimate(Quote) returns Quote behavior {
                    execute vernac {
                        require match quote rate {
                            present found => found amount >= 0
                            absent => true
                        } else NegativeRate.
                        return quote.
                    }
                }
                """);
        open(model);
        String text = Files.readString(model);
        assertTrue(diagnostics(model).isEmpty(), diagnostics(model).toString());
        assertBinding(model, text, "found amount", "found", "present found");
        int input = text.indexOf("Estimate(Quote)") + "Estimate(".length();
        var source = positionOf(text, text.indexOf("quote rate"));
        assertEquals(List.of(new Location(model.toUri().toString(),
                        new Range(positionOf(text, input), positionOf(text, input + "Quote".length())))),
                definition(model, source.getLine(), source.getCharacter()));
    }

    @Test
    void navigatesTypedFieldPathsAndKeywordPartsInNativeBehaviorsAndRules() throws Exception {
        file("billing", "unrelated", "value Tax(int amount);");
        Path price = file("billing", "price", "value Price(int amount);");
        Path model = file("billing", "invoice", """
                id InvoiceId;
                aggregate Invoice[InvoiceId](Price price) validates {
                    require (self totalWith: 0 factor: 1) >= 0 else NegativeTotal.
                } behavior {
                    read int totalWith(int extra, int factor) vernac {
                        return (self price amount + extra) * factor.
                    }
                    read int withBonus(int factor) vernac {
                        return self totalWith: 1 factor: factor.
                    }
                };
                """);
        open(price);
        open(model);
        assertTrue(diagnostics(model).isEmpty(), diagnostics(model).toString());
        String text = Files.readString(model);
        String priceText = Files.readString(price);
        assertNativeTarget(model, text, "price amount", "price", model, text, "Price price");
        assertNativeTarget(model, text, "price amount", "amount", price, priceText, "int amount");
        assertNativeTarget(model, text, "totalWith: 0", "totalWith:", model, text, "read int totalWith");
        assertNativeTarget(model, text, "factor: 1", "factor:", model, text, "int factor");
        assertNativeTarget(model, text, "totalWith: 1", "totalWith:", model, text, "read int totalWith");

        String unsavedPrice = priceText.replace("value Price", "\nvalue Price");
        change(price, unsavedPrice, 2);
        String unsaved = text.replace("return (self price", "/* 🧭 */ return (self price")
                .replace("factor: factor.", "factor: false.");
        change(model, unsaved, 2);
        assertFalse(diagnostics(model).isEmpty());
        assertNativeTarget(model, unsaved, "price amount", "amount", price, unsavedPrice, "int amount");
        assertNativeTarget(model, unsaved, "factor: false", "factor:", model, unsaved, "int factor");
    }

    @Test
    void completesTypedFieldsAndKeywordMessagesFromUnsavedNativeExpressions() throws Exception {
        file("billing", "tax", "value Tax(int subtotalTax);");
        Path price = file("billing", "price", "value Price(int amount);");
        Path model = file("billing", "invoice", """
                id InvoiceId;
                aggregate Invoice[InvoiceId](mut Price price) validates {
                    require (self totalWith: 0 fa: 1) >= 0 else NegativeTotal.
                } behavior {
                    read int totalWith(int extra, int factor) vernac {
                        return (self price amount + extra) * factor.
                    }
                    modify void replaceWith(Price next) vernac {
                        self price = next.
                    }
                    read int withBonus(int factor) vernac {
                        return self totalWith: 1 factor: factor.
                    }
                };
                """);
        open(model);
        String text = Files.readString(model);
        var keywordPart = nativeCompletion(model, text, " fa:", " fa", "factor");
        assertEquals("factor", keywordPart.getTextEdit().getLeft().getNewText());

        String partial = text.replace("return self totalWith: 1 factor: factor.", "return self tot.");
        change(model, partial, 2);
        var selector = nativeCompletion(model, partial, "self tot.", "self tot", "totalWith:factor:");
        assertEquals("totalWith: ${1:extra} factor: ${2:factor}", selector.getTextEdit().getLeft().getNewText());
        assertEquals(InsertTextFormat.Snippet, selector.getInsertTextFormat());
        assertEquals("tot", partial.lines().toList().get(selector.getTextEdit().getLeft().getRange().getStart().getLine())
                .substring(selector.getTextEdit().getLeft().getRange().getStart().getCharacter(),
                        selector.getTextEdit().getLeft().getRange().getEnd().getCharacter()));

        String selectorPrefix = partial.replace("self price = next.", "self repl.");
        change(model, selectorPrefix, 3);
        nativeCompletion(model, selectorPrefix, "self repl.", "self repl", "replaceWith:");

        String unsavedPrice = Files.readString(price).replace("amount", "subtotal");
        open(price, unsavedPrice);
        String fieldPrefix = text.replace("self price amount", "self price sub");
        change(model, fieldPrefix, 4);
        var field = nativeCompletion(model, fieldPrefix, "price sub", "price sub", "subtotal");
        assertEquals(CompletionItemKind.Field, field.getKind());
        assertEquals("subtotal", field.getTextEdit().getLeft().getNewText());

        String partialKeyword = fieldPrefix.replace("totalWith: 1 factor: factor.", "totalWith: 1 fa.");
        change(model, partialKeyword, 5);
        var suffix = nativeCompletion(model, partialKeyword, "1 fa.", "1 fa", "factor:");
        assertEquals("factor: ${1:factor}", suffix.getTextEdit().getLeft().getNewText());

        String emptyField = fieldPrefix.replace("self price sub", "self price ");
        change(model, emptyField, 6);
        nativeCompletion(model, emptyField, "price  +", "price ", "subtotal");
    }

    @Test
    void completesNativeUseCasePortSelectorsForTheCurrentEffect() throws Exception {
        Path model = file("billing", "inventory", """
                value ItemNumber(int value);
                port Inventory { ItemNumber lookup(ItemNumber number); void remove(ItemNumber number); }
                usecase Remove(ItemNumber) returns ItemNumber uses Inventory behavior {
                    execute vernac {
                        let current = inventory lo.
                        inventory re.
                        return current.
                    }
                }
                """);
        open(model);
        String text = Files.readString(model);
        nativeCompletion(model, text, "inventory lo.", "inventory lo", "lookup:");
        String statement = text.replace("inventory lo.", "inventory lookup: itemNumber.");
        change(model, statement, 2);
        nativeCompletion(model, statement, "inventory re.", "inventory re", "remove:");
    }

    private CompletionItem nativeCompletion(Path source, String text, String usage, String typed, String label) {
        var at = positionOf(text, text.indexOf(usage) + typed.length());
        var items = server.getTextDocumentService()
                .completion(new CompletionParams(new TextDocumentIdentifier(source.toUri().toString()), at)).join().getLeft();
        return items.stream().filter(item -> item.getLabel().equals(label)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing " + label + " in "
                        + items.stream().map(CompletionItem::getLabel).toList()));
    }

    private void assertNativeTarget(Path source, String sourceText, String usage, String selector,
                                    Path target, String targetText, String declaration) {
        var at = positionOf(sourceText, sourceText.indexOf(usage) + usage.indexOf(selector));
        var targetPosition = positionOf(targetText, targetText.indexOf(declaration));
        assertEquals(List.of(new Location(target.toUri().toString(), new Range(targetPosition, targetPosition))),
                definition(source, at.getLine(), at.getCharacter()));
    }

    private void assertBinding(Path file, String text, String usage, String name, String declaration) {
        int reference = text.indexOf(usage) + usage.lastIndexOf(name);
        int target = text.indexOf(declaration) + declaration.lastIndexOf(name);
        var at = positionOf(text, reference);
        var locations = definition(file, at.getLine(), at.getCharacter());
        assertEquals(List.of(new Location(file.toUri().toString(),
                new Range(positionOf(text, target), positionOf(text, target + name.length())))), locations);
    }

    private static Position positionOf(String text, int offset) {
        String prefix = text.substring(0, offset);
        return new Position((int) prefix.chars().filter(c -> c == '\n').count(), offset - prefix.lastIndexOf('\n') - 1);
    }

    private List<String> completionsAtEnd(TextDocumentIdentifier document, String line, int version) {
        change(Path.of(java.net.URI.create(document.getUri())), "namespace pages;\n" + line, version);
        return server.getTextDocumentService()
                .completion(new CompletionParams(document, new Position(1, line.length())))
                .join().getLeft().stream().map(CompletionItem::getLabel).toList();
    }

}
