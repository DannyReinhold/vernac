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
import java.util.List;
import org.vernac.compiler.modules.*;
import org.apache.maven.model.Resource;

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

    @Parameter(property="vernac.schemaHistory", defaultValue="${project.basedir}/src/main/vernac-schema/history")
    private File historyDirectory;
    @Parameter(property="vernac.migrations", defaultValue="${project.basedir}/src/main/resources/db/migration")
    private File migrationDirectory;
    @Parameter(property="vernac.enumCodes")
    private File enumCodes;

    private org.vernac.compiler.persistence.SchemaModel previousSchema() throws java.io.IOException {
        if (historyDirectory == null || migrationDirectory == null) return org.vernac.compiler.persistence.SchemaModel.empty();
        return new org.vernac.compiler.persistence.SchemaHistory(historyDirectory.toPath(),migrationDirectory.toPath()).read();
    }
    private java.util.Map<String,java.util.Map<String,String>> enumOverrides() throws java.io.IOException {
        return enumCodes == null ? java.util.Map.of() : org.vernac.compiler.persistence.SchemaModel.JSON.readValue(
                java.nio.file.Files.readString(enumCodes.toPath()),new com.fasterxml.jackson.core.type.TypeReference<>() { });
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Vernac compiler execution skipped.");
            return;
        }

        if (sourceDirectory.exists() && !sourceDirectory.isDirectory()) {
            throw new MojoExecutionException("Vernac source path is not a directory: " + sourceDirectory);
        }

        Path outputPath = outputDirectory.toPath();
        try {
            // Resolve the entire source tree before writing any generated Java.
            var dependencies=ModuleClasspath.read(project);
            var resolved=sourceDirectory.exists()?compiler.analyzeProject(sourceDirectory.toPath(),dependencies):null;
            VernacProjectCompilationResult result=resolved==null?new VernacProjectCompilationResult(List.of(),List.of())
                    :compiler.generate(resolved,previousSchema(),enumOverrides());
            Path base=project!=null&&project.getBasedir()!=null?project.getBasedir().toPath():ModuleArtifacts.projectDirectory(sourceDirectory.toPath());
            Path manifest=base.resolve(ModuleDefinition.FILE);
            Path resources=base.resolve("target/generated-resources/vernac-module");
            Path descriptor=resources.resolve(ModuleDescriptor.RESOURCE);
            ModuleDescriptor published=null;
            if(Files.isRegularFile(manifest)) {
                if(project==null)throw new IOException("Publishing a module requires Maven project coordinates.");
                if(resolved==null)throw new IOException("Module definition requires a Vernac source root.");
                var definition=ModuleDefinition.parse(manifest,Files.readString(manifest));
                published=ModuleContracts.publish(definition,resolved,result.generatedFiles(),project.getGroupId(),project.getArtifactId(),project.getVersion(),dependencies);
            }
            if(project!=null) {
                Files.createDirectories(base.resolve("target"));
                ModuleDescriptor.write(base.resolve("target/vernac-module-index.json"),dependencies);
                var attachments=new java.util.TreeMap<String,String>();
                for(var artifact:project.getArtifacts()) if(artifact.getFile()!=null && artifact.getFile().getName().endsWith(".jar")) {
                    Path file=artifact.getFile().toPath();Path sources=file.resolveSibling(file.getFileName().toString().replaceFirst("\\.jar$","-sources.jar"));
                    for(var dependency:dependencies)if(dependency.groupId().equals(artifact.getGroupId())&&dependency.artifactId().equals(artifact.getArtifactId()))attachments.put(dependency.module(),sources.toString());
                }
                ModuleDescriptor.write(base.resolve("target/vernac-module-sources.json"),attachments);
            }
            if(published!=null) {
                ModuleDescriptor.write(descriptor,published);
                Resource resource=new Resource();resource.setDirectory(resources.toString());project.addResource(resource);
                Path attachment=base.resolve("target/generated-module-sources/vernac");
                if(Files.exists(attachment))try(var old=Files.walk(attachment)) {for(var item:old.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(item);}
                Files.createDirectories(attachment);
                try(var files=Files.walk(sourceDirectory.toPath())) {
                    for(Path file:files.filter(f->f.toString().endsWith(".vernac")&&Files.isRegularFile(f)).toList()) {
                        Path target=attachment.resolve(sourceDirectory.toPath().relativize(file));Files.createDirectories(target.getParent());Files.copy(file,target,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                Files.copy(manifest,attachment.resolve(ModuleDefinition.FILE),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                project.addCompileSourceRoot(base.resolve("target/generated-module-sources").toString());
                getLog().info("Published Vernac type-module contract: "+published.module()+" ("+published.exports().size()+" exports)");
            } else {
                Files.deleteIfExists(descriptor);
                if(project!=null && project.getBuild().getOutputDirectory()!=null)Files.deleteIfExists(Path.of(project.getBuild().getOutputDirectory()).resolve(ModuleDescriptor.RESOURCE));
            }
            result.diagnostics().forEach(diagnostic -> getLog().warn(diagnostic.toString()));
            Files.createDirectories(outputPath);
            result.writeTo(outputPath);
            getLog().info("Successfully generated " + result.generatedFiles().size() + " Java source file(s).");
        } catch (IllegalArgumentException e) {
            throw new MojoFailureException("Invalid Vernac module contract: "+e.getMessage(),e);
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