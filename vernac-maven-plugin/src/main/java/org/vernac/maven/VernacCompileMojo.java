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
    @Parameter(property="vernac.migrations")
    private File migrationDirectory;
    @Parameter(property="vernac.enumCodes")
    private File enumCodes;

    private org.vernac.compiler.persistence.SchemaModel previousSchema() throws java.io.IOException {
        Path base=project!=null&&project.getBasedir()!=null?project.getBasedir().toPath():ModuleArtifacts.projectDirectory(sourceDirectory.toPath());
        return new org.vernac.compiler.persistence.SchemaHistory(historyDirectory==null?base.resolve("src/main/vernac-schema/history"):historyDirectory.toPath(),MigrationLocations.resolve(base,migrationDirectory).toPath()).read();
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
            Path base=project!=null&&project.getBasedir()!=null?project.getBasedir().toPath():ModuleArtifacts.projectDirectory(sourceDirectory.toPath());
            Path manifest=base.resolve(ModuleDefinition.FILE);
            org.vernac.compiler.application.ApplicationDefinition applicationDefinition=null;
            Path application=base.resolve(org.vernac.compiler.application.ApplicationDefinition.FILE);
            if(Files.isRegularFile(application)) {
                var definition=org.vernac.compiler.application.ApplicationDefinition.parse(application,Files.readString(application));
                applicationDefinition=definition;
                if(resolved!=null&&!Files.isRegularFile(manifest)) {
                    var issues=definition.diagnostics(resolved,dependencies);
                    if(!issues.isEmpty())throw new org.vernac.compiler.analyzer.SemanticValidationException(issues);
                }
                definition.requireBuildSupport();
            }
            if(resolved!=null && applicationDefinition==null && !Files.isRegularFile(manifest)) {
                var empty=new org.vernac.compiler.application.ApplicationDefinition("internal",List.of(),List.of());
                var issues=empty.diagnostics(resolved,dependencies);
                if(!issues.isEmpty())throw new org.vernac.compiler.analyzer.SemanticValidationException(issues);
            }
            var schemaOptions=resolved==null?new org.vernac.compiler.persistence.SchemaBuilder.Options(false,false):ModuleSchemaOptions.read(base,resolved,dependencies);
            VernacProjectCompilationResult result=resolved==null?new VernacProjectCompilationResult(List.of(),List.of())
                    :compiler.generate(resolved,previousSchema(),enumOverrides(),schemaOptions);
            Path resources=base.resolve("target/generated-resources/vernac-module");
            Path descriptor=resources.resolve(ModuleDescriptor.RESOURCE);
            if(Files.isRegularFile(manifest)&&applicationDefinition!=null&&(!applicationDefinition.bindings().isEmpty()||!applicationDefinition.activations().isEmpty()))
                throw new IOException("Published modules cannot contain application bindings or activations. Configure providers in the consuming application's vernac-application.vapplication.");
            ModuleDescriptor published=null;
            org.vernac.compiler.application.ApplicationBindings.Generated composition=null;
            if(resolved!=null) {
                composition=org.vernac.compiler.application.ApplicationBindings.generate(
                        applicationDefinition==null?new org.vernac.compiler.application.ApplicationDefinition("internal",List.of(),List.of()):applicationDefinition,
                        resolved,dependencies,result.generatedFiles(),Files.isRegularFile(manifest));
                result=new VernacProjectCompilationResult(composition.files(),result.diagnostics(),result.debugMap());
            }
            Path compositionResources=base.resolve("target/generated-resources/vernac-application");
            Path autoImports=compositionResources.resolve(org.vernac.compiler.application.ApplicationBindings.IMPORTS);
            if(Files.isRegularFile(manifest)) {
                if(project==null)throw new IOException("Publishing a module requires Maven project coordinates.");
                if(resolved==null)throw new IOException("Module definition requires a Vernac source root.");
                var definition=ModuleDefinition.parse(manifest,Files.readString(manifest));
                published=ModuleContracts.publish(definition,resolved,result.generatedFiles(),project.getGroupId(),project.getArtifactId(),project.getVersion(),dependencies);
                Path sourceHistory=historyDirectory==null?base.resolve("src/main/vernac-schema/history"):historyDirectory.toPath();
                boolean recordedHistory=false;
                if(Files.isDirectory(sourceHistory))try(var files=Files.list(sourceHistory)){recordedHistory=files.anyMatch(f->f.getFileName().toString().endsWith(".json"));}
                if(published.persistent()||recordedHistory) {
                    Path sql=MigrationLocations.resolve(base,migrationDirectory).toPath().toAbsolutePath().normalize();
                    ModulePersistenceResources.validateSource(project,sql);
                    var expected=new org.vernac.compiler.persistence.SchemaBuilder(resolved,previousSchema(),enumOverrides(),schemaOptions).build();
                    var bundle=ModulePersistence.prepare(published.module(),expected,sourceHistory,sql);
                    ModulePersistence.verify(bundle.descriptor(),bundle.resources());
                    ModulePersistenceResources.write(project,base.resolve("target/generated-resources/vernac-module-persistence"),bundle);
                    if(!published.persistent())published=new ModuleDescriptor(published.formatVersion(),published.contractVersion(),published.module(),published.groupId(),published.artifactId(),published.version(),published.exports(),published.externalContracts(),published.internalTypes(),published.components(),true);
                }

            }
            if(composition!=null&&composition.configuration().isPresent()) {
                if(project==null)throw new IOException("Application bindings require Maven project context.");
                if(Files.isRegularFile(base.resolve("src/main/resources").resolve(org.vernac.compiler.application.ApplicationBindings.IMPORTS)))
                    throw new IOException("Application binding auto-configuration conflicts with an existing source AutoConfiguration.imports resource. Keep application composition metadata owned by Vernac.");
                Files.createDirectories(autoImports.getParent());Files.writeString(autoImports,composition.configuration().get()+"\n");
                if(dependencies.stream().anyMatch(ModuleDescriptor::persistent)) {
                    var schema=new org.vernac.compiler.persistence.SchemaBuilder(resolved,previousSchema(),enumOverrides(),schemaOptions).build();
                    Path schemaResource=compositionResources.resolve(org.vernac.compiler.application.ApplicationBindings.schemaResource(applicationDefinition.name()));
                    java.util.Set<String> owned=new java.util.TreeSet<>();schema.tables().forEach(t->owned.add(org.vernac.compiler.persistence.SqlNames.physical(t.namespace())+"/"+org.vernac.compiler.persistence.SqlNames.physical(t.name())));
                    Path sourceHistory=historyDirectory==null?base.resolve("src/main/vernac-schema/history"):historyDirectory.toPath();
                    if(Files.isDirectory(sourceHistory))try(var files=Files.list(sourceHistory)){for(var file:files.filter(f->f.getFileName().toString().endsWith(".json")).toList()){var snapshot=org.vernac.compiler.persistence.SchemaModel.JSON.readValue(Files.readString(file),org.vernac.compiler.persistence.SchemaHistory.Snapshot.class);snapshot.model().tables().forEach(t->owned.add(org.vernac.compiler.persistence.SqlNames.physical(t.namespace())+"/"+org.vernac.compiler.persistence.SqlNames.physical(t.name())));}}
                    ModuleDescriptor.write(schemaResource,java.util.Map.of("schema",schema,"ownedTables",owned));
                }
                Resource resource=new Resource();resource.setDirectory(compositionResources.toString());project.addResource(resource);
            } else {
                if(Files.isRegularFile(autoImports)) {
                    String owned=Files.readString(autoImports);
                    if(project!=null&&project.getBuild().getOutputDirectory()!=null) {
                        Path output=Path.of(project.getBuild().getOutputDirectory()).resolve(org.vernac.compiler.application.ApplicationBindings.IMPORTS);
                        if(Files.isRegularFile(output)&&Files.readString(output).equals(owned))Files.delete(output);
                    }
                    Files.delete(autoImports);
                }
            }
            if(project!=null&&resolved!=null)org.vernac.compiler.vocabulary.Vocabulary.write(base.resolve("target"),resolved.project());
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
                getLog().info("Published Vernac module contract: "+published.module()+" ("+published.exports().size()+" exports)");
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