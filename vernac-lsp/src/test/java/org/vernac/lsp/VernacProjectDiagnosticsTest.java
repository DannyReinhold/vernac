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
}
