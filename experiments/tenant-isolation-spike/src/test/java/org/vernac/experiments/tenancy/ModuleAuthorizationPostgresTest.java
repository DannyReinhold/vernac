// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.experiments.tenancy;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URLClassLoader;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.vernac.compiler.application.*;
import org.vernac.compiler.modules.*;
import org.vernac.compiler.persistence.SchemaModel;
import org.vernac.compiler.pipeline.*;
import org.vernac.runtime.authorization.*;
import org.vernac.runtime.events.EventHandler;

/** Reuses all six behavioral proofs against imported JAR classes and generated module activation. */
class ModuleAuthorizationPostgresTest extends GeneratedAuthorizationPostgresTest {
    Path producerJar;
    ModuleDescriptor descriptor;
    String configuration;

    @Override @BeforeAll void compileAndDatabase(@TempDir Path temporary) throws Exception {
        Path producer=temporary.resolve("producer/source");
        write(producer.resolve("sample/auth/model.vernac"),MODEL+"\nusecase Internal(InvoiceId) requires permission Rights.VIEW on invoiceId behavior {execute {}}\n");
        write(producer.resolve("sample/security/rights.vernac"),"""
            namespace sample.security;
            term Rights { description "Invoice export rights."; }
            permission Rights {VIEW; EXPORT; String; name; values; qualifiedName;}
            """);
        var compiler=new VernacCompiler(); var resolved=compiler.analyzeProject(producer);
        var original=compiler.generate(resolved,SchemaModel.empty(),Map.of());
        var library=ApplicationBindings.generate(new ApplicationDefinition("sample.library",List.of(),List.of()),resolved,List.of(),original.generatedFiles(),true);
        var exports=new ArrayList<String>();
        for(var identity:List.of("sample.auth.InvoiceId","sample.auth.Label","sample.auth.Flag","sample.security.Rights",
                "sample.auth.ExportInvoice","sample.auth.ExternalInvoice","sample.auth.Outer","sample.auth.SignedIn","sample.auth.PublicEntry")) exports.add("export "+identity+";");
        var manifest=ModuleDefinition.parse(temporary.resolve("producer/vernac-module.vmodule"),"module sample.library {"+String.join("",exports)+"}");
        descriptor=ModuleContracts.publish(manifest,resolved,library.files(),"sample","library","1.0",List.of());
        Path java=temporary.resolve("producer/java"),output=Files.createDirectories(temporary.resolve("producer/classes"));
        for(var file:library.files())file.writeTo(java);
        write(java.resolve("fixture/Probe.java"),PROBE);write(java.resolve("fixture/External.java"),EXTERNAL);
        compile(java,output,System.getProperty("java.class.path"));
        ModuleDescriptor.write(output.resolve(ModuleDescriptor.RESOURCE),descriptor);
        producerJar=temporary.resolve("library.jar");
        try(var jar=new JarOutputStream(Files.newOutputStream(producerJar));var paths=Files.walk(output)) {
            for(var path:paths.filter(Files::isRegularFile).sorted().toList()) {
                jar.putNextEntry(new JarEntry(output.relativize(path).toString().replace('\\','/')));Files.copy(path,jar);jar.closeEntry();
            }
        }
        // Read from the packaged artifact, not the producer AST or source attachment.
        var modules=ModuleArtifacts.read(List.of(producerJar)); descriptor=modules.getFirst();
        assertEquals("PERMISSION",descriptor.exports().stream().filter(d->d.identity().equals("sample.security.Rights")).findFirst().orElseThrow().kind());
        assertTrue(descriptor.usecaseAccess().stream().anyMatch(a->a.identity().equals("sample.auth.Internal")&&a.requirement().mode().equals("PERMISSION")));
        assertTrue(descriptor.exports().stream().noneMatch(d->d.identity().equals("sample.auth.Internal")));
        assertEquals("Invoice export rights.",descriptor.vocabulary().getFirst().description());
        Path consumer=temporary.resolve("consumer/source");
        write(consumer.resolve("client/model.vernac"),"""
            namespace client;
            import sample.auth.InvoiceId;
            import sample.auth.Label;
            import sample.auth.ExportInvoice;
            import sample.security.Rights;
            usecase Forward(InvoiceId) returns Label
                requires permission Rights.EXPORT on invoiceId
                uses ExportInvoice
            behavior {execute {return self.exportInvoice().execute(invoiceId);}}
            """);
        var model=compiler.analyzeProject(consumer,modules);
        var generated=compiler.generate(model,SchemaModel.empty(),Map.of());
        assertTrue(generated.generatedFiles().stream().noneMatch(f->f.packageName().startsWith("sample.")));
        var application=ApplicationDefinition.parse(temporary.resolve("consumer/vernac-application.vapplication"),"application client.app {activate sample.library;}");
        assertTrue(application.diagnostics(model,modules).isEmpty());
        var files=ApplicationBindings.generate(application,model,modules,generated.generatedFiles(),false);
        configuration=files.configuration().orElseThrow();
        Path clientJava=temporary.resolve("consumer/java"),clientClasses=Files.createDirectories(temporary.resolve("consumer/classes"));
        for(var file:files.files())file.writeTo(clientJava);
        compile(clientJava,clientClasses,System.getProperty("java.class.path")+File.pathSeparator+producerJar);
        classes=new URLClassLoader(new java.net.URL[]{clientClasses.toUri().toURL(),producerJar.toUri().toURL()},getClass().getClassLoader());
        idType=classes.loadClass("sample.auth.domain.InvoiceId");flagType=classes.loadClass("sample.auth.domain.Flag");probe=classes.loadClass("fixture.Probe");
        id=idType.getMethod("of",UUID.class).invoke(null,PostgresFixture.INVOICE_A);
        assertEquals(producerJar.toUri().toURL(),idType.getProtectionDomain().getCodeSource().getLocation());
        fixture=new PostgresFixture();
    }
    @Override @SuppressWarnings({"rawtypes","unchecked"})
    void registerOperations(ObjectProvider<EventHandler> events) throws Exception {
        context.register(classes.loadClass(configuration));
        context.registerAlias("sample.auth.ExportInvoice","exporter");
        context.registerAlias("sample.auth.ExternalInvoice","external");
        context.registerAlias("sample.auth.Outer","outer");
        context.registerAlias("sample.auth.SignedIn","signed");
        context.registerAlias("sample.auth.PublicEntry","publicEntry");
        context.registerBean("clientForward",(Class)classes.loadClass("client.usecase.Forward"));
    }
    @Test void consumerAndActivatedModuleShareActorTenantAndTheRequiredTransaction() {
        policy.export=true;
        assertEquals("done",scoped(()->text(call("clientForward",id))));
        assertEquals(2,version());
        assertEquals(List.of("client.Forward","sample.auth.ExportInvoice","sample.auth.ExportInvoice"),policy.calls.stream().map(PermissionRequest::operation).toList());
        assertEquals(1,policy.calls.stream().map(PermissionRequest::actor).distinct().count());
        assertEquals(1,policy.calls.stream().map(PermissionRequest::tenant).distinct().count());
        assertNotNull(context.getBean("sample.auth.Internal"));
    }
    private static void compile(Path java,Path output,String classpath) throws Exception {
        var args=new ArrayList<>(List.of("--release","21","-parameters","-g","-classpath",classpath,"-d",output.toString()));
        try(var files=Files.walk(java)){files.filter(p->p.toString().endsWith(".java")).map(Path::toString).forEach(args::add);}
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)));
    }
}
