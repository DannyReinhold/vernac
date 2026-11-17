// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.persistence.*;
import org.vernac.compiler.pipeline.VernacCompiler;
class VernacSaasTemplateTest {
    @TempDir Path temporary;
    @Test void independentConsumerUsesItsOwnNamespaceAndConsistentTenantSchema()throws Exception {
        Path root=temporary.resolve("saas");String namespace="de.rsas.kunden.workspace";
        VernacProjectTemplate.copyTo(root,"My SaaS","de.rsas","my-saas",namespace,VernacProjectTemplate.ProjectKind.SAAS_APPLICATION);
        var compiler=new VernacCompiler();var resolved=compiler.analyzeProject(root.resolve("src/main/vernac"));
        var history=new SchemaHistory(root.resolve("src/main/vernac-schema/history"),root.resolve("src/main/resources/db/migration"));
        var schema=history.read();
        assertFalse(new MigrationPlanner().plan(schema,new SchemaBuilder(resolved,schema,Map.of()).build(),MigrationPlanner.Options.safe()).changed());
        assertTrue(schema.tables().stream().anyMatch(t->t.namespace().equals(namespace)&&t.name().equals("Document")));
        String sql=Files.readString(root.resolve("src/main/resources/db/migration/V20261116010000000__initial_documents.sql"));
        assertTrue(sql.contains("FORCE ROW LEVEL SECURITY"));assertFalse(sql.contains("example.documentworkspace"));
        String bindings=Files.readString(root.resolve("vernac-application.vapplication"));
        assertTrue(bindings.contains("activate vernac.saas.payments.jdbc"));assertTrue(bindings.contains(namespace+".StoredDocuments"));
        String pom=Files.readString(root.resolve("pom.xml"));assertTrue(pom.contains("<artifactId>my-saas</artifactId>"));
        assertTrue(pom.contains("<vernac.version>"+System.getProperty("vernac.expected.version")+"</vernac.version>"));
        String launcher=Files.readString(root.resolve("run-local.py"));assertTrue(launcher.contains(namespace));assertFalse(launcher.contains("example.documentworkspace"));
        assertTrue(Files.isRegularFile(root.resolve(".mvn/wrapper/maven-wrapper.properties")));
        assertTrue(Files.isRegularFile(root.resolve("src/main/java/de/rsas/kunden/workspace/BillingSetup.java")));
        assertTrue(Files.readString(root.resolve(".gitignore")).contains(".local/"));
        for (String path : new String[]{"acceptance_runtime.py", "upgrade_snapshot.py", "test_acceptance_gate.py",
                "browser/saas.spec.mjs", "browser/fixtures.mjs", "browser/reporter.cjs", "browser/playwright.config.mjs"})
            assertTrue(Files.isRegularFile(root.resolve(path)), path);
        String fixture = Files.readString(root.resolve("src/test/java/acceptance/support/AcceptanceApplication.java"));
        assertTrue(fixture.contains("import " + namespace + ".DemoApplication;"));
        assertFalse(Files.exists(root.resolve("src/main/java/acceptance")));
        assertTrue(Files.readString(root.resolve("acceptance_runtime.py")).contains(namespace));
    }
    @Test void rolesBelongToTheGeneratedModelAndCanBeChangedIndependently() throws Exception {
        Path root=temporary.resolve("roles");
        VernacProjectTemplate.copyTo(root,"Roles","de.rsas","roles","de.rsas.roles",VernacProjectTemplate.ProjectKind.SAAS_APPLICATION);
        Path model=root.resolve("src/main/vernac/de/rsas/roles/model.vernac");
        Files.writeString(model,Files.readString(model).replace("example.workspace_reader","product.auditor").replace("example.workspace_editor","product.author"));
        var resolved=new VernacCompiler().analyzeProject(root.resolve("src/main/vernac"));
        String catalog=org.vernac.compiler.modules.ModuleDescriptor.JSON.writeValueAsString(new org.vernac.compiler.pipeline.RoleDeclarations().catalog(resolved));
        assertTrue(catalog.contains("product.auditor"));assertTrue(catalog.contains("de.rsas.roles.WorkspacePermissions.VIEW"));
        assertFalse(catalog.contains("workspace_reader"));assertFalse(Files.exists(root.resolve("src/main/resources/roles.json")));
    }
    @Test void detectsCollisionsBeforeWritingAnything()throws Exception {
        Path root=temporary.resolve("collision");Files.createDirectories(root);Files.writeString(root.resolve("compose.yaml"),"owned");
        assertThrows(java.io.IOException.class,()->VernacProjectTemplate.copyTo(root,"SaaS","de.rsas","saas","de.rsas.saas",VernacProjectTemplate.ProjectKind.SAAS_APPLICATION));
        assertEquals("owned",Files.readString(root.resolve("compose.yaml")));assertFalse(Files.exists(root.resolve("pom.xml")));
    }
}
