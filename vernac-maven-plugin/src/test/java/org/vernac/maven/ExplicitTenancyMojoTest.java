// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import java.nio.file.*;
import org.apache.maven.project.MavenProject;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ExplicitTenancyMojoTest {
    @TempDir Path root;
    private MavenProject source(boolean global) throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Path file=root.resolve("src/main/vernac/example/model.vernac"); Files.createDirectories(file.getParent());
        Files.writeString(file, "namespace example; id InvoiceId; " + (global?"global ":"")
                + "aggregate Invoice[InvoiceId](); repository InvoiceRepository for Invoice {}");
        Files.writeString(root.resolve("vernac-application.vapplication"), "application example.app { tenancy explicit; }");
        var project=new MavenProject(); project.setFile(root.resolve("pom.xml").toFile()); return project;
    }
    private <T extends SchemaMojo> T configure(T mojo, MavenProject project) {
        mojo.project=project;
        mojo.sourceDirectory=root.resolve("src/main/vernac").toFile();
        mojo.historyDirectory=root.resolve("src/main/vernac-schema/history").toFile();
        mojo.migrationDirectory=root.resolve("src/main/resources/db/migration").toFile();
        mojo.schemaOutput=root.resolve("target/schema").toFile();
        return mojo;
    }
    private VernacCompileMojo compile(MavenProject project) {
        var mojo=new VernacCompileMojo();mojo.setProject(project);
        mojo.setSourceDirectory(root.resolve("src/main/vernac").toFile());
        mojo.setOutputDirectory(root.resolve("target/generated-sources/vernac").toFile());return mojo;
    }
    @Test void compileSchemaAndForcedMigrationRejectMissingClassificationBeforeWriting() throws Exception {
        var project=source(false);
        assertTrue(assertThrows(MojoFailureException.class,()->compile(project).execute()).getMessage().contains("tenancy explicit"));
        assertTrue(assertThrows(MojoFailureException.class,()->configure(new SchemaMojo(),project).execute()).getMessage().contains("tenancy explicit"));
        var migration=configure(new MigrationMojo(),project);
        var force=MigrationMojo.class.getDeclaredField("force");force.setAccessible(true);force.set(migration,true);
        assertTrue(assertThrows(MojoFailureException.class,migration::execute).getMessage().contains("tenancy explicit"));
        assertFalse(Files.exists(root.resolve("src/main/vernac-schema/history")));
        assertFalse(Files.exists(root.resolve("src/main/resources/db/migration")));
        assertFalse(Files.exists(root.resolve("target/generated-sources/vernac")));
    }
    @Test void explicitGlobalBuildAndSchemaRemainUsableInStrictMode() throws Exception {
        var project=source(true);
        compile(project).execute();
        configure(new SchemaMojo(),project).execute();
        assertTrue(Files.isRegularFile(root.resolve("target/generated-sources/vernac/example/domain/Invoice.java")));
        String schema=Files.readString(root.resolve("target/schema/schema.json"));
        assertFalse(schema.contains("tenantIsolation"));
        assertFalse(schema.contains("@tenantId"));
    }
}
