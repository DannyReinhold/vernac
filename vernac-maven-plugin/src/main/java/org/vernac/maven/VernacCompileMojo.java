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
import org.vernac.compiler.pipeline.VernacCompilationResult;
import org.vernac.compiler.pipeline.VernacCompiler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Kompiliert Vernac DSL Dateien (.vernac) in Java Source Code während der generate-sources Phase.
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
     * Quellverzeichnis für .vernac Dateien.
     */
    @Parameter(property = "vernac.sourceDirectory", defaultValue = "${project.basedir}/src/main/vernac")
    private File sourceDirectory;
    /**
     * Zielverzeichnis für den generierten Java-Code.
     */
    @Parameter(property = "vernac.outputDirectory", defaultValue = "${project.build.directory}/generated-sources/vernac")
    private File outputDirectory;
    /**
     * Flag zum Überspringen der Ausführung.
     */
    @Parameter(property = "vernac.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Vernac compiler execution skipped.");
            return;
        }

        if (!sourceDirectory.exists() || !sourceDirectory.isDirectory()) {
            getLog().info("No Vernac source directory found at " + sourceDirectory.getAbsolutePath() + " - skipping.");
            return;
        }

        Path sourcePath = sourceDirectory.toPath();
        Path outputPath = outputDirectory.toPath();

        List<Path> vernacFiles;
        try (Stream<Path> stream = Files.walk(sourcePath)) {
            vernacFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".vernac"))
                    .toList();
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to scan Vernac source directory: " + sourceDirectory, e);
        }

        if (vernacFiles.isEmpty()) {
            getLog().info("No .vernac files found in " + sourceDirectory.getAbsolutePath());
            return;
        }

        getLog().info("Compiling " + vernacFiles.size() + " Vernac DSL file(s) to " + outputDirectory.getAbsolutePath());

        try {
            Files.createDirectories(outputPath);
        } catch (IOException e) {
            throw new MojoExecutionException("Could not create output directory: " + outputDirectory, e);
        }

        int totalGeneratedFiles = 0;

        for (Path file : vernacFiles) {
            getLog().debug("Compiling Vernac file: " + file);
            try {
                VernacCompilationResult result = compiler.compile(file);
                result.writeTo(outputPath);
                totalGeneratedFiles += result.generatedFiles().size();
            } catch (Exception e) {
                throw new MojoFailureException("Failed to compile Vernac file: " + file + " (" + e.getMessage() + ")", e);
            }
        }

        getLog().info("Successfully generated " + totalGeneratedFiles + " Java source file(s).");

        // Registriere das Verzeichnis bei Maven, damit der Java-Compiler die Klassen findet
        if (project != null) {
            project.addCompileSourceRoot(outputDirectory.getAbsolutePath());
            getLog().debug("Added compile source root: " + outputDirectory.getAbsolutePath());
        }
    }

    // Für Unit-Tests & direkte Instanziierung
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