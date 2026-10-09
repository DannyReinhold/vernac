// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.vernac.compiler.persistence.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** Creates a new, reviewable Flyway candidate and its source-controlled model snapshot. */
@Mojo(name = "migration", threadSafe = false)
public final class MigrationMojo extends SchemaMojo {
    @Parameter(property="vernac.migrationDescription", required=true)
    private String description;
    @Parameter(property="vernac.migrationVersion")
    private String version;
    @Parameter(property="vernac.force", defaultValue="false")
    private boolean force;
    /** Trusted SQL expressions in a reviewed source-controlled JSON file. */
    @Parameter(property="vernac.transformations")
    private java.io.File transformations;
    public record Transformations(Map<String,String> backfills, Map<String,String> conversions) { }
    @Override public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            var history = history();
            var previous = history.read();
            var next = model(previous);
            var expressions = transformations == null ? new Transformations(Map.of(), Map.of())
                    : SchemaModel.JSON.readValue(java.nio.file.Files.readString(transformations.toPath()), Transformations.class);
            var plan = new MigrationPlanner().plan(previous, next, new MigrationPlanner.Options(force,
                    expressions.backfills() == null ? Map.of() : expressions.backfills(),
                    expressions.conversions() == null ? Map.of() : expressions.conversions()));
            if (!plan.changed()) { getLog().info("No schema changes. No migration generated."); return; }
            String v = version == null ? DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC).format(Instant.now()) : version;
            var file = history.append(v, description == null ? "" : description, previous, next, plan);
            getLog().warn("Generated migration CANDIDATE: " + file);
            getLog().warn("You alone are responsible for review, testing and approval. No SQL has been applied. Run schema-check against a disposable PostgreSQL server before committing.");
            plan.risks().forEach(r -> getLog().warn("FORCE: " + r));
        } catch (Exception e) { throw new MojoFailureException("Migration generation failed: " + e.getMessage(), e); }
    }
}
