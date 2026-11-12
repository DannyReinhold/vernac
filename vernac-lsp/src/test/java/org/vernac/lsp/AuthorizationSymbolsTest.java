// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.lsp;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthorizationSymbolsTest {
    @TempDir Path root;
    @Test void unsavedPermissionMembersUseTheCompilerAndRecoverAfterAnError() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Path model = root.resolve("src/main/vernac/sample/model.vernac"); Files.createDirectories(model.getParent());
        String valid = "namespace sample; permission Rights {VIEW;} id InvoiceId; usecase Read(InvoiceId) requires permission Rights.VIEW on invoiceId behavior {execute {}}";
        Files.writeString(model, valid); var messages = new ArrayList<PublishDiagnosticsParams>();
        var project = new VernacProjectDiagnostics(); var params = new InitializeParams(); params.setRootUri(root.toUri().toString()); project.initialize(params);
        project.connect(new LanguageClient() {
            public void telemetryEvent(Object value) {}
            public void publishDiagnostics(PublishDiagnosticsParams message) { messages.add(message); }
            public void showMessage(MessageParams message) {}
            public java.util.concurrent.CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams message) { return java.util.concurrent.CompletableFuture.completedFuture(null); }
            public void logMessage(MessageParams message) {}
        });
        String uri=model.toUri().toString(); project.open(uri, valid, 1);
        assertFalse(last(messages, uri).stream().anyMatch(d -> d.getSeverity() == DiagnosticSeverity.Error));
        project.change(uri, valid.replace("Rights.VIEW on", "Rights.UNKNOWN on"), 2);
        assertTrue(last(messages, uri).stream().anyMatch(d -> d.getMessage().contains("Unknown permission member")));
        project.change(uri, valid, 3);
        assertFalse(last(messages, uri).stream().anyMatch(d -> d.getSeverity() == DiagnosticSeverity.Error));
    }
    private List<Diagnostic> last(List<PublishDiagnosticsParams> messages, String uri) {
        return messages.stream().filter(m -> m.getUri().equals(uri)).reduce((a,b) -> b).orElseThrow().getDiagnostics();
    }
}
