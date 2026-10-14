// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import java.nio.file.*;
import java.net.URLClassLoader;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.boot.autoconfigure.flyway.*;
import org.flywaydb.core.Flyway;
import org.vernac.compiler.modules.*;
import org.vernac.compiler.persistence.*;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.application.ApplicationBindings;
import org.vernac.runtime.application.*;
import org.vernac.runtime.jdbc.modules.ModuleMigrationStrategy;
import org.vernac.runtime.persistence.SchemaDescription;
import static org.junit.jupiter.api.Assertions.*;

class ModuleMigrationPostgresTest {
    @TempDir Path root;
    private record Published(ModuleDescriptor descriptor,SchemaModel schema,Path sql) { }
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement public static class Transactions { }
    private final List<Path> javaSources=new ArrayList<>();private final List<ModuleDescriptor> descriptors=new ArrayList<>();
    private Path resources;
    private boolean upgraded,durable;
    private Published publish(String folder,String text,String exports) throws Exception {
        Path sourceRoot=root.resolve(folder+"/src/main/vernac"),source=sourceRoot.resolve("db/"+folder+"/model.vernac");Files.createDirectories(source.getParent());Files.writeString(source,text);
        if(durable && folder.equals("required"))text=text.replace("event Started(RootId);","recorded event Started(RootId);\ndeferred listener AuditStarted on Started uses RootRepository behavior {execute {self.rootRepository().byId(event.rootId());}} ");
        if(durable && folder.equals("dependent"))text=text.replace("namespace db.dependent;","namespace db.dependent; import db.required.Started;")+"\ndeferred listener DeliverStarted on Started uses ReceiptRepository behavior {java imports {org.vernac.maven.ModuleDeferredEventProbe;} execute {self.receiptRepository().save(Receipt.create(Name.of(\"delivered\")));ModuleDeferredEventProbe.afterWrite();}}";
        if (upgraded) text=text.replace("value Name(String text, String? alias);", "value Name(String text, String? alias, String origin);");
        Files.writeString(source,text);
        Files.writeString(root.resolve(folder+"/vernac-module.vmodule"),"module example."+folder+" {"+exports+"}");
        var compiler=new VernacCompiler();var resolved=compiler.analyzeProject(sourceRoot,descriptors);var generated=compiler.generate(resolved,SchemaModel.empty(),Map.of());
        var files=ApplicationBindings.generate(new org.vernac.compiler.application.ApplicationDefinition("internal",List.of(),List.of()),resolved,descriptors,generated.generatedFiles(),true).files();
        Path java=root.resolve(folder+"/java");for(var file:files)file.writeTo(java);javaSources.add(java);
        var descriptor=ModuleContracts.publish(ModuleDefinition.parse(root.resolve(folder+"/vernac-module.vmodule"),"module example."+folder+" {"+exports+"}"),resolved,files,"example",folder,"1",descriptors);
        var model=new SchemaBuilder(resolved,SchemaModel.empty()).build();Path history=root.resolve(folder+"/history"),sql=root.resolve(folder+"/migration");
        appendHistory(history,sql,model);
        bundle(folder,model,history,sql);descriptors.add(descriptor);return new Published(descriptor,model,sql);
    }
    private void bundle(String module,SchemaModel model,Path history,Path sql) throws Exception {
        var bundle=ModulePersistence.prepare("example."+module,model,history,sql);ModulePersistence.verify(bundle.descriptor(),bundle.resources());
        for(var entry:bundle.resources().entrySet()){Path target=resources.resolve(entry.getKey());Files.createDirectories(target.getParent());Files.write(target,entry.getValue());}
    }
    private void appendHistory(Path history,Path sql,SchemaModel next) throws Exception {
        var chain=new SchemaHistory(history,sql);var previous=chain.read();
        Map<String,String> backfills=new TreeMap<>();
        if(upgraded)for(var table:next.tables())for(var column:table.columns())
            if(column.name().endsWith(".origin"))backfills.put(table.identity()+"/"+column.name(),"'LEGACY'");
        chain.append(upgraded?"20261013220100000":"20261013210000000",upgraded?"add_origin":"initial",previous,next,
            new MigrationPlanner().plan(previous,next,new MigrationPlanner.Options(false,backfills,Map.of())));
    }
    private List<Published> fixtures() throws Exception {
        resources=Files.createDirectories(root.resolve("classes"));
        var first=publish("required","""
            namespace db.required;
            id RootId; id ChildId; value Name(String text, String? alias);
            entity Child[ChildId](Name) list Children;
            aggregate Root[RootId](Name, Children);
            repository RootRepository for Root {}
            event Started(RootId);
            synchronous listener CheckStarted on Started uses RootRepository behavior {
                execute {
                    if (self.rootRepository().byId(event.rootId()).name().text().equals("reject"))
                        throw new org.vernac.runtime.DomainValidationException("Synchronous receiver rejected the root");
                }
            }
            usecase Start(Name) returns RootId uses RootRepository rootRepository behavior {
                execute {var root=Root.create(name,Children.empty());self.rootRepository().save(root);self.emit(Started.create(root.id()));return root.id();}
            }
            ""","export db.required.RootId;export db.required.Name;export db.required.Start;export db.required.Started;");
        var second=publish("dependent","""
            namespace db.dependent;
            import db.required.Name; import db.required.RootId; import db.required.Start;
            id ReceiptId; aggregate Receipt[ReceiptId](Name);
            repository ReceiptRepository for Receipt {}
            usecase Submit(Name) returns RootId uses Start start, ReceiptRepository receiptRepository behavior {
                execute {var id=self.start().execute(name);self.receiptRepository().save(Receipt.create(name));return id;}
            }
            usecase Reject(Name) uses Start start, ReceiptRepository receiptRepository behavior {
                execute {self.start().execute(name);self.receiptRepository().save(Receipt.create(name));throw new IllegalStateException("fixture rollback");}
            }
            ""","export db.dependent.Submit;export db.dependent.Reject;");
        ModuleContracts.checkDependencies(descriptors);
        Path appSource=root.resolve("app/src/main/vernac/db/application/model.vernac");Files.createDirectories(appSource.getParent());
        Files.writeString(appSource,"namespace db.application; import db.required.Name; id ApplicationId; aggregate Application[ApplicationId](Name); repository ApplicationRepository for Application {}");
        var compiler=new VernacCompiler();var app=new SchemaBuilder(compiler.analyzeProject(root.resolve("app/src/main/vernac"),descriptors),SchemaModel.empty(),Map.of(),new SchemaBuilder.Options(false,durable)).build();
        appendHistory(root.resolve("app/history"),resources.resolve("db/migration"),app);
        ModuleDescriptor.write(resources.resolve("application-schema.json"),Map.of("schema",app,"ownedTables",app.tables().stream().map(t->SqlNames.physical(t.namespace())+"/"+SqlNames.physical(t.name())).toList()));
        var arguments=new ArrayList<>(List.of("--release","21","-proc:none","-classpath",System.getProperty("java.class.path"),"-d",resources.toString()));
        for(Path java:javaSources)try(var paths=Files.walk(java)){paths.filter(p->p.toString().endsWith(".java")).map(Path::toString).forEach(arguments::add);}
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,arguments.toArray(String[]::new)));
        SchemaDescription runtime=SchemaModel.JSON.readValue(first.schema().json(),SchemaDescription.class);assertEquals(first.schema().fingerprint(),ModuleMigrationStrategy.fingerprint(runtime));
        return List.of(first,second);
    }
    private AnnotationConfigApplicationContext context(CountingSource source,URLClassLoader code) throws Exception {
        var context=new AnnotationConfigApplicationContext();context.setClassLoader(code);context.register(Transactions.class);
        context.registerBean("dataSource",javax.sql.DataSource.class,()->source);context.registerBean("transactionManager",DataSourceTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean("jdbc",NamedParameterJdbcTemplate.class,()->new NamedParameterJdbcTemplate(source));
        List<ActivationPlan.Node> nodes=new ArrayList<>();Map<String,Class<?>> functions=new TreeMap<>();
        for(var descriptor:descriptors)for(var component:descriptor.components()){var node=new ActivationPlan.Node(component.identity(),descriptor.module(),component.kind(),component.javaName(),component.dependencies(),descriptor.persistent());nodes.add(node);functions.put(node.identity(),code.loadClass(node.javaName()));}
        context.addBeanFactoryPostProcessor(new AdapterBindings(context.getEnvironment(),List.of(),Map.of(),nodes,List.of("example.dependent"),List.of(),functions,"application-schema.json"));
        context.registerBean("flywayInitializer",FlywayMigrationInitializer.class,()->new FlywayMigrationInitializer(Flyway.configure(code).dataSource(source).locations("classpath:db/migration").load(),context.getBean(AdapterBindings.STRATEGY,FlywayMigrationStrategy.class)));
        return context;
    }
    private interface Exercise {void run(CountingSource source) throws Exception;}
    private void database(Exercise exercise) throws Exception {
        String url=System.getenv("VERNAC_PG_TEST_URL");Properties credentials=new Properties();
        if(System.getenv("VERNAC_PG_TEST_USER")!=null)credentials.setProperty("user",System.getenv("VERNAC_PG_TEST_USER"));if(System.getenv("VERNAC_PG_TEST_PASSWORD")!=null)credentials.setProperty("password",System.getenv("VERNAC_PG_TEST_PASSWORD"));
        String database="vernac_modules_"+UUID.randomUUID().toString().replace("-","");
        try(var admin=DriverManager.getConnection(url,credentials)) {
            try(var statement=admin.createStatement()){statement.execute("CREATE DATABASE "+database);}
            try{exercise.run(new CountingSource(SchemaCheckMojo.databaseUrl(url,database),credentials));}
            finally{try(var statement=admin.createStatement()){statement.execute("DROP DATABASE "+database+" WITH (FORCE)");}}
        }
    }
    private static final class CountingSource extends AbstractDataSource {
        private final String url;private final Properties credentials;private String searchPath;final AtomicInteger transactions=new AtomicInteger();
        CountingSource(String url,Properties credentials){this.url=url;this.credentials=credentials;}
        public Connection getConnection() throws SQLException {return wrap(DriverManager.getConnection(url,credentials));}
        public Connection getConnection(String user,String password) throws SQLException{return wrap(DriverManager.getConnection(url,user,password));}
        private Connection wrap(Connection connection) throws SQLException {if(searchPath!=null)try(var statement=connection.createStatement()){statement.execute("SET search_path TO "+searchPath);}return (Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{if(m.getName().equals("setAutoCommit")&&Boolean.FALSE.equals(a[0]))transactions.incrementAndGet();try{return m.invoke(connection,a);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}});}
    }
    private static long count(CountingSource source,String sql) throws Exception {try(var c=source.getConnection();var s=c.createStatement();var rows=s.executeQuery(sql)){rows.next();return rows.getLong(1);}}
    @Test void fixtureModulesCompileAndShareTheRuntimeFingerprintContract() throws Exception {
        var models=fixtures();assertEquals(2,models.size());
        assertTrue(Files.isRegularFile(resources.resolve("db/dependent/usecase/Submit.class")));
        assertTrue(Files.isRegularFile(resources.resolve("application-schema.json")));
        upgraded=true;descriptors.clear();javaSources.clear();fixtures();
        assertTrue(Files.readString(resources.resolve("db/migration/V20261013220100000__add_origin.sql")).contains("LEGACY"));
    }
    @Test void durableFixturesKeepPayloadAndSharedDeliveryOwnershipSeparate() throws Exception {
        durable=true;var modules=fixtures();
        assertTrue(modules.getFirst().schema().tables().stream().anyMatch(t->t.name().equals("@event:Started")));
        for(var module:modules)assertTrue(module.schema().tables().stream().noneMatch(t->t.identity().equals("vernac/@delivery")));
        assertTrue(Files.readString(resources.resolve("application-schema.json")).contains("@delivery"));
        assertTrue(modules.getFirst().descriptor().exports().stream().filter(d->d.kind().equals("EVENT")).allMatch(d->"true".equals(d.javaMembers().get("@recorded"))));
    }
    @Test @EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
    void deferredModuleReceiversResumeAfterRestartAndRetryWithoutDuplicatingCommittedWrites() throws Exception {
        durable=true;ModuleDeferredEventProbe.calls.set(0);fixtures();
        database(source->{
            // Reproduce a CI login named vernac even when the test server uses another login.
            // Before schema creation public is current; afterwards vernac would become current.
            source.searchPath="vernac, public";
            try(var code=new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()},getClass().getClassLoader());var context=context(source,code)) {
                context.refresh();
                assertEquals(1,count(source,"SELECT count(*) FROM public.flyway_schema_history WHERE success AND type='SQL'"));
                assertEquals(0,count(source,"SELECT count(*) FROM information_schema.tables WHERE table_schema='vernac' AND table_name='flyway_schema_history'"));
                var name=code.loadClass("db.required.domain.Name");
                Object submit=context.getBean("db.dependent.Submit");
                submit.getClass().getMethod("execute",name).invoke(submit,name.getMethod("of",String.class).invoke(null,"durable"));
                assertEquals(2,count(source,"SELECT count(*) FROM \"vernac\".\"@delivery\" WHERE status='PENDING'"));
                assertEquals(1,count(source,"SELECT count(*) FROM \"db.required\".\"@event:Started\""));
                assertEquals(0,ModuleDeferredEventProbe.calls.get());
            }
            // No background worker was started by this plain Spring fixture. Pending jobs survive restart.
            try(var code=new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()},getClass().getClassLoader());var context=context(source,code)) {
                context.refresh();
                var worker=new org.vernac.runtime.jdbc.JdbcEventWorker(context.getBean(NamedParameterJdbcTemplate.class),context.getBean(DataSourceTransactionManager.class),
                    ()->List.copyOf(context.getBeansOfType(org.vernac.runtime.events.EventHandler.class).values()),org.vernac.runtime.jdbc.EventDeliveryOptions.defaults());
                for(int i=0;i<6 && worker.runOnce();i++) {
                    // Expedite the deliberate retry, rather than sleeping through production backoff.
                    context.getBean(NamedParameterJdbcTemplate.class).update("UPDATE \"vernac\".\"@delivery\" SET \"nextAttemptAt\"=clock_timestamp() WHERE status='PENDING'",Map.of());
                }
                assertEquals(2,ModuleDeferredEventProbe.calls.get());
                assertEquals(2,count(source,"SELECT count(*) FROM \"vernac\".\"@delivery\" WHERE status='SUCCEEDED'"));
                assertEquals(1,count(source,"SELECT count(*) FROM \"vernac\".\"@delivery\" WHERE attempts=2"));
                assertEquals(1,count(source,"SELECT count(*) FROM \"db.dependent\".\"Receipt\" WHERE \"name.text\"='delivered'"));
                assertEquals(1,count(source,"SELECT count(*) FROM \"db.required\".\"@event:Started\""),"Recorded event remains as a protocol row");
                assertFalse(worker.runOnce());
            }
        });
    }
    @Test @EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
    void freshStartupRestartSeparateHistoriesAndRequiredTransactions() throws Exception {
        var modules=fixtures();database(source->{
            for(int restart=0;restart<2;restart++)try(var code=new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()},getClass().getClassLoader());var context=context(source,code)) {
                context.refresh();
                // Flyway also records schema creation (type SCHEMA); count the actual SQL migration.
                for(var module:modules)assertEquals(1,count(source,"SELECT count(*) FROM "+SqlNames.quote(ModulePersistence.historySchema(module.descriptor().module()))+".flyway_schema_history WHERE success AND type = 'SQL' AND version = '20261013210000000'"));
                assertEquals(1,count(source,"SELECT count(*) FROM public.flyway_schema_history WHERE success AND type = 'SQL' AND version = '20261013210000000'"));
                if(restart==0){source.transactions.set(0);var name=code.loadClass("db.required.domain.Name");Object value=name.getMethod("of",String.class).invoke(null,"module fixture");
                    Object submit=context.getBean("db.dependent.Submit");submit.getClass().getMethod("execute",name).invoke(submit,value);assertEquals(1,source.transactions.get());
                    assertEquals(1,count(source,"SELECT count(*) FROM \"db.required\".\"Root\""));assertEquals(1,count(source,"SELECT count(*) FROM \"db.dependent\".\"Receipt\""));
                    Object reject=context.getBean("db.dependent.Reject");assertThrows(java.lang.reflect.InvocationTargetException.class,()->reject.getClass().getMethod("execute",name).invoke(reject,value));
                    assertEquals(2,source.transactions.get());assertEquals(1,count(source,"SELECT count(*) FROM \"db.required\".\"Root\""));assertEquals(1,count(source,"SELECT count(*) FROM \"db.dependent\".\"Receipt\""));}
            }
        });
    }
    @Test @EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
    void failedLaterHistoryStopsApplicationAndRetryRetainsEarlierSuccess() throws Exception {
        var modules=fixtures();var second=modules.get(1);Path file=second.sql().resolve("V20261013210000000__initial.sql");String reviewed=Files.readString(file);Files.writeString(file,reviewed+"\nSELECT 1/0;\n");bundle("dependent",second.schema(),root.resolve("dependent/history"),second.sql());
        database(source->{
            try(var code=new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()},getClass().getClassLoader());var context=context(source,code)){assertThrows(RuntimeException.class,context::refresh);}
            assertEquals(1,count(source,"SELECT count(*) FROM "+SqlNames.quote(ModulePersistence.historySchema("example.required"))+".flyway_schema_history WHERE success AND type = 'SQL' AND version = '20261013210000000'"));
            assertEquals(0,count(source,"SELECT count(*) FROM information_schema.tables WHERE table_schema='db.application' AND table_name='Application'"));
            Files.writeString(file,reviewed);bundle("dependent",second.schema(),root.resolve("dependent/history"),second.sql());
            try(var code=new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()},getClass().getClassLoader());var context=context(source,code)){context.refresh();assertEquals(1,count(source,"SELECT count(*) FROM public.flyway_schema_history WHERE success AND type = 'SQL' AND version = '20261013210000000'"));}
        });
    }
    @Test @EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
    void upgradesModuleAndApplicationCopiesOfAnExternalValueWithExistingData() throws Exception {
        fixtures();var oldDescriptors=List.copyOf(descriptors);Path oldResources=root.resolve("version-one");
        try(var files=Files.walk(resources)) {for(Path file:files.filter(Files::isRegularFile).toList()) {
            Path copy=oldResources.resolve(resources.relativize(file));Files.createDirectories(copy.getParent());Files.copy(file,copy);
        }}
        database(source->{
            try(var code=new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()},getClass().getClassLoader());var context=context(source,code)) {
                context.refresh();var name=code.loadClass("db.required.domain.Name");
                context.getBean("db.dependent.Submit").getClass().getMethod("execute",name)
                    .invoke(context.getBean("db.dependent.Submit"),name.getMethod("of",String.class).invoke(null,"before upgrade"));
            }
            // Both roots have the same persisted columns. Seed a local application copy.
            try(var c=source.getConnection();var statement=c.createStatement()) {
                String columns=List.of("@createdAt","@nanoRemainder:@createdAt","@nanoRemainder:@updatedAt",
                    "@updatedAt","@version","id","name.alias","name.text").stream().map(SqlNames::quote).collect(java.util.stream.Collectors.joining(","));
                statement.execute("INSERT INTO \"db.application\".\"Application\" ("+columns+") SELECT "+columns+" FROM \"db.required\".\"Root\"");
            }
            upgraded=true;descriptors.clear();javaSources.clear();var modules=fixtures();
            try(var code=new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()},getClass().getClassLoader());var context=context(source,code)) {
                context.refresh();
                for(String table:List.of("\"db.required\".\"Root\"","\"db.dependent\".\"Receipt\"","\"db.application\".\"Application\""))
                    assertEquals(1,count(source,"SELECT count(*) FROM "+table+" WHERE \"name.origin\"='LEGACY' AND \"name.text\"='before upgrade'"));
                for(var module:modules)assertEquals(2,count(source,"SELECT count(*) FROM "+SqlNames.quote(ModulePersistence.historySchema(module.descriptor().module()))+".flyway_schema_history WHERE success AND type='SQL'"));
                assertEquals(2,count(source,"SELECT count(*) FROM public.flyway_schema_history WHERE success AND type='SQL'"));
                var name=code.loadClass("db.required.domain.Name");
                context.getBean("db.dependent.Submit").getClass().getMethod("execute",name)
                    .invoke(context.getBean("db.dependent.Submit"),name.getMethod("of",String.class,String.class).invoke(null,"after upgrade","CURRENT"));
                assertEquals(1,count(source,"SELECT count(*) FROM \"db.required\".\"Root\" WHERE \"name.origin\"='CURRENT'"));
            }
            resources=oldResources;descriptors.clear();descriptors.addAll(oldDescriptors);
            try(var code=new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()},getClass().getClassLoader());var context=context(source,code)) {
                assertThrows(RuntimeException.class,context::refresh,"An older artifact must not start against upgraded histories");
            }
        });
    }
    @Test @EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
    void synchronousModuleListenerFailureRollsBackTheCallingTransaction() throws Exception {
        fixtures();database(source->{
            try(var code=new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()},getClass().getClassLoader());var context=context(source,code)) {
                context.refresh();var name=code.loadClass("db.required.domain.Name");
                var submit=context.getBean("db.dependent.Submit");
                var error=assertThrows(java.lang.reflect.InvocationTargetException.class,()->submit.getClass().getMethod("execute",name)
                    .invoke(submit,name.getMethod("of",String.class).invoke(null,"reject")));
                assertInstanceOf(org.vernac.runtime.DomainValidationException.class,error.getCause());
                assertEquals(0,count(source,"SELECT count(*) FROM \"db.required\".\"Root\""));
                assertEquals(0,count(source,"SELECT count(*) FROM \"db.dependent\".\"Receipt\""));
                assertEquals(0,count(source,"SELECT count(*) FROM information_schema.tables WHERE table_schema='vernac' AND table_name='@delivery'"));
            }
        });
    }
    @Test @EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
    void manualOwnedSchemaDriftBlocksRestart() throws Exception {
        fixtures();database(source->{
            try(var code=new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()},getClass().getClassLoader());var context=context(source,code)){context.refresh();}
            try(var c=source.getConnection();var s=c.createStatement()){s.execute("ALTER TABLE \"db.required\".\"Root\" ADD COLUMN unexpected TEXT");}
            try(var code=new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()},getClass().getClassLoader());var context=context(source,code)){assertThrows(RuntimeException.class,context::refresh);}
        });
    }
}
