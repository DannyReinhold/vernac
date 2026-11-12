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
    @Test void exportedPermissionDocumentationAndDiagnosticsWorkWithoutProducerSources() throws Exception {
        Path producer=root.resolve("producer/library/security/model.vernac"); Files.createDirectories(producer.getParent());
        Files.writeString(producer,"namespace library.security; term Rights {description \"Rights provided by the library.\";} permission Rights {VIEW;}");
        var compiler=new org.vernac.compiler.pipeline.VernacCompiler();
        var resolved=compiler.analyzeProject(root.resolve("producer"));
        var module=org.vernac.compiler.modules.ModuleContracts.publish(
                org.vernac.compiler.modules.ModuleDefinition.parse(root.resolve("module.vmodule"),"module example.security {export library.security.Rights;}"),
                resolved,compiler.generate(resolved,org.vernac.compiler.persistence.SchemaModel.empty(),Map.of()).generatedFiles(),"example","security","1",List.of());
        Files.createDirectories(root.resolve("target")); Files.writeString(root.resolve("target/vernac-module-index.json"),
                org.vernac.compiler.modules.ModuleDescriptor.JSON.writeValueAsString(List.of(module)));
        Files.writeString(root.resolve("pom.xml"),"<project/>");
        Path source=root.resolve("src/main/vernac/app/model.vernac"); Files.createDirectories(source.getParent());
        String text="namespace app; import library.security.Rights; usecase Read() requires permission Rights.VIEW behavior {execute {}}";
        Files.writeString(source,text); var project=new VernacProjectDiagnostics();var params=new InitializeParams();params.setRootUri(root.toUri().toString());project.initialize(params);
        var messages=new ArrayList<PublishDiagnosticsParams>();
        project.connect(new LanguageClient() {
            public void telemetryEvent(Object value) {}
            public void publishDiagnostics(PublishDiagnosticsParams value) {messages.add(value);}
            public void showMessage(MessageParams value) {}
            public java.util.concurrent.CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams value) {return java.util.concurrent.CompletableFuture.completedFuture(null);}
            public void logMessage(MessageParams value) {}
        });
        String uri=source.toUri().toString();project.open(uri,text,1);
        assertFalse(last(messages,uri).stream().anyMatch(d->d.getSeverity()==DiagnosticSeverity.Error));
        var at=new Position(0,text.indexOf("Rights")+2);
        assertTrue(project.hover(uri,at).contains("Rights provided by the library."));
        var location=project.definition(uri,at).getFirst();
        assertTrue(location.getUri().contains("vernac-module-contracts"));
        assertTrue(Files.readString(Path.of(java.net.URI.create(location.getUri()))).contains("permission Rights"));
        project.change(uri,text.replace("Rights.VIEW","Rights.UNKNOWN"),2);
        assertTrue(last(messages,uri).stream().anyMatch(d->d.getMessage().contains("Unknown permission member")));
        assertFalse(Files.exists(root.resolve("target/vernac-module-sources.json")));
    }
    private List<Diagnostic> last(List<PublishDiagnosticsParams> messages, String uri) {
        return messages.stream().filter(m -> m.getUri().equals(uri)).reduce((a,b) -> b).orElseThrow().getDiagnostics();
    }
}
