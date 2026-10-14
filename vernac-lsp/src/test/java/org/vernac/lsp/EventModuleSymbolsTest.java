// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.lsp;

import java.nio.file.*;
import java.util.*;
import org.eclipse.lsp4j.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.modules.*;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.persistence.SchemaModel;
import static org.junit.jupiter.api.Assertions.*;

class EventModuleSymbolsTest {
    @TempDir Path root;
    @Test void moduleExportCompletionOffersEventsButNotInternalReceivers() throws Exception {
        Path source=root.resolve("src/main/vernac/events/model.vernac");
        String model="namespace events; value Name(String); event Changed(Name); synchronous listener Observe on Changed behavior {execute {}}";
        var symbols=new VernacProjectSymbols(root.resolve("src/main/vernac"),Map.of(source,model));
        String text="module example.events {export events.";
        var completions=symbols.moduleComplete(text,new Position(0,text.length()));
        assertTrue(completions.stream().anyMatch(c->c.getLabel().equals("events.Changed")));
        assertFalse(completions.stream().anyMatch(c->c.getLabel().equals("events.Observe")));
    }
    @Test void dependencyEventsNavigateAsEventsAndListenerOnlyModulesCanBeActivated() throws Exception {
        Path producer=root.resolve("producer/events/model.vernac");Files.createDirectories(producer.getParent());
        Files.writeString(producer,"namespace events; value Name(String); event Changed(Name); synchronous listener Observe on Changed behavior {execute {}}");
        var compiler=new VernacCompiler();var resolved=compiler.analyzeProject(root.resolve("producer"));var generated=compiler.generate(resolved,SchemaModel.empty(),Map.of());
        var module=ModuleContracts.publish(ModuleDefinition.parse(root.resolve("module.vmodule"),"module example.events {export events.Name;export events.Changed;}"),resolved,generated.generatedFiles(),"example","events","1",List.of());
        Files.writeString(root.resolve("pom.xml"),"<project/>");Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("target/vernac-module-index.json"),ModuleDescriptor.JSON.writeValueAsString(List.of(module)));
        Path source=root.resolve("src/main/vernac/app/model.vernac");Files.createDirectories(source.getParent());
        String text="namespace app; import events.Changed; synchronous listener Local on Changed behavior {execute {}}";Files.writeString(source,text);
        var projects=new VernacProjectDiagnostics();var params=new InitializeParams();params.setRootUri(root.toUri().toString());projects.initialize(params);projects.open(source.toUri().toString(),text,1);
        var at=new Position(0,text.lastIndexOf("Changed")+2);var target=projects.definition(source.toUri().toString(),at).getFirst();
        assertTrue(Files.readString(Path.of(java.net.URI.create(target.getUri()))).contains("event Changed(events.Name name)"));
        assertTrue(projects.complete(source.toUri().toString(),at).stream().anyMatch(c->c.getLabel().equals("Changed")));
        String application="application app {activate example.";String uri=root.resolve("vernac-application.vapplication").toUri().toString();projects.open(uri,application,1);
        assertTrue(projects.complete(uri,new Position(0,application.length())).stream().anyMatch(c->c.getLabel().equals("example.events")));
        Path attachment=root.resolve("events-sources.jar");
        String original="// Original source with Unicode: Ä\nnamespace events;\nevent Changed(Name);\nvalue Name(String);\n";
        try(var jar=new java.util.jar.JarOutputStream(Files.newOutputStream(attachment))) {
            jar.putNextEntry(new java.util.jar.JarEntry("vernac/events/model.vernac"));
            jar.write(original.getBytes(java.nio.charset.StandardCharsets.UTF_8));jar.closeEntry();
        }
        Files.writeString(root.resolve("target/vernac-module-sources.json"),ModuleDescriptor.JSON.writeValueAsString(Map.of(module.module(),attachment.toString())));
        // Index changes are detected on the next request without an explicit refresh call.
        var attached=projects.definition(source.toUri().toString(),at).getFirst();
        assertTrue(attached.getUri().contains("/attached/vernac/"));
        assertEquals(2,attached.getRange().getStart().getLine());assertEquals(6,attached.getRange().getStart().getCharacter());
    }
}
