// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.modules.*;
import org.vernac.compiler.persistence.*;
import org.vernac.compiler.pipeline.VernacCompiler;
import static org.junit.jupiter.api.Assertions.*;
class ModulePersistenceMojoTest {
    @TempDir Path root;
    private ModulePersistenceMojo producer() throws Exception {
        Files.writeString(root.resolve("pom.xml"),"<project/>");
        Files.writeString(root.resolve(ModuleDefinition.FILE),"module example.billing {export example.billing.InvoiceId;}");
        Path sources=root.resolve("src/main/vernac"),model=sources.resolve("example/billing/model.vernac");Files.createDirectories(model.getParent());
        Files.writeString(model,"namespace example.billing; id InvoiceId; value Reference(String); aggregate Invoice[InvoiceId](Reference reference); repository InvoiceRepository for Invoice {}");
        var project=new MavenProject();project.setFile(root.resolve("pom.xml").toFile());
        var goal=new ModulePersistenceMojo();goal.project=project;goal.sourceDirectory=sources.toFile();goal.historyDirectory=root.resolve("src/main/vernac-schema/history").toFile();goal.outputDirectory=root.resolve("target/generated-resources/vernac-module-persistence").toFile();
        var schema=new SchemaBuilder(new VernacCompiler().analyzeProject(sources),SchemaModel.empty()).build();
        new SchemaHistory(goal.historyDirectory.toPath(),goal.migrations().toPath()).append("20261013090000000","initial_billing",SchemaModel.empty(),schema,new MigrationPlanner().plan(SchemaModel.empty(),schema,MigrationPlanner.Options.safe()));
        return goal;
    }
    @Test void packagesActualJarWithIsolatedSqlAndVerifiableReviewedHistory() throws Exception {
        var goal=producer();goal.execute();
        assertEquals(root.resolve("src/main/vernac-schema/migration").toFile(),goal.migrations());
        assertFalse(Files.exists(root.resolve("src/main/resources/db/migration")));
        assertTrue(goal.project.getResources().stream().anyMatch(r->r.getDirectory().equals(goal.outputDirectory.toString())));
        Path jar=root.resolve("billing.jar");
        try(var out=new JarOutputStream(Files.newOutputStream(jar));var paths=Files.walk(goal.outputDirectory.toPath())) {
            for(Path file:paths.filter(Files::isRegularFile).toList()){out.putNextEntry(new JarEntry(goal.outputDirectory.toPath().relativize(file).toString().replace('\\','/')));Files.copy(file,out);out.closeEntry();}
        }
        Map<String,byte[]> bytes=new TreeMap<>();
        try(var artifact=new JarFile(jar.toFile())) {
            for(var entry:artifact.stream().toList())try(var in=artifact.getInputStream(entry)){bytes.put(entry.getName(),in.readAllBytes());}
        }
        var descriptor=ModuleDescriptor.JSON.readValue(bytes.get(ModulePersistence.resource("example.billing")),ModulePersistence.Descriptor.class);
        ModulePersistence.verify(descriptor,bytes);
        assertFalse(ModuleDescriptor.JSON.writeValueAsString(descriptor).contains(root.toString()));
        assertTrue(bytes.keySet().stream().allMatch(s->s.startsWith("META-INF/vernac/modules/example.billing/")));
        assertTrue(descriptor.expectedSchema().tables().stream().anyMatch(t->t.name().equals("Invoice")));
        // Rebuilding removes only previously tracked generated files, not unrelated resources.
        Path sentinel=goal.outputDirectory.toPath().resolve("unrelated.txt");Files.writeString(sentinel,"keep");goal.execute();assertEquals("keep",Files.readString(sentinel));
    }
    @Test void failedPreparationPreservesPreviousBundleAndRejectsResourceLeakage() throws Exception {
        var goal=producer();goal.execute();
        Path manifest=goal.outputDirectory.toPath().resolve(ModulePersistence.resource("example.billing"));byte[] before=Files.readAllBytes(manifest);
        Files.writeString(goal.migrations().toPath().resolve("V20261013100000000__unpaired.sql"),"select 1;");
        assertThrows(org.apache.maven.plugin.MojoFailureException.class,goal::execute);assertArrayEquals(before,Files.readAllBytes(manifest));
        goal.migrationDirectory=root.resolve("src/main/resources/db/migration").toFile();
        assertTrue(assertThrows(org.apache.maven.plugin.MojoFailureException.class,goal::execute).getMessage().contains("outside ordinary Maven resources"));
    }
    @Test void applicationDefaultAndExplicitOverridesRemainAvailable() throws Exception {
        assertEquals(root.resolve("src/main/resources/db/migration").toFile(),MigrationLocations.resolve(root,null));
        Files.writeString(root.resolve(ModuleDefinition.FILE),"module example.library {}");
        assertEquals(root.resolve("src/main/vernac-schema/migration").toFile(),MigrationLocations.resolve(root,null));
        var configured=root.resolve("reviewed-sql").toFile();assertEquals(configured,MigrationLocations.resolve(root,configured));
    }
}
