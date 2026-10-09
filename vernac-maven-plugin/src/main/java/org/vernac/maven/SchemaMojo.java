// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.vernac.compiler.persistence.*;
import org.vernac.compiler.pipeline.VernacCompiler;
import java.io.File;
import java.nio.file.Files;

/** Generates the expected PostgreSQL model and initial-DDL candidate, without changing source history. */
@Mojo(name = "schema", threadSafe = true)
public class SchemaMojo extends AbstractMojo {
    @Parameter(property="vernac.sourceDirectory", defaultValue="${project.basedir}/src/main/vernac")
    protected File sourceDirectory;
    @Parameter(property="vernac.schemaHistory", defaultValue="${project.basedir}/src/main/vernac-schema/history")
    protected File historyDirectory;
    @Parameter(property="vernac.migrations", defaultValue="${project.basedir}/src/main/resources/db/migration")
    protected File migrationDirectory;
    @Parameter(property="vernac.schemaOutput", defaultValue="${project.build.directory}/vernac-schema")
    protected File schemaOutput;
    /** Optional complete enum-code overrides, keyed by fully qualified Vernac enum identity. */
    @Parameter(property="vernac.enumCodes")
    protected File enumCodes;
    protected SchemaHistory history() { return new SchemaHistory(historyDirectory.toPath(), migrationDirectory.toPath()); }
    protected SchemaModel model(SchemaModel previous) throws Exception {
        if (!sourceDirectory.isDirectory()) throw new IllegalArgumentException("Vernac source root does not exist: " + sourceDirectory);
        java.util.Map<String, java.util.Map<String, String>> overrides = enumCodes == null ? java.util.Map.of()
                : SchemaModel.JSON.readValue(Files.readString(enumCodes.toPath()), new com.fasterxml.jackson.core.type.TypeReference<>() { });
        return new SchemaBuilder(new VernacCompiler().analyzeProject(sourceDirectory.toPath()), previous, overrides).build();
    }
    @Override public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            SchemaModel model = model(history().read());
            Files.createDirectories(schemaOutput.toPath());
            Files.writeString(schemaOutput.toPath().resolve("schema.json"), model.json());
            Files.writeString(schemaOutput.toPath().resolve("schema.sql"), new MigrationPlanner().plan(SchemaModel.empty(), model, MigrationPlanner.Options.safe()).sql());
            getLog().info("Generated expected schema in " + schemaOutput + ". No database or source history changed.");
            getLog().warn("Generated SQL is a candidate. You alone are responsible for review, testing and approval before application.");
        } catch (Exception e) { throw new MojoFailureException("Schema generation failed: " + e.getMessage(), e); }
    }
}
