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
class PortModuleSymbolsTest {
    @TempDir Path root;
    @Test void externalPortNavigatesToContractOrActualSources() throws Exception {
        Path producer=root.resolve("producer/provider/api/model.vernac");Files.createDirectories(producer.getParent());
        String original="namespace provider.api;\nport PaymentProvider { void ping(); }\n";Files.writeString(producer,original);
        var compiler=new VernacCompiler();var resolved=compiler.analyzeProject(root.resolve("producer"),List.of());
        var generated=compiler.generate(resolved,SchemaModel.empty(),Map.of());
        var module=ModuleContracts.publish(ModuleDefinition.parse(root.resolve("manifest.vmodule"),"module provider.contracts {export provider.api.PaymentProvider;}"),resolved,generated.generatedFiles(),"example","provider","1",List.of());
        Files.writeString(root.resolve("pom.xml"),"<project/>");Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("target/vernac-module-index.json"),ModuleDescriptor.JSON.writeValueAsString(List.of(module)));
        Path model=root.resolve("src/main/vernac/consumer/model.vernac");Files.createDirectories(model.getParent());
        String text="namespace consumer; import provider.api.PaymentProvider; adapter Noop implements PaymentProvider {behavior {public void ping() {}}}";Files.writeString(model,text);
        var projects=new VernacProjectDiagnostics();var params=new InitializeParams();params.setRootUri(root.toUri().toString());projects.initialize(params);projects.open(model.toUri().toString(),text,1);
        Position at=new Position(0,text.lastIndexOf("PaymentProvider")+3);
        var target=projects.definition(model.toUri().toString(),at).getFirst();
        assertTrue(Files.readString(Path.of(java.net.URI.create(target.getUri()))).contains("port PaymentProvider"));
        Path sources=root.resolve("provider-sources.jar");
        try(var jar=new java.util.jar.JarOutputStream(Files.newOutputStream(sources))) {
            jar.putNextEntry(new java.util.jar.JarEntry("vernac/provider/api/model.vernac"));jar.write(original.getBytes(java.nio.charset.StandardCharsets.UTF_8));jar.closeEntry();
        }
        Files.writeString(root.resolve("target/vernac-module-sources.json"),ModuleDescriptor.JSON.writeValueAsString(Map.of(module.module(),sources.toString())));projects.refresh();
        var attached=projects.definition(model.toUri().toString(),at).getFirst();
        assertTrue(attached.getUri().contains("/attached/"));assertEquals(1,attached.getRange().getStart().getLine());assertEquals(5,attached.getRange().getStart().getCharacter());
        assertTrue(projects.complete(model.toUri().toString(),at).stream().anyMatch(c->c.getLabel().equals("PaymentProvider")));
    }
}
