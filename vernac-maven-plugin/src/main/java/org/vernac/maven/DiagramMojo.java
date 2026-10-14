// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.vernac.compiler.diagram.DomainDiagrams;
import org.vernac.compiler.pipeline.VernacCompiler;

import java.io.File;

/** Exports a declaration graph without generating Java, migrating or executing application code. */
@Mojo(name = "diagram", threadSafe = true, requiresDependencyResolution = ResolutionScope.COMPILE)
public class DiagramMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true)
    protected org.apache.maven.project.MavenProject project;

    @Parameter(
            property = "vernac.sourceDirectory",
            defaultValue = "${project.basedir}/src/main/vernac")
    protected File sourceDirectory;

    @Parameter(
            property = "vernac.diagramOutput",
            defaultValue = "${project.build.directory}/vernac-diagrams")
    protected File outputDirectory;

    @Parameter(property = "vernac.diagramNamespace")
    protected String namespace;

    @Override
    public void execute() throws MojoFailureException {
        try {
            var resolved =
                    new VernacCompiler()
                            .analyzeProject(
                                    sourceDirectory.toPath(), ModuleClasspath.read(project));
            if (namespace == null || namespace.isBlank())
                DomainDiagrams.writeAll(resolved, outputDirectory.toPath());
            else DomainDiagrams.write(resolved, namespace, outputDirectory.toPath());
            getLog().info(
                            "Generated Vernac domain diagrams in "
                                    + outputDirectory
                                    + ". Open Markdown Preview for the Mermaid view; .puml is also"
                                    + " available.");
        } catch (Exception e) {
            throw new MojoFailureException(
                    "Domain diagram generation failed: " + e.getMessage(), e);
        }
    }
}
