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
        for(String directory:List.of("vernac-module","vernac-module-persistence","vernac-application")) {
            Path resources=base.resolve("target/generated-resources/"+directory);
            if(Files.isDirectory(resources))try(var files=Files.walk(resources)){for(var file:files.filter(Files::isRegularFile).toList()){var dest=classes.resolve(resources.relativize(file));Files.createDirectories(dest.getParent());Files.copy(file,dest,StandardCopyOption.REPLACE_EXISTING);}}
        }
        return classes;
    }
    @Test void separateJarPublishesContractsAndConsumerUsesCompiledClassesWithoutRegeneration() throws Exception {
        var producer=project(root.resolve("producer"),"values");Path model=producer.getBasedir().toPath().resolve("src/main/vernac/lib/values/model.vernac");Files.createDirectories(model.getParent());Files.writeString(model,"namespace lib.values; id Ref; value Name(String) list Names; value Detail(Name, int count); port Provider {Detail echo(Detail);}");
        Files.writeString(producer.getBasedir().toPath().resolve(ModuleDefinition.FILE),"module lib.values {export lib.values.Ref;export lib.values.Name;export lib.values.Names;export lib.values.Detail;export lib.values.Provider;}");
        Path classes=compile(producer),jar=root.resolve("values.jar");
        try(var out=new JarOutputStream(Files.newOutputStream(jar));var files=Files.walk(classes)){for(var file:files.filter(Files::isRegularFile).toList()){out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\','/')));Files.copy(file,out);out.closeEntry();}}
        var consumer=project(root.resolve("consumer"),"app");var artifact=new DefaultArtifact("org.example","values","1.0.0","compile","jar",null,new DefaultArtifactHandler("jar"));artifact.setFile(jar.toFile());consumer.setArtifacts(Set.of(artifact));
        Path local=consumer.getBasedir().toPath().resolve("src/main/vernac/app/model.vernac");Files.createDirectories(local.getParent());Files.writeString(local,"namespace app; import lib.values.Detail; import lib.values.Provider; value Snapshot(Detail); adapter LocalProvider implements Provider {behavior {public Detail echo(Detail detail) {return detail;}}}");
        Path consumerClasses=compile(consumer);
        try(var code=new URLClassLoader(new URL[]{consumerClasses.toUri().toURL(),jar.toUri().toURL()},getClass().getClassLoader())) {
            var name=code.loadClass("lib.values.domain.Name");Object n=name.getMethod("of",String.class).invoke(null,"Example");
            var detail=code.loadClass("lib.values.domain.Detail");Object d=detail.getMethod("of",name,int.class).invoke(null,n,7);
            var snapshot=code.loadClass("app.domain.Snapshot");Object s=snapshot.getMethod("of",detail).invoke(null,d);assertSame(d,snapshot.getMethod("detail").invoke(s));
            var contract=code.loadClass("lib.values.domain.Provider");
            Object adapter=code.loadClass("app.adapter.outbound.LocalProvider").getConstructor().newInstance();
            assertTrue(contract.isInstance(adapter));assertSame(d,contract.getMethod("echo",detail).invoke(adapter,d));
        }
        assertFalse(Files.exists(consumer.getBasedir().toPath().resolve("target/generated-sources/vernac/lib")));
        assertEquals(1,ModuleArtifacts.editorModules(local.getParent().getParent()).size());
        assertTrue(Files.exists(producer.getBasedir().toPath().resolve("target/generated-module-sources/vernac/lib/values/model.vernac")));
    }
    private Path jar(Path classes,String name) throws Exception {
        Path jar=root.resolve(name+".jar");
        try(var out=new JarOutputStream(Files.newOutputStream(jar));var files=Files.walk(classes)){for(var file:files.filter(Files::isRegularFile).toList()){out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\','/')));Files.copy(file,out);out.closeEntry();}}
        return jar;
    }
    private org.apache.maven.artifact.Artifact artifact(Path jar,String name) {
        var artifact=new DefaultArtifact("org.example",name,"1.0.0","compile","jar",null,new DefaultArtifactHandler("jar"));artifact.setFile(jar.toFile());return artifact;
    }
    @Test void thirdArtifactProvidesSelectedAdapterAndOnlySelectedConfigurationIsRequired() throws Exception {
        var contracts=project(root.resolve("contracts"),"contracts");
        Path contractSource=contracts.getBasedir().toPath().resolve("src/main/vernac/billing/api/model.vernac");Files.createDirectories(contractSource.getParent());
        Files.writeString(contractSource,"namespace billing.api; value Receipt(String); port PaymentProvider {Receipt pay();}");
        Files.writeString(contracts.getBasedir().toPath().resolve(ModuleDefinition.FILE),"module billing.contracts {export billing.api.Receipt;export billing.api.PaymentProvider;}");
        Path contractJar=jar(compile(contracts),"contracts");
        var providers=project(root.resolve("providers"),"providers");providers.setArtifacts(Set.of(artifact(contractJar,"contracts")));
        Path providerSource=providers.getBasedir().toPath().resolve("src/main/vernac/billing/providers/model.vernac");Files.createDirectories(providerSource.getParent());
        Files.writeString(providerSource,"namespace billing.providers; import billing.api.Receipt; import billing.api.PaymentProvider; adapter Noop implements PaymentProvider {behavior {public Receipt pay(){return Receipt.of(\"noop\");}}} adapter Remote implements PaymentProvider {configuration {String apiKey from \"remote.api-key\";} behavior {public Receipt pay(){return Receipt.of(\"remote\");}}}");
        Files.writeString(providers.getBasedir().toPath().resolve(ModuleDefinition.FILE),"module billing.providers {export billing.providers.Noop;export billing.providers.Remote;}");
        Path providerClasses=compile(providers),providerJar=jar(providerClasses,"providers");
        assertFalse(Files.readString(providers.getBasedir().toPath().resolve("target/generated-sources/vernac/billing/providers/adapter/outbound/Remote.java")).contains("@Component"));
        var application=project(root.resolve("application"),"application");application.setArtifacts(Set.of(artifact(contractJar,"contracts"),artifact(providerJar,"providers")));
        Path source=application.getBasedir().toPath().resolve("src/main/vernac/app/model.vernac");Files.createDirectories(source.getParent());Files.writeString(source,"namespace app; value Name(String);");
        Files.writeString(application.getBasedir().toPath().resolve("vernac-application.vapplication"),"application app {bind billing.api.PaymentProvider from configuration \"billing.payment-provider\";}");
        Path classes=compile(application);
        assertTrue(Files.readString(classes.resolve("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")).contains("app.configuration.__VernacApplication"));
        try(var code=new URLClassLoader(new URL[]{classes.toUri().toURL(),providerJar.toUri().toURL(),contractJar.toUri().toURL()},getClass().getClassLoader())) {
            Class<?> config=code.loadClass("app.configuration.__VernacApplication"),port=code.loadClass("billing.api.domain.PaymentProvider");
            for(String selected:List.of("billing.providers.Noop","billing.providers.Remote")) {
                try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
                    context.setClassLoader(code);
                    context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test",selected.endsWith("Remote")?Map.of("billing.payment-provider",selected,"remote.api-key","fixture-only"):Map.of("billing.payment-provider",selected)));
                    context.register(config);context.refresh();Object bean=context.getBean(port);
                    assertEquals(selected.substring(selected.lastIndexOf('.')+1),bean.getClass().getSimpleName());
                    assertEquals(1,context.getBeanNamesForType(port).length);
                    assertNotNull(port.getMethod("pay").invoke(bean));
                }
            }
            try(var missing=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
                missing.setClassLoader(code);missing.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test",Map.of("billing.payment-provider","billing.providers.Remote")));missing.register(config);
                assertThrows(RuntimeException.class,missing::refresh);
            }
        }
    }
    @Test void fixedBindingPreservesUppercaseJavaPackageSegments() throws Exception {
        var application=project(root.resolve("upper"),"upper");
        Path source=application.getBasedir().toPath().resolve("src/main/vernac/UPPER/Billing/model.vernac");Files.createDirectories(source.getParent());
        Files.writeString(source,"namespace UPPER.Billing; port Payment {void pay();} adapter Noop implements Payment {behavior {public void pay(){}}}");
        Files.writeString(application.getBasedir().toPath().resolve("vernac-application.vapplication"),"application UPPER.Shop {bind UPPER.Billing.Payment to UPPER.Billing.Noop;}");
        Path classes=compile(application);
        assertTrue(Files.isRegularFile(classes.resolve("UPPER/Shop/configuration/__VernacApplication.class")));
    }
    @Test void functionalModuleRegistersInternalServiceAndExportedUsecaseWithoutScanning() throws Exception {
        var foundation=project(root.resolve("foundation"),"foundation");
        Path foundationSource=foundation.getBasedir().toPath().resolve("src/main/vernac/support/model.vernac");Files.createDirectories(foundationSource.getParent());
        Files.writeString(foundationSource,"namespace support; service Format behavior {public String text(){return \"accepted\";}}");
        Files.writeString(foundation.getBasedir().toPath().resolve(ModuleDefinition.FILE),"module example.support {export support.Format;}");
        Path foundationJar=jar(compile(foundation),"foundation");
        var producer=project(root.resolve("functions"),"functions");producer.setArtifacts(Set.of(artifact(foundationJar,"foundation")));
        Path source=producer.getBasedir().toPath().resolve("src/main/vernac/billing/model.vernac");Files.createDirectories(source.getParent());
        Files.writeString(source,"namespace billing; import support.Format; value Receipt(String); service Helper uses Format format behavior {public Receipt receipt(){return Receipt.of(self.format().text());}} usecase Pay() returns Receipt uses Helper helper behavior {execute {return self.helper().receipt();}}");
        Files.writeString(producer.getBasedir().toPath().resolve(ModuleDefinition.FILE),"module example.billing {export billing.Receipt;export billing.Pay;}");
        Path producerClasses=compile(producer),published=jar(producerClasses,"functions");
        assertFalse(Files.readString(producer.getBasedir().toPath().resolve("target/generated-sources/vernac/billing/domain/Helper.java")).contains("@Service"));
        var application=project(root.resolve("functional-app"),"functional-app");application.setArtifacts(Set.of(artifact(published,"functions"),artifact(foundationJar,"foundation")));
        Path local=application.getBasedir().toPath().resolve("src/main/vernac/app/model.vernac");Files.createDirectories(local.getParent());
        Files.writeString(local,"namespace app; import billing.Pay; import billing.Receipt; usecase Checkout() returns Receipt uses Pay pay behavior {execute {return self.pay().execute();}}");
        Files.writeString(application.getBasedir().toPath().resolve("vernac-application.vapplication"),"application app {activate example.billing;}");
        Path classes=compile(application);
        try(var code=new URLClassLoader(new URL[]{classes.toUri().toURL(),published.toUri().toURL(),foundationJar.toUri().toURL()},getClass().getClassLoader());var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.setClassLoader(code);context.registerBean("transactionManager",CountingTransactions.class);
            context.register(code.loadClass("app.configuration.__VernacApplication"),code.loadClass("app.usecase.Checkout"));context.refresh();
            Object checkout=context.getBean(code.loadClass("app.usecase.Checkout"));Object receipt=checkout.getClass().getMethod("execute").invoke(checkout);
            assertEquals("accepted",receipt.getClass().getMethod("string").invoke(receipt));
            assertTrue(context.containsBean("billing.Helper"));assertTrue(context.containsBean("billing.Pay"));assertTrue(context.containsBean("support.Format"));
            assertEquals(1,context.getBean(CountingTransactions.class).begins);
            assertEquals(1,context.getBean(CountingTransactions.class).commits);
        }
        Files.writeString(application.getBasedir().toPath().resolve("vernac-application.vapplication"),"application app {}");
        assertThrows(Exception.class,()->compile(application));
    }

    @Test void exportedEventsReachOnlyActivatedSynchronousReceiversInTheCallingTransaction() throws Exception {
        var publisher=project(root.resolve("event-publisher"),"event-publisher");
        Path publisherSource=publisher.getBasedir().toPath().resolve("src/main/vernac/events/api/model.vernac");Files.createDirectories(publisherSource.getParent());
        Files.writeString(publisherSource,"namespace events.api; value Name(String); event Changed(Name); usecase Publish(Name) behavior {execute {self.emit(Changed.create(name));}}");
        Files.writeString(publisher.getBasedir().toPath().resolve(ModuleDefinition.FILE),"module example.publisher {export events.api.Name;export events.api.Changed;export events.api.Publish;}");
        var eventCompiler=new org.vernac.compiler.pipeline.VernacCompiler();
        var eventSchema=new org.vernac.compiler.persistence.SchemaBuilder(eventCompiler.analyzeProject(publisher.getBasedir().toPath().resolve("src/main/vernac")),org.vernac.compiler.persistence.SchemaModel.empty()).build();
        new org.vernac.compiler.persistence.SchemaHistory(publisher.getBasedir().toPath().resolve("src/main/vernac-schema/history"),publisher.getBasedir().toPath().resolve("src/main/vernac-schema/migration"))
            .append("20261013210000000","initial_events",org.vernac.compiler.persistence.SchemaModel.empty(),eventSchema,new org.vernac.compiler.persistence.MigrationPlanner().plan(org.vernac.compiler.persistence.SchemaModel.empty(),eventSchema,org.vernac.compiler.persistence.MigrationPlanner.Options.safe()));
        Path publisherJar=jar(compile(publisher),"event-publisher");
        var observer=project(root.resolve("event-observer"),"event-observer");observer.setArtifacts(Set.of(artifact(publisherJar,"event-publisher")));
        Path observerSource=observer.getBasedir().toPath().resolve("src/main/vernac/events/observer/model.vernac");Files.createDirectories(observerSource.getParent());
        Files.writeString(observerSource,"namespace events.observer; import events.api.Changed; synchronous listener Observe on Changed behavior {java imports {org.vernac.maven.ModuleEventProbe;} execute {ModuleEventProbe.observe(event.name().string());}}");
        Files.writeString(observer.getBasedir().toPath().resolve(ModuleDefinition.FILE),"module example.observer {}");
        Path observerClasses=compile(observer),observerJar=jar(observerClasses,"event-observer");
        assertFalse(Files.readString(observer.getBasedir().toPath().resolve("target/generated-sources/vernac/events/observer/adapter/inbound/event/Observe.java")).contains("@Service"));
        var application=project(root.resolve("events-app"),"events-app");application.setArtifacts(Set.of(artifact(publisherJar,"event-publisher"),artifact(observerJar,"event-observer")));
        Path appSource=application.getBasedir().toPath().resolve("src/main/vernac/app/model.vernac");Files.createDirectories(appSource.getParent());
        Files.writeString(appSource,"namespace app; import events.api.Name; import events.api.Publish; usecase Forward(Name) uses Publish behavior {execute {self.publish().execute(name);}}");
        for(boolean active:List.of(false,true)) {
            Files.writeString(application.getBasedir().toPath().resolve("vernac-application.vapplication"),"application app {activate example.publisher;"+(active?"activate example.observer;":"")+"}");
            Path classes=compile(application);ModuleEventProbe.reset();
            try(var code=new URLClassLoader(new URL[]{classes.toUri().toURL(),publisherJar.toUri().toURL(),observerJar.toUri().toURL()},getClass().getClassLoader());var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
                context.setClassLoader(code);context.registerBean("transactionManager",CountingTransactions.class);
                // Registry/transaction test only: real SQL startup is covered by ModuleMigrationPostgresTest.
                context.registerBean("dataSource",javax.sql.DataSource.class,()->new org.springframework.jdbc.datasource.DriverManagerDataSource());
                context.registerBean("jdbc",org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class,()->new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(context.getBean(javax.sql.DataSource.class)));
                context.registerBean("flywayInitializer",org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer.class,()->new org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer(org.flywaydb.core.Flyway.configure().dataSource(context.getBean(javax.sql.DataSource.class)).load()){@Override public void afterPropertiesSet(){}});
                context.register(code.loadClass("app.configuration.__VernacApplication"),code.loadClass("app.usecase.Forward"));context.refresh();
                Class<?> name=code.loadClass("events.api.domain.Name");Object value=name.getMethod("of",String.class).invoke(null,"fixture");
                Object forward=context.getBean(code.loadClass("app.usecase.Forward"));forward.getClass().getMethod("execute",name).invoke(forward,value);
                assertEquals(active?1:0,ModuleEventProbe.calls);
                assertEquals(active?2:1,context.getBeanNamesForType(org.vernac.runtime.events.EventHandler.class).length);
                var transactions=context.getBean(CountingTransactions.class);assertEquals(1,transactions.begins);assertEquals(1,transactions.commits);
                if(active) {
                    ModuleEventProbe.reject=true;
                    var rejected=assertThrows(java.lang.reflect.InvocationTargetException.class,()->forward.getClass().getMethod("execute",name).invoke(forward,value));
                    assertInstanceOf(org.vernac.runtime.DomainValidationException.class,rejected.getCause());
                    assertEquals(2,transactions.begins);assertEquals(1,transactions.commits);assertEquals(1,transactions.rollbacks);
                }
            } finally {ModuleEventProbe.reset();}
        }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    public static class ModuleMvcConfiguration { }

    @Test void moduleHttpRoutesAndTheirAdviceRequireActivationEvenUnderComponentScanning() throws Exception {
        var domain=project(root.resolve("http-domain"),"http-domain");
        Path model=domain.getBasedir().toPath().resolve("src/main/vernac/httpdomain/model.vernac");
        Files.createDirectories(model.getParent());
        Files.writeString(model,"""
            namespace httpdomain;
            value Title(String) validates {require(!self.string().isBlank(), "Private validation detail");};
            usecase Echo(Title) returns Title behavior {execute {return title;}}
            """);
        Files.writeString(domain.getBasedir().toPath().resolve(ModuleDefinition.FILE),"module example.httpdomain {export httpdomain.Title;export httpdomain.Echo;}");
        Path domainJar=jar(compile(domain),"http-domain");
        var web=project(root.resolve("http-entrypoints"),"http-entrypoints");
        web.setArtifacts(Set.of(artifact(domainJar,"http-domain")));
        Path source=web.getBasedir().toPath().resolve("src/main/vernac/httpentry/model.vernac");
        Files.createDirectories(source.getParent());
        Files.writeString(source,"""
            namespace httpentry;
            import httpdomain.Title;
            import httpdomain.Echo;
            adapter Api inbound http uses Echo echo {
                base "/module/echo";
                echo {POST "/";body Request;execute echo {title <- body.text;} respond 200 Response using output;}
                schema Request(String text);
                schema Response(String text);
                mapping output: Response <- Title {text <- source;}
                errors {
                    when invalid request then respond 400 "MODULE_REQUEST" "Supply text.";
                    when validation then respond 422 "MODULE_VALIDATION" "Text must not be blank.";
                    otherwise default;
                }
            }
            """);
        Path definition=web.getBasedir().toPath().resolve(ModuleDefinition.FILE);
        Files.writeString(definition,"module example.httpentry {export httpentry.Api;}");
        var exportFailure=assertThrows(Exception.class,()->compile(web));
        assertTrue(exportFailure.getMessage().contains("internal entrypoints"));
        Files.writeString(definition,"module example.httpentry {}");
        Path webClasses=compile(web),webJar=jar(webClasses,"http-entrypoints");
        var descriptor=ModuleArtifacts.read(List.of(webJar,domainJar)).stream().filter(m->m.module().equals("example.httpentry")).findFirst().orElseThrow();
        assertTrue(descriptor.exports().isEmpty());
        assertTrue(descriptor.components().stream().anyMatch(c->c.kind().equals("INBOUND_HTTP_ADAPTER")));
        assertTrue(descriptor.components().stream().anyMatch(c->c.kind().equals("INBOUND_HTTP_ADVICE")));
        for(boolean active:List.of(false,true)) {
            var app=project(root.resolve(active?"http-active":"http-inactive"),active?"http-active":"http-inactive");
            app.setArtifacts(Set.of(artifact(domainJar,"http-domain"),artifact(webJar,"http-entrypoints")));
            Path local=app.getBasedir().toPath().resolve("src/main/vernac/httpapp/model.vernac");
            Files.createDirectories(local.getParent());Files.writeString(local,"namespace httpapp; value Name(String);");
            Files.writeString(app.getBasedir().toPath().resolve("vernac-application.vapplication"),
                "application httpapp {activate "+(active?"example.httpentry":"example.httpdomain")+";}");
            Path classes=compile(app);
            try(var code=new URLClassLoader(new URL[]{classes.toUri().toURL(),webJar.toUri().toURL(),domainJar.toUri().toURL()},getClass().getClassLoader());
                var context=new org.springframework.web.context.support.AnnotationConfigWebApplicationContext()) {
                context.setClassLoader(code);context.setServletContext(new org.springframework.mock.web.MockServletContext());
                context.register(ModuleMvcConfiguration.class,code.loadClass("httpapp.configuration.__VernacApplication"));
                context.addBeanFactoryPostProcessor(factory->factory.registerSingleton("transactionManager",new CountingTransactions()));
                // An application scanning dependency packages must not bypass explicit activation.
                context.scan("httpentry.adapter.inbound.http");context.refresh();
                var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context).build();
                var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/module/echo")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"text\":\"Hello\"}");
                var response=mvc.perform(request).andReturn().getResponse();
                assertEquals(active?200:404,response.getStatus());
                assertEquals(active,context.containsBean("httpentry.Api"));
                assertEquals(active,context.containsBean("@http-errors:httpentry.Api"));
                if(active) {
                    assertEquals("Hello",new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString()).get("text").asText());
                    assertTrue(context.containsBean("httpdomain.Echo"),"Usecase module activates through the HTTP dependency");
                    var invalid=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/module/echo")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{}"))
                        .andReturn().getResponse();
                    assertEquals(400,invalid.getStatus());assertTrue(invalid.getContentAsString().contains("MODULE_REQUEST"));
                    var rejected=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/module/echo")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"text\":\"\"}"))
                        .andReturn().getResponse();
                    assertEquals(422,rejected.getStatus());assertTrue(rejected.getContentAsString().contains("MODULE_VALIDATION"));
                    assertFalse(rejected.getContentAsString().contains("Private validation detail"));
                    assertEquals(1,context.getBean(CountingTransactions.class).commits);
                }
            }
            assertFalse(Files.exists(app.getBasedir().toPath().resolve("target/generated-sources/vernac/httpentry")));
        }
    }

    public static class CountingTransactions extends org.springframework.transaction.support.AbstractPlatformTransactionManager {
        private final ThreadLocal<State> current=new ThreadLocal<>();int begins,commits,rollbacks;
        private static class State implements org.springframework.transaction.support.SmartTransactionObject {
            boolean active,rollbackOnly;
            public boolean isRollbackOnly(){return rollbackOnly;}
            public void flush() { }
        }
        @Override protected Object doGetTransaction(){var existing=current.get();return existing==null?new State():existing;}
        @Override protected boolean isExistingTransaction(Object transaction){return ((State)transaction).active;}
        @Override protected void doBegin(Object transaction,org.springframework.transaction.TransactionDefinition definition){((State)transaction).active=true;current.set((State)transaction);begins++;}
        @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){commits++;}
        @Override protected void doSetRollbackOnly(org.springframework.transaction.support.DefaultTransactionStatus status){((State)status.getTransaction()).rollbackOnly=true;}
        @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status){rollbacks++;}
        @Override protected void doCleanupAfterCompletion(Object transaction){current.remove();}
    }

    @Test void persistentJarPublishesOwnedSchemaAndConsumerUsesItsCompiledContracts() throws Exception {
        var producer=project(root.resolve("persistent"),"persistent");
        Path sources=producer.getBasedir().toPath().resolve("src/main/vernac"),source=sources.resolve("billing/model.vernac");Files.createDirectories(source.getParent());
        Files.writeString(source,"namespace billing; id InvoiceId; value Reference(String); aggregate Invoice[InvoiceId](Reference) list Invoices; repository InvoiceRepository for Invoice {find offset Invoices all() order by createdAt desc, id counting;}");
        Files.writeString(producer.getBasedir().toPath().resolve(ModuleDefinition.FILE),"module example.billing {export billing.InvoiceId;export billing.Reference;export billing.Invoice;export billing.Invoices;export billing.InvoiceRepository;}");
        assertThrows(Exception.class,()->compile(producer),"Publishing cannot omit the migration history");
        var compiler=new org.vernac.compiler.pipeline.VernacCompiler();var expected=new org.vernac.compiler.persistence.SchemaBuilder(compiler.analyzeProject(sources),org.vernac.compiler.persistence.SchemaModel.empty()).build();
        Path history=producer.getBasedir().toPath().resolve("src/main/vernac-schema/history"),sql=producer.getBasedir().toPath().resolve("src/main/vernac-schema/migration");
        new org.vernac.compiler.persistence.SchemaHistory(history,sql).append("20261013210000000","initial_billing",org.vernac.compiler.persistence.SchemaModel.empty(),expected,new org.vernac.compiler.persistence.MigrationPlanner().plan(org.vernac.compiler.persistence.SchemaModel.empty(),expected,org.vernac.compiler.persistence.MigrationPlanner.Options.safe()));
        Path producerClasses=compile(producer),published=jar(producerClasses,"persistent");
        assertFalse(Files.readString(producer.getBasedir().toPath().resolve("target/generated-sources/vernac/billing/adapter/outbound/jdbc/JdbcInvoiceRepository.java")).contains("@Repository"));
        assertFalse(Files.exists(producerClasses.resolve("db/migration")));
        assertTrue(ModuleArtifacts.read(List.of(published)).getFirst().persistent());
        var consumer=project(root.resolve("consumer-contracts"),"consumer-contracts");consumer.setArtifacts(Set.of(artifact(published,"persistent")));
        Path local=consumer.getBasedir().toPath().resolve("src/main/vernac/app/model.vernac");Files.createDirectories(local.getParent());
        Files.writeString(local,"namespace app; import billing.Reference; import billing.InvoiceId; import billing.InvoiceRepository; value Snapshot(Reference); usecase Lookup(InvoiceId) returns Reference uses InvoiceRepository invoiceRepository behavior {execute {return self.invoiceRepository().byId(invoiceId).reference();}}");
        Files.writeString(consumer.getBasedir().toPath().resolve(ModuleDefinition.FILE),"module example.consumer {export app.Snapshot;export app.Lookup;}");
        Path classes=compile(consumer);
        assertFalse(Files.exists(consumer.getBasedir().toPath().resolve("target/generated-sources/vernac/billing")));
        try(var code=new URLClassLoader(new URL[]{classes.toUri().toURL(),published.toUri().toURL()},getClass().getClassLoader())) {
            var reference=code.loadClass("billing.domain.Reference");Object ref=reference.getMethod("of",String.class).invoke(null,"INV-1");
            var invoice=code.loadClass("billing.domain.Invoice");Object value=invoice.getMethod("create",reference).invoke(null,ref);assertSame(ref,invoice.getMethod("reference").invoke(value));
            var repository=code.loadClass("billing.domain.InvoiceRepository");assertEquals("CountedOffsetQuery",repository.getMethod("all").getReturnType().getSimpleName());
            assertTrue(Arrays.stream(code.loadClass("app.usecase.Lookup").getConstructors()).anyMatch(c->Arrays.asList(c.getParameterTypes()).contains(repository)));
        }
        Files.delete(consumer.getBasedir().toPath().resolve(ModuleDefinition.FILE));
        Files.writeString(consumer.getBasedir().toPath().resolve("vernac-application.vapplication"),"application app {activate example.billing;}");
        Path applicationClasses=compile(consumer);assertTrue(Files.isRegularFile(applicationClasses.resolve("META-INF/vernac/applications/app/schema.json")));
        Files.writeString(sql.resolve("V20261013210000000__initial_billing.sql"),"-- changed after packaging");
        // The previously packaged artifact remains valid; a tampered packaged resource is rejected.
        Map<String,byte[]> bytes=new TreeMap<>();try(var artifact=new JarFile(published.toFile())){for(var entry:artifact.stream().filter(e->!e.isDirectory()).toList())try(var in=artifact.getInputStream(entry)){bytes.put(entry.getName(),in.readAllBytes());}}
        String resource=ModulePersistence.directory("example.billing")+"migration/V20261013210000000__initial_billing.sql";bytes.put(resource,"select 1;".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path altered=root.resolve("altered.jar");try(var out=new JarOutputStream(Files.newOutputStream(altered))){for(var entry:bytes.entrySet()){out.putNextEntry(new JarEntry(entry.getKey()));out.write(entry.getValue());out.closeEntry();}}
        assertTrue(assertThrows(java.io.IOException.class,()->ModuleArtifacts.read(List.of(altered))).getMessage().contains("Missing/changed packaged module SQL"));
    }

}
