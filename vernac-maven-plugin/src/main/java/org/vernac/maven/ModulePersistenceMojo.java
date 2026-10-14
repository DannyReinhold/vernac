// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.vernac.compiler.modules.*;

/** Prepares a verified artifact contract; never migrates a database or enables persistent exports. */
@Mojo(name="module-persistence",threadSafe=true,requiresDependencyResolution=ResolutionScope.COMPILE)
public final class ModulePersistenceMojo extends SchemaMojo {
    @Parameter(defaultValue="${project.build.directory}/generated-resources/vernac-module-persistence")
    File outputDirectory;
    @Override public void execute() throws MojoExecutionException,MojoFailureException {
        try {
            if(project==null||project.getBasedir()==null)throw new IllegalArgumentException("Module persistence packaging requires a Maven project");
            Path manifest=project.getBasedir().toPath().resolve(ModuleDefinition.FILE);
            if(!Files.isRegularFile(manifest))throw new IllegalArgumentException("Module persistence packaging requires "+ModuleDefinition.FILE);
            var definition=ModuleDefinition.parse(manifest,Files.readString(manifest));
            Path sql=migrations().toPath().toAbsolutePath().normalize();
            ModulePersistenceResources.validateSource(project,sql);
            var previous=history().read();
            var bundle=ModulePersistence.prepare(definition.name(),model(previous),historyDirectory.toPath(),sql);
            ModulePersistence.verify(bundle.descriptor(),bundle.resources());
            ModulePersistenceResources.write(project,outputDirectory.toPath(),bundle);
            getLog().info("Prepared module persistence contract: "+definition.name()+" ("+bundle.descriptor().migrations().size()+" migrations)");
            getLog().warn("No database was modified. This preparation goal does not enable persistent module exports or automatic activation/migrations.");
            getLog().warn("You alone are responsible for reviewing, testing and approving migration candidates before applying them. Checksums and schema history checks do not prove SQL correctness or deployment safety.");
        }catch(Exception e){throw new MojoFailureException("Module persistence preparation failed: "+e.getMessage(),e);}
    }
}
