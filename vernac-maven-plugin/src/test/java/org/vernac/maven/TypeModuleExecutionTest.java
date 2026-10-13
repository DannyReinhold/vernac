// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;
import org.apache.maven.project.MavenProject;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.vernac.compiler.modules.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class TypeModuleExecutionTest {
    @TempDir Path root;
    private MavenProject project(Path base,String artifact) throws Exception {
        Files.createDirectories(base);Files.writeString(base.resolve("pom.xml"),"<project/>");
        var project=new MavenProject();project.setFile(base.resolve("pom.xml").toFile());project.setGroupId("org.example");project.setArtifactId(artifact);project.setVersion("1.0.0");return project;
    }
    private Path compile(MavenProject project) throws Exception {
        Path base=project.getBasedir().toPath(),source=base.resolve("src/main/vernac"),output=base.resolve("target/generated-sources/vernac");
        var mojo=new VernacCompileMojo();mojo.setProject(project);mojo.setSourceDirectory(source.toFile());mojo.setOutputDirectory(output.toFile());mojo.execute();
        Path classes=Files.createDirectories(base.resolve("target/classes"));
        var args=new ArrayList<>(List.of("--release","21","-proc:none","-classpath",System.getProperty("java.class.path")+java.io.File.pathSeparator+project.getArtifacts().stream().map(a->a.getFile().toString()).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator)),"-d",classes.toString()));
        try(var files=Files.walk(output)){files.filter(p->p.toString().endsWith(".java")).map(Path::toString).forEach(args::add);}
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)));
        Path resources=base.resolve("target/generated-resources/vernac-module");
        if(Files.isDirectory(resources))try(var files=Files.walk(resources)){for(var file:files.filter(Files::isRegularFile).toList()){var dest=classes.resolve(resources.relativize(file));Files.createDirectories(dest.getParent());Files.copy(file,dest);}}
        return classes;
    }
    @Test void separateJarPublishesContractsAndConsumerUsesCompiledClassesWithoutRegeneration() throws Exception {
        var producer=project(root.resolve("producer"),"values");Path model=producer.getBasedir().toPath().resolve("src/main/vernac/lib/values/model.vernac");Files.createDirectories(model.getParent());Files.writeString(model,"namespace lib.values; id Ref; value Name(String) list Names; value Detail(Name, int count);");
        Files.writeString(producer.getBasedir().toPath().resolve(ModuleDefinition.FILE),"module lib.values {export lib.values.Ref;export lib.values.Name;export lib.values.Names;export lib.values.Detail;}");
        Path classes=compile(producer),jar=root.resolve("values.jar");
        try(var out=new JarOutputStream(Files.newOutputStream(jar));var files=Files.walk(classes)){for(var file:files.filter(Files::isRegularFile).toList()){out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\','/')));Files.copy(file,out);out.closeEntry();}}
        var consumer=project(root.resolve("consumer"),"app");var artifact=new DefaultArtifact("org.example","values","1.0.0","compile","jar",null,new DefaultArtifactHandler("jar"));artifact.setFile(jar.toFile());consumer.setArtifacts(Set.of(artifact));
        Path local=consumer.getBasedir().toPath().resolve("src/main/vernac/app/model.vernac");Files.createDirectories(local.getParent());Files.writeString(local,"namespace app; import lib.values.Detail; value Snapshot(Detail);");
        Path consumerClasses=compile(consumer);
        try(var code=new URLClassLoader(new URL[]{consumerClasses.toUri().toURL(),jar.toUri().toURL()},getClass().getClassLoader())) {
            var name=code.loadClass("lib.values.domain.Name");Object n=name.getMethod("of",String.class).invoke(null,"Example");
            var detail=code.loadClass("lib.values.domain.Detail");Object d=detail.getMethod("of",name,int.class).invoke(null,n,7);
            var snapshot=code.loadClass("app.domain.Snapshot");Object s=snapshot.getMethod("of",detail).invoke(null,d);assertSame(d,snapshot.getMethod("detail").invoke(s));
        }
        assertFalse(Files.exists(consumer.getBasedir().toPath().resolve("target/generated-sources/vernac/lib")));
        assertEquals(1,ModuleArtifacts.editorModules(local.getParent().getParent()).size());
        assertTrue(Files.exists(producer.getBasedir().toPath().resolve("target/generated-module-sources/vernac/lib/values/model.vernac")));
    }
}
