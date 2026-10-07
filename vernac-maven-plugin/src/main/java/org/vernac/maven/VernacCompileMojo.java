// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.vernac.compiler.pipeline.VernacProjectCompilationResult;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.pipeline.VernacCompiler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Compiles Vernac DSL files (.vernac) into Java source code during the generate-sources phase.
 */
@Mojo(
        name = "compile",
        defaultPhase = LifecyclePhase.GENERATE_SOURCES,
        requiresDependencyResolution = ResolutionScope.COMPILE,
        threadSafe = true
)
public class VernacCompileMojo extends AbstractMojo {

    private final VernacCompiler compiler = new VernacCompiler();
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;
    /**
     * Source directory for .vernac files.
     */
    @Parameter(property = "vernac.sourceDirectory", defaultValue = "${project.basedir}/src/main/vernac")
    private File sourceDirectory;
    /**
     * Target directory for generated Java code.
     */
    @Parameter(property = "vernac.outputDirectory", defaultValue = "${project.build.directory}/generated-sources/vernac")
    private File outputDirectory;
    /**
     * Flag to skip execution.
     */
    @Parameter(property = "vernac.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Vernac compiler execution skipped.");
            return;
        }

        if (!sourceDirectory.exists()) {
            getLog().info("No Vernac source directory found at " + sourceDirectory.getAbsolutePath() + " - skipping.");
            return;
        }
        if (!sourceDirectory.isDirectory()) {
            throw new MojoExecutionException("Vernac source path is not a directory: " + sourceDirectory);
        }

        Path outputPath = outputDirectory.toPath();
        try {
            // Resolve the entire source tree before writing any generated Java.
            VernacProjectCompilationResult result = compiler.compileProject(sourceDirectory.toPath());
            result.diagnostics().forEach(diagnostic -> getLog().warn(diagnostic.toString()));
            if (result.generatedFiles().isEmpty()) {
                getLog().info("No Java sources generated from " + sourceDirectory.getAbsolutePath());
                return;
            }
            Files.createDirectories(outputPath);
            result.writeTo(outputPath);
            getLog().info("Successfully generated " + result.generatedFiles().size() + " Java source file(s).");
        } catch (SemanticValidationException e) {
            throw new MojoFailureException("Failed to compile Vernac project: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoExecutionException("Could not read or write Vernac project files: " + e.getMessage(), e);
        }

        // Register the directory with Maven so that the Java compiler finds the classes
        if (project != null) {
            project.addCompileSourceRoot(outputDirectory.getAbsolutePath());
            getLog().debug("Added compile source root: " + outputDirectory.getAbsolutePath());
        }
    }

    // For unit tests and direct instantiation
    public void setSourceDirectory(File sourceDirectory) {
        this.sourceDirectory = sourceDirectory;
    }

    public void setOutputDirectory(File outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    public void setProject(MavenProject project) {
        this.project = project;
    }
}