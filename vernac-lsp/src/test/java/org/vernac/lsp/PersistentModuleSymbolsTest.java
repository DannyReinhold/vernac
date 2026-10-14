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
class PersistentModuleSymbolsTest {
    @TempDir Path root;
    @Test void importedRepositoryNavigatesToPublicContractAndPersistentActivationIsSuggested() throws Exception {
        Path producer=root.resolve("producer/billing/model.vernac");Files.createDirectories(producer.getParent());
        Files.writeString(producer,"namespace billing; id InvoiceId; value Reference(String); aggregate Invoice[InvoiceId](Reference) list Invoices; repository InvoiceRepository for Invoice {find Invoices all();}");
        var compiler=new VernacCompiler();var resolved=compiler.analyzeProject(root.resolve("producer"),List.of());var generated=compiler.generate(resolved,SchemaModel.empty(),Map.of());
        var module=ModuleContracts.publish(ModuleDefinition.parse(root.resolve("manifest.vmodule"),"module example.billing {export billing.InvoiceId;export billing.Reference;export billing.Invoice;export billing.Invoices;export billing.InvoiceRepository;}"),resolved,generated.generatedFiles(),"example","billing","1",List.of());
        Files.writeString(root.resolve("pom.xml"),"<project/>");Files.createDirectories(root.resolve("target"));Files.writeString(root.resolve("target/vernac-module-index.json"),ModuleDescriptor.JSON.writeValueAsString(List.of(module)));
        Path source=root.resolve("src/main/vernac/app/model.vernac");Files.createDirectories(source.getParent());String text="namespace app; import billing.InvoiceRepository; usecase Run() uses InvoiceRepository invoiceRepository behavior {execute {}}";Files.writeString(source,text);
        var projects=new VernacProjectDiagnostics();var params=new InitializeParams();params.setRootUri(root.toUri().toString());projects.initialize(params);projects.open(source.toUri().toString(),text,1);
        var position=new Position(0,text.lastIndexOf("InvoiceRepository")+3);var target=projects.definition(source.toUri().toString(),position).getFirst();
        String view=Files.readString(Path.of(java.net.URI.create(target.getUri())));assertTrue(view.contains("repository InvoiceRepository"));assertTrue(view.contains("Public query signatures only"));
        assertTrue(projects.complete(source.toUri().toString(),position).stream().anyMatch(c->c.getLabel().equals("InvoiceRepository")));
        String application="application app {activate example.";String uri=root.resolve("vernac-application.vapplication").toUri().toString();projects.open(uri,application,1);
        assertTrue(projects.complete(uri,new Position(0,application.length())).stream().anyMatch(c->c.getLabel().equals("example.billing")));
    }
}
