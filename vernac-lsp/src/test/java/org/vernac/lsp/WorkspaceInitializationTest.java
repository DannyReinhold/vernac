// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.lsp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercise actual initialization wire fields, including old clients and precedence. */
class WorkspaceInitializationTest {
    @TempDir Path directory;

    @Test
    void workspaceFoldersTakePrecedenceOverLegacyUriAndPath() throws Exception {
        var wire = legacyRoots("legacy-uri", "legacy-path");
        var folder = new JsonObject();
        folder.addProperty("uri", directory.resolve("modern").toUri().toString());
        folder.addProperty("name", "modern");
        var folders = new JsonArray();
        folders.add(folder);
        wire.add("workspaceFolders", folders);
        assertWorkspace(wire, "modern", "legacy-uri", "legacy-path");
    }

    @Test
    void legacyRootUriRemainsSupportedAndTakesPrecedenceOverRootPath() throws Exception {
        var wire = legacyRoots("legacy-uri", "legacy-path");
        // Empty modern folders must preserve the legacy fallback as well.
        wire.add("workspaceFolders", new JsonArray());
        assertWorkspace(wire, "legacy-uri", "legacy-path");
    }

    @Test
    void legacyRootPathRemainsSupportedWithoutRootUri() throws Exception {
        var wire = legacyRoots(null, "legacy-path");
        assertWorkspace(wire, "legacy-path", "outside");
    }

    private JsonObject legacyRoots(String uri, String path) {
        var wire = new JsonObject();
        if (uri != null) wire.addProperty("rootUri", directory.resolve(uri).toUri().toString());
        wire.addProperty("rootPath", directory.resolve(path).toString());
        return wire;
    }

    private void assertWorkspace(JsonObject wire, String accepted, String... rejected)
            throws Exception {
        var project = new VernacProjectDiagnostics();
        var client = new RecordingClient();
        project.connect(client);
        project.initialize(new Gson().fromJson(wire, InitializeParams.class));
        String acceptedUri = open(project, accepted);
        assertTrue(client.diagnostics.get(acceptedUri).isEmpty(),
                () -> client.diagnostics.get(acceptedUri).toString());
        for (String outside : rejected) {
            String uri = open(project, outside);
            assertTrue(client.diagnostics.get(uri).stream().anyMatch(d ->
                    d.getMessage().contains("below a workspace src/main/vernac")), outside);
        }
    }

    private String open(VernacProjectDiagnostics project, String workspace) throws Exception {
        Path source = directory.resolve(workspace).resolve("src/main/vernac/sample/model.vernac");
        Files.createDirectories(source.getParent());
        String model = "namespace sample; value Name(String);";
        Files.writeString(source, model);
        String uri = source.toUri().toString();
        project.open(uri, model, 1);
        return uri;
    }

    private static final class RecordingClient implements LanguageClient {
        final Map<String, List<Diagnostic>> diagnostics = new HashMap<>();
        public void telemetryEvent(Object event) {}
        public void publishDiagnostics(PublishDiagnosticsParams params) {
            diagnostics.put(params.getUri(), params.getDiagnostics());
        }
        public void showMessage(MessageParams params) {}
        public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
            return CompletableFuture.completedFuture(null);
        }
        public void logMessage(MessageParams params) {}
    }
}
