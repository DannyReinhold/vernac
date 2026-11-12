// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.vernac.compiler.application.*;
import org.vernac.compiler.modules.*;
import org.vernac.compiler.persistence.SchemaModel;
import org.vernac.compiler.pipeline.*;

class UseCaseAccessAuditExecutionTest {
    @TempDir Path root;
    private Path source(String folder,String namespace,String text) throws Exception {
        Path base=root.resolve(folder),file=base.resolve(namespace.replace('.','/')+"/model.vernac");
        Files.createDirectories(file.getParent());Files.writeString(file,"namespace "+namespace+";"+text);return base;
    }
    private record Application(URLClassLoader loader,String configuration) implements AutoCloseable {
        AnnotationConfigApplicationContext context() throws Exception {
            var context=new AnnotationConfigApplicationContext();context.setClassLoader(loader);
            context.register(loader.loadClass(configuration),loader.loadClass("app.usecase.Entry"));return context;
        }
        public void close() throws Exception {loader.close();}
    }
    private Application compile(boolean audit,boolean activate,boolean localExplicit,boolean includeModule) throws Exception {
        var compiler=new VernacCompiler();var files=new ArrayList<com.palantir.javapoet.JavaFile>();var modules=new ArrayList<ModuleDescriptor>();
        if(includeModule) {
            var producer=compiler.analyzeProject(source("producer","library","usecase Internal() behavior {execute {}} usecase Facade() requires public uses Internal behavior {execute {self.internal().execute();}}"));
            var generated=compiler.generate(producer,SchemaModel.empty(),Map.of());
            var stripped=ApplicationBindings.generate(new ApplicationDefinition("library",List.of(),List.of()),producer,List.of(),generated.generatedFiles(),true);
            files.addAll(stripped.files());modules.add(ModuleContracts.publish(ModuleDefinition.parse(root.resolve("module.vmodule"),"module example.library {export library.Facade;}"),producer,stripped.files(),"example","library","1",List.of()));
        }
        String dependency=activate?"import library.Facade;":"";
        String requirement=localExplicit?" requires public ":" ";
        String uses=activate?"uses Facade ":"";
        var consumer=compiler.analyzeProject(source("consumer","app",dependency+"usecase Entry()"+requirement+uses+"behavior {execute {}}"),modules);
        var app=ApplicationDefinition.parse(root.resolve(ApplicationDefinition.FILE),"application example.app {"+(audit?"authorization explicit;":"")+(activate?"activate example.library;":"")+"}");
        var generated=ApplicationBindings.generate(app,consumer,modules,compiler.generate(consumer,SchemaModel.empty(),Map.of()).generatedFiles(),false);
        assertTrue(generated.configuration().isPresent());files.addAll(generated.files());
        Path javaRoot=root.resolve("java"),classes=Files.createDirectories(root.resolve("classes"));for(var file:files)file.writeTo(javaRoot);
        var arguments=new ArrayList<>(List.of("--release","21","-proc:none","-classpath",System.getProperty("java.class.path"),"-d",classes.toString()));
        try(var paths=Files.walk(javaRoot)){paths.filter(p->p.toString().endsWith(".java")).map(Path::toString).forEach(arguments::add);}
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,arguments.toArray(String[]::new)));
        return new Application(new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},getClass().getClassLoader()),generated.configuration().orElseThrow());
    }
    @Test void generatedLocalAuditRejectsOmissionBeforeAcceptingAnExplicitPublicEntry() throws Exception {
        try(var app=compile(true,false,false,false);var context=app.context()) {
            var error=assertThrows(RuntimeException.class,context::refresh);assertTrue(messages(error).contains("app.Entry"));
        }
        try(var app=compile(true,false,true,false);var context=app.context()) {context.refresh();assertNotNull(context.getBean(app.loader().loadClass("app.usecase.Entry")));}
    }
    @Test void activatedModulesIncludePrivateUsecasesButInactiveTypeDependenciesDoNot() throws Exception {
        try(var app=compile(true,true,true,true);var context=app.context()) {
            var error=assertThrows(RuntimeException.class,context::refresh);assertTrue(messages(error).contains("library.Internal"));
        }
        try(var app=compile(true,false,true,true);var context=app.context()) {context.refresh();assertFalse(context.containsBean("library.Internal"));}
    }
    @Test void ordinaryApplicationsRetainOmittedClauseBehavior() throws Exception {
        try(var app=compile(false,true,false,true);var context=app.context()) {context.refresh();assertTrue(context.containsBean("library.Internal"));}
    }
    @Test void modulesCannotPublishAnApplicationOwnedAuditSwitch() throws Exception {
        var compiler=new VernacCompiler();var model=compiler.analyzeProject(source("producer","library","usecase Work() behavior {execute {}}"));
        var app=ApplicationDefinition.parse(root.resolve(ApplicationDefinition.FILE),"application example.library {authorization explicit;}");
        assertThrows(IllegalArgumentException.class,()->ApplicationBindings.generate(app,model,List.of(),compiler.generate(model,SchemaModel.empty(),Map.of()).generatedFiles(),true));
    }
    @Test void historyOnlyPublicationRetainsPrivateAccessAndVocabulary() throws Exception {
        Path base=source("history-only","history","id OldId; aggregate Old[OldId](); repository OldRepository for Old {}");
        var compiler=new VernacCompiler();var before=new org.vernac.compiler.persistence.SchemaBuilder(compiler.analyzeProject(base),SchemaModel.empty()).build();
        Path projectRoot=root.resolve("historical-project");Files.createDirectories(projectRoot);
        Path history=projectRoot.resolve("src/main/vernac-schema/history"),sql=projectRoot.resolve("src/main/vernac-schema/migration");
        var storage=new org.vernac.compiler.persistence.SchemaHistory(history,sql);
        var planner=new org.vernac.compiler.persistence.MigrationPlanner();
        storage.append("20261112090000000","initial",SchemaModel.empty(),before,planner.plan(SchemaModel.empty(),before,org.vernac.compiler.persistence.MigrationPlanner.Options.safe()));
        storage.append("20261112100000000","remove_storage",before,SchemaModel.empty(),planner.plan(before,SchemaModel.empty(),new org.vernac.compiler.persistence.MigrationPlanner.Options(true,Map.of(),Map.of())));
        Path source=projectRoot.resolve("src/main/vernac/history/model.vernac");Files.createDirectories(source.getParent());
        Files.writeString(source,"namespace history; term Rights {description \"Preserved access vocabulary.\";} permission Rights {VIEW;} usecase Internal() requires permission Rights.VIEW behavior {execute {}} usecase Facade() requires public behavior {execute {}}");
        Files.writeString(projectRoot.resolve(ModuleDefinition.FILE),"module example.historical {export history.Rights; export history.Facade;}");
        Files.writeString(projectRoot.resolve("pom.xml"),"<project/>");
        var project=new org.apache.maven.project.MavenProject();project.setFile(projectRoot.resolve("pom.xml").toFile());project.setGroupId("example");project.setArtifactId("historical");project.setVersion("1");
        var mojo=new VernacCompileMojo();mojo.setProject(project);mojo.setSourceDirectory(projectRoot.resolve("src/main/vernac").toFile());mojo.setOutputDirectory(projectRoot.resolve("target/generated-sources/vernac").toFile());mojo.execute();
        var descriptor=ModuleDescriptor.JSON.readValue(Files.readString(projectRoot.resolve("target/generated-resources/vernac-module").resolve(ModuleDescriptor.RESOURCE)),ModuleDescriptor.class);
        assertTrue(descriptor.persistent());assertEquals("Preserved access vocabulary.",descriptor.vocabulary().getFirst().description());
        assertEquals("PERMISSION",descriptor.usecaseAccess().stream().filter(a->a.identity().equals("history.Internal")).findFirst().orElseThrow().requirement().mode());
        ModuleContracts.checkDependencies(List.of(descriptor));
    }
    private String messages(Throwable error) {var result=new StringBuilder();for(var current=error;current!=null;current=current.getCause())result.append(current.getMessage()).append('\n');return result.toString();}
}
