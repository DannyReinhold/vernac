// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;
import java.util.*;
import java.nio.file.Path;
import org.apache.maven.project.MavenProject;
import org.vernac.compiler.modules.*;
final class ModuleClasspath {
    private ModuleClasspath() { }
    static List<ModuleDescriptor> read(MavenProject project) throws java.io.IOException {
        if(project==null)return List.of();
        List<Path> paths=new ArrayList<>();
        for(var artifact:project.getArtifacts()) if(artifact.getFile()!=null && Set.of("compile","provided","system").contains(artifact.getScope()))paths.add(artifact.getFile().toPath());
        return ModuleArtifacts.read(paths);
    }
}
