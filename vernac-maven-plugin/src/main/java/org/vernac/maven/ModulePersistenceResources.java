// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;
import java.nio.file.*;
import java.util.*;
import org.apache.maven.model.Resource;
import org.apache.maven.project.MavenProject;
import org.vernac.compiler.modules.*;
final class ModulePersistenceResources {
    private ModulePersistenceResources() { }
    static void validateSource(MavenProject project,Path sql) {
        List<Path> sourceResources=new ArrayList<>();sourceResources.add(project.getBasedir().toPath().resolve("src/main/resources").toAbsolutePath().normalize());
        for(var configured:project.getResources())if(configured.getDirectory()!=null)sourceResources.add(project.getBasedir().toPath().resolve(configured.getDirectory()).toAbsolutePath().normalize());
        if(sourceResources.stream().anyMatch(sql::startsWith))throw new IllegalArgumentException("Module SQL must be outside ordinary Maven resources, otherwise application Flyway discovery can mix migration histories. Use src/main/vernac-schema/migration or configure vernac.migrations outside resource roots.");
    }
    static void write(MavenProject project,Path output,ModulePersistence.Bundle bundle) throws java.io.IOException {

        // Track only this goal's generated files. Never infer ownership of other resources.
        Path inventory=output.resolveSibling(output.getFileName()+".manifest.json");
        if(Files.isRegularFile(inventory)) {
            var old=ModuleDescriptor.JSON.readValue(Files.readString(inventory),new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){});
            for(String name:old) {
                Path relative=Path.of(name);
                if(relative.isAbsolute()||relative.normalize().startsWith("..")||!name.startsWith("META-INF/vernac/modules/"))throw new IllegalArgumentException("Invalid generated module persistence inventory; run Maven clean");
            }
            for(String name:old)if(!bundle.resources().containsKey(name))Files.deleteIfExists(output.resolve(name));
        }
        for(var entry:bundle.resources().entrySet()) {
            Path target=output.resolve(entry.getKey());Files.createDirectories(target.getParent());Files.write(target,entry.getValue());
        }
        ModuleDescriptor.write(inventory,new ArrayList<>(bundle.resources().keySet()));
        Resource resource=new Resource();resource.setDirectory(output.toString());project.addResource(resource);
    }
}
