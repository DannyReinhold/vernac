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
class ModuleDefinitionSymbolsTest {
    @TempDir Path root;
    @Test void manifestExportsCompleteNavigateAndUseSharedCompilerDiagnostics() throws Exception {
        Path source=root.resolve("src/main/vernac/demo/model.vernac");Files.createDirectories(source.getParent());Files.writeString(root.resolve("pom.xml"),"<project/>");String model="namespace demo; value Name(String) list Names;";Files.writeString(source,model);
        var symbols=new VernacProjectSymbols(root.resolve("src/main/vernac"),Map.of(source,model));
        String manifest="module demo.values { export demo.Name; }";
        assertTrue(symbols.moduleComplete(manifest,new Position(0,manifest.indexOf("demo.Name")+6)).stream().anyMatch(c->c.getLabel().equals("demo.Name")));
        assertEquals(source.toUri().toString(),symbols.moduleDefinition(manifest,new Position(0,manifest.indexOf("demo.Name")+6)).getFirst().getUri());
        var projects=new VernacProjectDiagnostics();var params=new InitializeParams();params.setRootUri(root.toUri().toString());projects.initialize(params);
        List<PublishDiagnosticsParams> published=new ArrayList<>();projects.connect(new org.eclipse.lsp4j.services.LanguageClient(){
            public void telemetryEvent(Object o){}public void publishDiagnostics(PublishDiagnosticsParams p){published.add(p);}public void showMessage(MessageParams p){}public java.util.concurrent.CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams p){return java.util.concurrent.CompletableFuture.completedFuture(null);}public void logMessage(MessageParams p){}
        });
        Path file=root.resolve(ModuleDefinition.FILE);projects.open(file.toUri().toString(),"module demo.values {export demo.Unknown;}",1);
        assertTrue(published.stream().flatMap(p->p.getDiagnostics().stream()).anyMatch(d->d.getMessage().contains("Unknown local type-module export")));
    }
    @Test void dependencyValuesCompleteAndNavigateToReadOnlyContractFallback() throws Exception {
        Path source=root.resolve("producer/lib/values/model.vernac");Files.createDirectories(source.getParent());Files.writeString(source,"namespace lib.values; value Name(String);");
        var compiler=new VernacCompiler();var resolved=compiler.analyzeProject(root.resolve("producer"),List.of());var generated=compiler.generate(resolved,SchemaModel.empty(),Map.of());
        var descriptor=ModuleContracts.publish(ModuleDefinition.parse(root.resolve("producer/manifest.vmodule"),"module lib.values {export lib.values.Name;}"),resolved,generated.generatedFiles(),"org.example","values","1",List.of());
        Files.writeString(root.resolve("pom.xml"),"<project/>");Files.createDirectories(root.resolve("target"));Files.writeString(root.resolve("target/vernac-module-index.json"),ModuleDescriptor.JSON.writeValueAsString(List.of(descriptor)));
        Path local=root.resolve("src/main/vernac/app/model.vernac");Files.createDirectories(local.getParent());String text="namespace app; import lib.values.Name; value Local(Name);";Files.writeString(local,text);
        var projects=new VernacProjectDiagnostics();var params=new InitializeParams();params.setRootUri(root.toUri().toString());projects.initialize(params);projects.open(local.toUri().toString(),text,1);
        var target=projects.definition(local.toUri().toString(),new Position(0,text.lastIndexOf("Name")+1));assertFalse(target.isEmpty());
        assertTrue(target.getFirst().getUri().contains("target/vernac-module-contracts"));
        assertTrue(Files.readString(Path.of(java.net.URI.create(target.getFirst().getUri()))).contains("Dependency contract view"));
        Path attachment=root.resolve("values-sources.jar");
        String original="// Attached original source\nnamespace lib.values;\nvalue Name(String);\n";
        try(var jar=new java.util.jar.JarOutputStream(Files.newOutputStream(attachment))) {
            jar.putNextEntry(new java.util.jar.JarEntry("vernac/lib/values/model.vernac"));jar.write(original.getBytes(java.nio.charset.StandardCharsets.UTF_8));jar.closeEntry();
        }
        Files.writeString(root.resolve("target/vernac-module-sources.json"),ModuleDescriptor.JSON.writeValueAsString(Map.of(descriptor.module(),attachment.toString())));
        projects.refresh();
        var attached=projects.definition(local.toUri().toString(),new Position(0,text.lastIndexOf("Name")+1)).getFirst();
        assertTrue(attached.getUri().contains("/attached/vernac/"));assertEquals(2,attached.getRange().getStart().getLine());assertEquals(6,attached.getRange().getStart().getCharacter());
        assertTrue(projects.complete(local.toUri().toString(),new Position(0,text.lastIndexOf("Name")+1)).stream().anyMatch(c->c.getLabel().equals("Name")));
    }
}
