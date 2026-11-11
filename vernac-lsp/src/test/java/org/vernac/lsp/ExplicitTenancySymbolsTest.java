// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.lsp;

import java.nio.file.*;
import java.util.*;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ExplicitTenancySymbolsTest {
    @TempDir Path root;
    private final List<PublishDiagnosticsParams> messages = new ArrayList<>();
    private VernacProjectDiagnostics project() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        var projects = new VernacProjectDiagnostics();
        var params = new InitializeParams(); params.setRootUri(root.toUri().toString()); projects.initialize(params);
        projects.connect(new LanguageClient() {
            public void telemetryEvent(Object o) {}
            public void publishDiagnostics(PublishDiagnosticsParams p) { messages.add(p); }
            public void showMessage(MessageParams p) {}
            public java.util.concurrent.CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams p) { return java.util.concurrent.CompletableFuture.completedFuture(null); }
            public void logMessage(MessageParams p) {}
        });
        return projects;
    }
    private boolean classificationError(String uri) {
        return messages.stream().filter(p -> p.getUri().equals(uri)).reduce((a,b) -> b).orElseThrow()
                .getDiagnostics().stream().anyMatch(d -> d.getMessage().contains("tenancy explicit"));
    }
    @Test void unsavedApplicationPolicyAndModelChangesShareCompilerRule() throws Exception {
        var projects = project();
        Path source = root.resolve("src/main/vernac/example/model.vernac"); Files.createDirectories(source.getParent());
        String text = "namespace example; id InvoiceId; aggregate Invoice[InvoiceId](); repository InvoiceRepository for Invoice {}";
        Files.writeString(source, text);
        String uri = source.toUri().toString(); projects.open(uri, text, 1);
        Path application = root.resolve("vernac-application.vapplication");
        Files.writeString(application, "application example.app { tenancy explicit; }");
        projects.open(application.toUri().toString(), "application example.app { tenancy explicit; }", 1);
        assertTrue(classificationError(uri));
        projects.change(application.toUri().toString(), "application example.app {}", 2);
        assertFalse(classificationError(uri), "Unsaved editor text must override the saved strict rule");
        projects.change(application.toUri().toString(), "application example.app { tenancy explicit; }", 3);
        projects.change(uri, text.replace("aggregate Invoice", "global aggregate Invoice"), 2);
        assertFalse(classificationError(uri));
        var definitions = projects.definition(uri, new Position(0, text.replace("aggregate Invoice", "global aggregate Invoice").lastIndexOf("InvoiceId]") + 2));
        assertFalse(definitions.isEmpty());
    }
    @Test void unsavedModulePolicyAlsoChecksPrivatePersistedTypes() throws Exception {
        var projects = project();
        Path source = root.resolve("src/main/vernac/example/model.vernac"); Files.createDirectories(source.getParent());
        String text = "namespace example; id InvoiceId; aggregate Invoice[InvoiceId](); repository InvoiceRepository for Invoice {}";
        Files.writeString(source, text); projects.open(source.toUri().toString(), text, 1);
        projects.open(root.resolve("vernac-module.vmodule").toUri().toString(), "module example.library { tenancy explicit; }", 1);
        assertTrue(classificationError(source.toUri().toString()));
    }
    @Test void compositionCompletionsOfferTheExplicitRule() throws Exception {
        var projects = project();
        Path source=root.resolve("src/main/vernac/example/model.vernac"); Files.createDirectories(source.getParent());
        Files.writeString(source,"namespace example; value Name(String);");
        for(String extension:List.of("vapplication","vmodule")) {
            String file=extension.equals("vmodule")?"vernac-module.vmodule":"vernac-application.vapplication";
            String header=extension.equals("vmodule")?"module example.library":"application example.app";
            String text=header+" { ten";
            String uri=root.resolve(file).toUri().toString();
            projects.open(uri,text,1);
            assertTrue(projects.complete(uri,new Position(0,text.length())).stream().anyMatch(c->c.getLabel().equals("tenancy explicit;")));
            text=header+" { tenancy ";
            projects.change(uri,text,2);
            assertTrue(projects.complete(uri,new Position(0,text.length())).stream().anyMatch(c->c.getLabel().equals("explicit;")));
            projects.close(uri);
        }
    }

}
