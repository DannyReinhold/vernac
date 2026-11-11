// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.persistence;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.analyzer.SemanticValidationException;
import static org.junit.jupiter.api.Assertions.*;

class TenantMappingTest {
    @TempDir Path directory;
    private Path source() throws Exception {
        var root=directory.resolve("src/main/vernac");Files.createDirectories(root.resolve("tenantdemo"));
        Files.writeString(directory.resolve("pom.xml"),"<project/>");
        Files.writeString(root.resolve("tenantdemo/model.vernac"),
                Files.readString(Path.of("../vernac-maven-plugin/src/test/resources/persistence/tenants/model.vernac")));
        return root;
    }
    @Test void completeOwnedGraphUsesTenantKeysAndLegacyGlobalGraphStaysUnscoped() throws Exception {
        var root=source();
        Files.writeString(directory.resolve("vernac-application.vapplication"),"application tenantdemo.app {tenancy explicit;}");
        var resolved=new VernacCompiler().analyzeProject(root);
        var schema=new SchemaBuilder(resolved,SchemaModel.empty()).build();
        assertEquals(2,schema.formatVersion());
        var invoice=schema.tables().stream().filter(t->t.name().equals("Invoice")).findFirst().orElseThrow();
        assertTrue(invoice.tenantIsolation().root());
        assertEquals(List.of("id"),invoice.primaryKey());
        var owned=schema.tables().stream().filter(t->t.name().startsWith("Invoice.")).toList();
        assertTrue(owned.size()>=6);
        assertTrue(owned.stream().allMatch(t->t.tenantIsolation()!=null && t.primaryKey().getFirst().equals("@tenantId")));
        assertTrue(schema.tables().stream().filter(t->t.name().equals("Catalog")).allMatch(t->t.tenantIsolation()==null));
        String java=new VernacCompiler().compileProject(root).generatedFiles().toString();
        assertTrue(java.contains("newTenantAggregate()"));
        assertTrue(java.contains("detachedTenant(version)"));
        assertTrue(java.contains("__vernacRestore"));
        assertTrue(java.contains("__vernacTenantOwnership"));
    }
    @Test void persistentTenantModulesPublishVersionedOwnedGraphs() throws Exception {
        var root = source();
        var module = org.vernac.compiler.modules.ModuleDefinition.parse(directory.resolve("other.vmodule"), "module example.billing {}");
        Files.writeString(directory.resolve("vernac-module.vmodule"), "module example.billing {}");
        var compiler = new VernacCompiler(); var resolved = compiler.analyzeProject(root);
        var generated = compiler.generate(resolved, SchemaModel.empty(), Map.of());
        var published = org.vernac.compiler.modules.ModuleContracts.publish(module, resolved,
                generated.generatedFiles(), "example", "billing", "1", List.of());
        assertTrue(published.persistent());
        var model = new SchemaBuilder(resolved, SchemaModel.empty()).build();
        assertEquals(2, model.formatVersion());
        var runtime = SchemaModel.JSON.readValue(model.json(), org.vernac.runtime.persistence.SchemaDescription.class);
        assertEquals(model.tables().size(), runtime.tables().size());
        assertEquals(model.tables().stream().filter(t -> t.tenantIsolation() != null).count(),
                runtime.tables().stream().filter(t -> t.tenantIsolation() != null).count());
    }
    @Test void sourceClassificationRemovalReachesSpecificMigrationGuard() throws Exception {
        var root=source();var compiler=new VernacCompiler();
        var before=new SchemaBuilder(compiler.analyzeProject(root),SchemaModel.empty()).build();
        Path model=root.resolve("tenantdemo/model.vernac");
        Files.writeString(model,Files.readString(model).replace("tenant aggregate Invoice","global aggregate Invoice"));
        var after=new SchemaBuilder(compiler.analyzeProject(root),before).build();
        var error=assertThrows(IllegalArgumentException.class,()->new MigrationPlanner().plan(before,after,MigrationPlanner.Options.safe()));
        assertTrue(error.getMessage().contains("TENANT ISOLATION REMOVAL"));
    }
}
