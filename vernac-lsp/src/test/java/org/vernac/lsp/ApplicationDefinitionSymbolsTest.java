// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.lsp;
import java.nio.file.*;
import java.util.*;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.application.ApplicationDefinition;
import static org.junit.jupiter.api.Assertions.*;
class ApplicationDefinitionSymbolsTest {
    @TempDir Path root;
    private org.vernac.compiler.modules.ModuleDescriptor internalModule() throws Exception {
        Path file=root.resolve("producer/support/model.vernac");Files.createDirectories(file.getParent());
        Files.writeString(file,"namespace support; service Internal behavior {public String text(){return \"ok\";}}");
        var compiler=new org.vernac.compiler.pipeline.VernacCompiler();var resolved=compiler.analyzeProject(root.resolve("producer"));
        return org.vernac.compiler.modules.ModuleContracts.publish(
            org.vernac.compiler.modules.ModuleDefinition.parse(root.resolve("producer/module.vmodule"),"module example.support {}"),
            resolved,compiler.generate(resolved,org.vernac.compiler.persistence.SchemaModel.empty(),Map.of()).generatedFiles(),"example","support","1",List.of());
    }
    @Test void activationNavigatesToTheLogicalModuleEvenWithoutExportsAndFromTheMiddleOfItsName() throws Exception {
        var module=internalModule();Files.writeString(root.resolve("pom.xml"),"<project/>");Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("target/vernac-module-index.json"),org.vernac.compiler.modules.ModuleDescriptor.JSON.writeValueAsString(List.of(module)));
        Path source=root.resolve("src/main/vernac/app/model.vernac");Files.createDirectories(source.getParent());Files.writeString(source,"namespace app; value Name(String);");
        var projects=new VernacProjectDiagnostics();var params=new InitializeParams();params.setRootUri(root.toUri().toString());projects.initialize(params);
        String text="application app {activate example.support;}",uri=root.resolve(ApplicationDefinition.FILE).toUri().toString();projects.open(uri,text,1);
        for(int character:List.of(text.indexOf("example.support")+1,text.indexOf("support")+3)) {
            var location=projects.definition(uri,new Position(0,character)).getFirst();
            String overview=Files.readString(Path.of(java.net.URI.create(location.getUri())));
            assertTrue(overview.contains("module example.support {"));assertTrue(overview.contains("DOMAIN_SERVICE support.Internal"));
            String line=overview.split("\n")[location.getRange().getStart().getLine()];
            assertEquals("example.support",line.substring(location.getRange().getStart().getCharacter(),location.getRange().getEnd().getCharacter()));
            assertTrue(location.getUri().endsWith("/module.vmodule"));
            projects.open(location.getUri(),overview,1);
        }
    }
    @Test void rebuiltDependencyIndexInvalidatesCachedSymbolsAndIncompatibleMetadataReachesApplicationDiagnostics() throws Exception {
        var module=internalModule();Files.writeString(root.resolve("pom.xml"),"<project/>");Files.createDirectories(root.resolve("target"));
        Path index=root.resolve("target/vernac-module-index.json");
        String metadata=org.vernac.compiler.modules.ModuleDescriptor.JSON.writeValueAsString(List.of(module));Files.writeString(index,metadata);
        Path source=root.resolve("src/main/vernac/app/model.vernac");Files.createDirectories(source.getParent());Files.writeString(source,"namespace app; value Name(String);");
        var projects=new VernacProjectDiagnostics();var params=new InitializeParams();params.setRootUri(root.toUri().toString());projects.initialize(params);
        List<PublishDiagnosticsParams> messages=new ArrayList<>();projects.connect(new LanguageClient(){public void telemetryEvent(Object o){}public void publishDiagnostics(PublishDiagnosticsParams p){messages.add(p);}public void showMessage(MessageParams p){}public java.util.concurrent.CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams p){return java.util.concurrent.CompletableFuture.completedFuture(null);}public void logMessage(MessageParams p){}});
        String text="application app {activate example.support;}",uri=root.resolve(ApplicationDefinition.FILE).toUri().toString();projects.open(uri,text,1);
        assertFalse(projects.definition(uri,new Position(0,text.indexOf("support")+2)).isEmpty());
        Path replacement=root.resolve("target/new-index.json");
        Files.writeString(replacement,metadata.replace(org.vernac.compiler.modules.ModuleDescriptor.CONTRACT,"unsupported-contract"));
        Files.move(replacement,index,StandardCopyOption.REPLACE_EXISTING);
        assertTrue(projects.definition(uri,new Position(0,text.indexOf("support")+2)).isEmpty(),"No stale module navigation after an incompatible rebuild");
        assertTrue(messages.stream().filter(m->m.getUri().equals(uri)).flatMap(m->m.getDiagnostics().stream()).anyMatch(d->d.getMessage().contains("contract")),"The open application file must receive the dependency error");
        Files.writeString(replacement,metadata);Files.move(replacement,index,StandardCopyOption.REPLACE_EXISTING);
        assertFalse(projects.definition(uri,new Position(0,text.indexOf("support")+2)).isEmpty());
        assertTrue(messages.stream().filter(m->m.getUri().equals(uri)).reduce((a,b)->b).orElseThrow().getDiagnostics().isEmpty());
    }
    @Test void httpOnlyModuleIsActivatableButControllerIsNotAnExportCandidate() throws Exception {
        Path producer=root.resolve("producer/httpentry/model.vernac");Files.createDirectories(producer.getParent());
        String model="namespace httpentry; value Name(String); usecase Echo(Name) returns Name behavior {execute {return name;}} adapter Api inbound http uses Echo echo {base \"/module\"; lookup {GET \"/\";query String name;execute echo {name <- query.name;} respond 200 Response using output;} schema Response(String name);mapping output: Response <- Name {name <- source;}}";
        Files.writeString(producer,model);
        var compiler=new org.vernac.compiler.pipeline.VernacCompiler();var resolved=compiler.analyzeProject(root.resolve("producer"));
        var generated=compiler.generate(resolved,org.vernac.compiler.persistence.SchemaModel.empty(),Map.of());
        var module=org.vernac.compiler.modules.ModuleContracts.publish(
            org.vernac.compiler.modules.ModuleDefinition.parse(root.resolve("module.vmodule"),"module example.httpentry {}"),resolved,generated.generatedFiles(),"example","httpentry","1",List.of());
        Files.writeString(root.resolve("pom.xml"),"<project/>");Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("target/vernac-module-index.json"),org.vernac.compiler.modules.ModuleDescriptor.JSON.writeValueAsString(List.of(module)));
        Path local=root.resolve("src/main/vernac/app/model.vernac");Files.createDirectories(local.getParent());Files.writeString(local,"namespace app; value Name(String);");
        var projects=new VernacProjectDiagnostics();var params=new InitializeParams();params.setRootUri(root.toUri().toString());projects.initialize(params);
        String text="application app {activate example.",uri=root.resolve(ApplicationDefinition.FILE).toUri().toString();projects.open(uri,text,1);
        assertTrue(projects.complete(uri,new Position(0,text.length())).stream().anyMatch(c->c.getLabel().equals("example.httpentry")));
        var symbols=new VernacProjectSymbols(root.resolve("producer"),Map.of(producer,model));
        String exports="module example.httpentry {export httpentry.";
        assertFalse(symbols.moduleComplete(exports,new Position(0,exports.length())).stream().anyMatch(c->c.getLabel().equals("httpentry.Api")));
    }
    @Test void completesPortsAndOnlyMatchingAdaptersAndNavigates() throws Exception {
        Files.writeString(root.resolve("pom.xml"),"<project/>");
        Path source=root.resolve("src/main/vernac/example/model.vernac");Files.createDirectories(source.getParent());
        Files.writeString(source,"namespace example; port Payment {void pay();} port Other {void pay();} adapter Provider implements Payment {behavior {public void pay() {}}} adapter Wrong implements Other {behavior {public void pay() {}}}");
        var projects=new VernacProjectDiagnostics();var params=new InitializeParams();params.setRootUri(root.toUri().toString());projects.initialize(params);
        String uri=root.resolve(ApplicationDefinition.FILE).toUri().toString();
        String text="application example.shop { bind example.Payment to example.";projects.open(uri,text,1);
        var choices=projects.complete(uri,new Position(0,text.length()));
        assertTrue(choices.stream().anyMatch(c->c.getLabel().equals("example.Provider")));
        assertFalse(choices.stream().anyMatch(c->c.getLabel().equals("example.Wrong")));
        assertEquals(source.toUri().toString(),projects.definition(uri,new Position(0,text.indexOf("Payment")+2)).getFirst().getUri());
        text="application example.shop { bind example.";projects.change(uri,text,2);
        assertTrue(projects.complete(uri,new Position(0,text.length())).stream().anyMatch(c->c.getLabel().equals("example.Payment")));
    }
    @Test void emptyIsAcceptedAndUnsavedNonemptyDeclarationIsNeverSilentlyIgnored() throws Exception {
        Files.writeString(root.resolve("pom.xml"),"<project/>");
        Path source=root.resolve("src/main/vernac/example/model.vernac");Files.createDirectories(source.getParent());Files.writeString(source,"namespace example; value Name(String);");
        var projects=new VernacProjectDiagnostics();var params=new InitializeParams();params.setRootUri(root.toUri().toString());projects.initialize(params);
        List<PublishDiagnosticsParams> messages=new ArrayList<>();projects.connect(new LanguageClient(){public void telemetryEvent(Object o){}public void publishDiagnostics(PublishDiagnosticsParams p){messages.add(p);}public void showMessage(MessageParams p){}public java.util.concurrent.CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams p){return java.util.concurrent.CompletableFuture.completedFuture(null);}public void logMessage(MessageParams p){}});
        String uri=root.resolve(ApplicationDefinition.FILE).toUri().toString();projects.open(uri,"application example.shop {}",1);
        assertTrue(messages.getLast().getDiagnostics().isEmpty());
        projects.change(uri,"application example.shop { activate missing.billing; }",2);
        assertTrue(messages.stream().filter(m->m.getUri().equals(uri)).flatMap(m->m.getDiagnostics().stream()).anyMatch(d->d.getMessage().contains("Unknown module")));
        projects.change(uri,"application example.shop {}",3);
        assertTrue(messages.getLast().getDiagnostics().isEmpty());
    }
    @Test void completesOnlyModulesWithFunctionalComponents() {
        var functional=new org.vernac.compiler.modules.ModuleDescriptor(1,org.vernac.compiler.modules.ModuleDescriptor.CONTRACT,"example.billing","example","billing","1",List.of(),List.of(),List.of("example.billing.Pay"),List.of(new org.vernac.compiler.modules.ModuleDescriptor.Component("example.billing.Pay","USE_CASE","example.billing.usecase.Pay",List.of())));
        var values=new org.vernac.compiler.modules.ModuleDescriptor(1,org.vernac.compiler.modules.ModuleDescriptor.CONTRACT,"example.values","example","values","1",List.of(),List.of());
        var symbols=new VernacProjectSymbols(root,Map.of());String text="application example.shop { activate example.";
        var names=symbols.applicationComplete(text,new Position(0,text.length()),List.of(functional,values)).stream().map(CompletionItem::getLabel).toList();
        assertEquals(List.of("example.billing"),names);
    }

}
