// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.lsp;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.eclipse.lsp4j.*;
import org.vernac.compiler.modules.*;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.persistence.SchemaModel;
import static org.junit.jupiter.api.Assertions.*;

class VocabularySymbolsTest {
    @TempDir Path root;
    private VernacProjectDiagnostics projects() throws Exception {
        Files.writeString(root.resolve("pom.xml"),"<project/>");
        var projects=new VernacProjectDiagnostics();var params=new InitializeParams();params.setRootUri(root.toUri().toString());projects.initialize(params);return projects;
    }
    @Test void typesAndStandaloneQuotedTermsHaveHoverAndTermNavigationAcrossFiles() throws Exception {
        Path model=root.resolve("src/main/vernac/sales/model.vernac"),words=model.resolveSibling("words.vernac");Files.createDirectories(model.getParent());
        String text="namespace sales; value Order(String); value Copy(Order);";
        String glossary="namespace sales; term Order {description \"A submitted request.\";example \"A paid order.\";note \"Not a cart.\";} term \"Rückständiger Betrag 😀\" {description \"Amount still owed.\";}";
        Files.writeString(model,text);Files.writeString(words,glossary);var projects=projects();projects.open(model.toUri().toString(),text,1);
        String hover=projects.hover(model.toUri().toString(),new Position(0,text.lastIndexOf("Order")+2));
        assertTrue(hover.contains("A submitted request."));assertTrue(hover.contains("A paid order."));assertTrue(hover.contains("Not a cart."));
        assertTrue(hover.contains("VALUE_OBJECT"));
        projects.open(words.toUri().toString(),glossary,1);
        var at=new Position(0,glossary.indexOf("Rückständiger")+2);
        assertTrue(projects.hover(words.toUri().toString(),at).contains("Amount still owed."));
        var link=projects.definition(words.toUri().toString(),new Position(0,glossary.indexOf("Order")+2)).getFirst();assertEquals(model.toUri().toString(),link.getUri());
        assertFalse(projects.complete(model.toUri().toString(),new Position(0,text.lastIndexOf("Order")+2)).stream().anyMatch(c->c.getLabel().equals("Rückständiger Betrag 😀")));
        assertTrue(projects.complete(words.toUri().toString(),new Position(0,glossary.indexOf("description"))).stream().anyMatch(c->c.getLabel().equals("example")));
        var service=new VernacTextDocumentService(projects);var params=new HoverParams(new TextDocumentIdentifier(model.toUri().toString()),new Position(0,text.lastIndexOf("Order")+2));
        assertTrue(service.hover(params).join().getContents().getRight().getValue().contains("A submitted request."));
    }
    @Test void exportedTypeDocumentationAppearsWithoutSourcesAndRefreshesAfterDependencyRebuild() throws Exception {
        Path file=root.resolve("producer/lib/model.vernac");Files.createDirectories(file.getParent());
        String model="namespace lib; term Name {description \"Name from the module.\";example \"Alice\";} value Name(String);";Files.writeString(file,model);
        var compiler=new VernacCompiler();var resolved=compiler.analyzeProject(root.resolve("producer"));
        var descriptor=ModuleContracts.publish(ModuleDefinition.parse(root.resolve("module.vmodule"),"module example.names {export lib.Name;}"),resolved,compiler.generate(resolved,SchemaModel.empty(),Map.of()).generatedFiles(),"example","names","1",List.of());
        Files.createDirectories(root.resolve("target"));Path index=root.resolve("target/vernac-module-index.json");Files.writeString(index,ModuleDescriptor.JSON.writeValueAsString(List.of(descriptor)));
        Path app=root.resolve("src/main/vernac/app/model.vernac");Files.createDirectories(app.getParent());String text="namespace app; import lib.Name; value Local(Name);";Files.writeString(app,text);
        var projects=projects();projects.open(app.toUri().toString(),text,1);var at=new Position(0,text.lastIndexOf("Name")+2);
        assertTrue(projects.hover(app.toUri().toString(),at).contains("Name from the module."));
        Path next=index.resolveSibling("next.json");Files.writeString(next,Files.readString(index).replace("Name from the module.","Updated module wording."));Files.move(next,index,StandardCopyOption.REPLACE_EXISTING);
        assertTrue(projects.hover(app.toUri().toString(),at).contains("Updated module wording."));
        assertFalse(Files.exists(root.resolve("target/vernac-module-sources.json")));
    }
}
