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

/** Shared compiled module fixtures; tests choose global or tenant persistence explicitly. */
abstract class ModuleMigrationFixture {
    @TempDir Path root;
    protected record Published(ModuleDescriptor descriptor,SchemaModel schema,Path sql) { }
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement public static class Transactions { }
    protected final List<Path> javaSources=new ArrayList<>();protected final List<ModuleDescriptor> descriptors=new ArrayList<>();
    protected Path resources;
    protected boolean upgraded,durable,tenant,parallel,unique;
    protected Published publish(String folder,String text,String exports) throws Exception {
        Path sourceRoot=root.resolve(folder+"/src/main/vernac"),source=sourceRoot.resolve("db/"+folder+"/model.vernac");Files.createDirectories(source.getParent());Files.writeString(source,text);
        if(durable && folder.equals("required"))text=text.replace("event Started(RootId);","recorded event Started(RootId);\ndeferred listener AuditStarted on Started uses RootRepository behavior {execute {self.rootRepository().byId(event.rootId());}} ");
        if(durable && folder.equals("dependent"))text=text.replace("namespace db.dependent;","namespace db.dependent; import db.required.Started;")+"\ndeferred listener DeliverStarted on Started uses ReceiptRepository behavior {java imports {org.vernac.maven.ModuleDeferredEventProbe;} execute {self.receiptRepository().save(Receipt.create(Name.of(\"delivered\")));ModuleDeferredEventProbe.afterWrite();}}";
        if (upgraded) text=text.replace("value Name(String text, String? alias);", "value Name(String text, String? alias, String origin);");
        if (unique) text = text.replace("value Name(String text, String? alias);", "value Name(String text);")
                .replace("aggregate Root[RootId](Name,", "aggregate Root[RootId](unique Name,");
        if (tenant) text = text.replace("aggregate ", "tenant aggregate ");
        if (parallel && folder.equals("required")) text = text.replace("self.rootRepository().save(root);",
                "self.rootRepository().save(root);org.vernac.maven.TenantModuleTransactionProbe.reached();");
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
    protected void bundle(String module,SchemaModel model,Path history,Path sql) throws Exception {
        var bundle=ModulePersistence.prepare("example."+module,model,history,sql);ModulePersistence.verify(bundle.descriptor(),bundle.resources());
        for(var entry:bundle.resources().entrySet()){Path target=resources.resolve(entry.getKey());Files.createDirectories(target.getParent());Files.write(target,entry.getValue());}
    }
    protected void appendHistory(Path history,Path sql,SchemaModel next) throws Exception {
        var chain=new SchemaHistory(history,sql);var previous=chain.read();
        Map<String,String> backfills=new TreeMap<>();
        if(upgraded)for(var table:next.tables())for(var column:table.columns())
            if(column.name().endsWith(".origin"))backfills.put(table.identity()+"/"+column.name(),"'LEGACY'");
        chain.append(upgraded?"20261013220100000":"20261013210000000",upgraded?"add_origin":"initial",previous,next,
            new MigrationPlanner().plan(previous,next,new MigrationPlanner.Options(false,backfills,Map.of())));
    }
    protected List<Published> fixtures() throws Exception {
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
        prepareApplication();
        compileFixtures();
        SchemaDescription runtime = SchemaModel.JSON.readValue(first.schema().json(), SchemaDescription.class);
        assertEquals(first.schema().fingerprint(), ModuleMigrationStrategy.fingerprint(runtime));
        return List.of(first,second);
    }
    private void prepareApplication() throws Exception {
        Path sourceRoot = root.resolve("app/src/main/vernac");
        Path source = sourceRoot.resolve("db/application/model.vernac"); Files.createDirectories(source.getParent());
        String imports = tenant ? "import db.required.RootId; import db.dependent.Submit; import db.dependent.Reject; " : "";
        String model = "namespace db.application; " + imports + "import db.required.Name; id ApplicationId; "
                + "aggregate Application[ApplicationId](Name); repository ApplicationRepository for Application {}";
        if (tenant) model = model.replace("aggregate ", "tenant aggregate ") + """
                usecase Purchase(Name) returns RootId uses Submit submit, ApplicationRepository applicationRepository behavior {
                    execute {
                        var id = self.submit().execute(name);
                        self.applicationRepository().save(Application.create(name));
                        return id;
                    }
                }
                usecase RejectPurchase(Name) uses Reject reject, ApplicationRepository applicationRepository behavior {
                    execute {
                        self.applicationRepository().save(Application.create(name));
                        self.reject().execute(name);
                    }
                }
                """;
        Files.writeString(source, model);
        var compiler = new VernacCompiler(); var resolved = compiler.analyzeProject(sourceRoot, descriptors);
        var options = new SchemaBuilder.Options(false, durable);
        var schema = new SchemaBuilder(resolved, SchemaModel.empty(), Map.of(), options).build();
        appendHistory(root.resolve("app/history"), resources.resolve("db/migration"), schema);
        ModuleDescriptor.write(resources.resolve("application-schema.json"), Map.of("schema", schema,
                "ownedTables", schema.tables().stream().map(t -> SqlNames.physical(t.namespace()) + "/" + SqlNames.physical(t.name())).toList()));
        if (tenant) {
            Path java = root.resolve("app/java");
            var definition = org.vernac.compiler.application.ApplicationDefinition.parse(root.resolve("app/vernac-application.vapplication"),
                    "application example.tenant.fixture {activate example.dependent;}");
            var generated = compiler.generate(resolved, SchemaModel.empty(), Map.of(), options);
            var application = ApplicationBindings.generate(definition, resolved, descriptors, generated.generatedFiles(), false);
            for (var file : application.files()) file.writeTo(java);
            ModuleDescriptor.write(resources.resolve(ApplicationBindings.schemaResource(definition.name())), Map.of("schema", schema,
                    "ownedTables", schema.tables().stream().map(t -> SqlNames.physical(t.namespace()) + "/" + SqlNames.physical(t.name())).toList()));
            javaSources.add(java);
        }
    }

    private void compileFixtures() throws Exception {
        var arguments = new ArrayList<>(List.of("--release", "21", "-proc:none", "-classpath", System.getProperty("java.class.path"), "-d", resources.toString()));
        for (Path java : javaSources) try (var paths = Files.walk(java)) {
            paths.filter(p -> p.toString().endsWith(".java")).map(Path::toString).forEach(arguments::add);
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)));
    }

    private void registerApplication(org.springframework.context.ConfigurableApplicationContext context, ClassLoader code) throws Exception {
        if (!tenant) return;
        var registry = (org.springframework.beans.factory.support.BeanDefinitionRegistry) context.getBeanFactory();
        new org.springframework.context.annotation.AnnotatedBeanDefinitionReader(registry)
                .register(code.loadClass("example.tenant.fixture.configuration.__VernacApplication"));
        for (String name : List.of("adapter.outbound.jdbc.JdbcApplicationRepository", "usecase.Purchase", "usecase.RejectPurchase")) {
            var definition = new org.springframework.beans.factory.support.RootBeanDefinition(code.loadClass("db.application." + name));
            definition.setAutowireMode(org.springframework.beans.factory.support.AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
            ((org.springframework.beans.factory.support.BeanDefinitionRegistry) context.getBeanFactory())
                    .registerBeanDefinition("db.application." + name, definition);
        }
    }

    protected URLClassLoader packagedCode() throws Exception {
        Path artifacts = Files.createTempDirectory(root, "jars-");
        List<Path> jars = new ArrayList<>();
        for (String folder : List.of("required", "dependent", "application")) {
            Path jar = artifacts.resolve(folder + ".jar");
            try (var output = new java.util.jar.JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(resources)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    String name = resources.relativize(file).toString().replace('\\', '/');
                    if (belongsToArtifact(folder, name)) {
                        output.putNextEntry(new java.util.jar.JarEntry(name)); Files.copy(file, output); output.closeEntry();
                    }
                }
                if (!folder.equals("application")) {
                    output.putNextEntry(new java.util.jar.JarEntry(ModuleDescriptor.RESOURCE));
                    output.write(ModuleDescriptor.JSON.writeValueAsBytes(descriptors.stream()
                            .filter(d -> d.module().equals("example." + folder)).findFirst().orElseThrow()));
                    output.closeEntry();
                }
            }
            jars.add(jar);
        }
        assertEquals(2, ModuleArtifacts.read(jars).size(), "Both persistent module JAR bundles must pass normal compiler ingestion");
        var urls = new ArrayList<java.net.URL>(); for (Path jar : jars) urls.add(jar.toUri().toURL());
        return new URLClassLoader(urls.toArray(java.net.URL[]::new), getClass().getClassLoader());
    }

    private boolean belongsToArtifact(String folder, String path) {
        if (!folder.equals("application")) return path.startsWith("db/" + folder + "/")
                || path.startsWith(ModulePersistence.directory("example." + folder));
        return path.startsWith("db/application/") || path.startsWith("db/migration/")
                || path.startsWith("example/tenant/fixture/") || path.startsWith("META-INF/vernac/applications/")
                || path.equals("application-schema.json");
    }

    protected AnnotationConfigApplicationContext context(CountingSource source,URLClassLoader code) throws Exception {
        return context(source, source, code);
    }
    protected AnnotationConfigApplicationContext context(CountingSource source, CountingSource migrations, URLClassLoader code) throws Exception {
        var context=new AnnotationConfigApplicationContext();context.setClassLoader(code);context.register(Transactions.class);
        context.registerBean("dataSource",javax.sql.DataSource.class,()->source);context.registerBean("transactionManager",DataSourceTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean("jdbc",NamedParameterJdbcTemplate.class,()->new NamedParameterJdbcTemplate(source));
        context.addBeanFactoryPostProcessor(bindings(context.getEnvironment(), code));
        context.registerBean("flywayInitializer",FlywayMigrationInitializer.class,()->new FlywayMigrationInitializer(Flyway.configure(code).dataSource(migrations).locations("classpath:db/migration").load(),context.getBean(AdapterBindings.STRATEGY,FlywayMigrationStrategy.class)));
        return context;
    }
    protected AdapterBindings bindings(org.springframework.core.env.Environment environment, ClassLoader code) throws Exception {
        List<ActivationPlan.Node> nodes = new ArrayList<>(); Map<String,Class<?>> functions = new TreeMap<>();
        for (var descriptor : descriptors) for (var component : descriptor.components()) {
            var node = new ActivationPlan.Node(component.identity(), descriptor.module(), component.kind(),
                    component.javaName(), component.dependencies(), descriptor.persistent());
            nodes.add(node); functions.put(node.identity(), code.loadClass(node.javaName()));
        }
        return new AdapterBindings(environment, List.of(), Map.of(), nodes, List.of("example.dependent"),
                List.of(), functions, "application-schema.json");
    }

    protected record RuntimeLogin(String role, String password) { }

    protected RuntimeLogin provisionRuntime(CountingSource migrations) throws Exception {
        var login = new RuntimeLogin("module_runtime_" + UUID.randomUUID().toString().replace("-", ""), UUID.randomUUID().toString());
        try (var connection = migrations.getConnection(); var sql = connection.createStatement()) {
            sql.execute("CREATE ROLE " + login.role() + " LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '" + login.password() + "'");
            // Explicit provisioning: only application-owned schemas/tables, never Flyway histories.
            for (String schema : List.of("db.required", "db.dependent", "db.application")) {
                sql.execute("CREATE SCHEMA " + SqlNames.quote(schema));
                sql.execute("GRANT USAGE ON SCHEMA " + SqlNames.quote(schema) + " TO " + login.role());
                sql.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA " + SqlNames.quote(schema)
                        + " GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO " + login.role());
            }
        }
        return login;
    }

    protected org.springframework.boot.test.context.runner.ApplicationContextRunner boot(
            CountingSource migrations, RuntimeLogin runtime, URLClassLoader code) {
        return new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withClassLoader(code).withUserConfiguration(Transactions.class)
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
                        org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration.class,
                        org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration.class,
                        FlywayAutoConfiguration.class))
                .withPropertyValues("spring.datasource.type=org.springframework.jdbc.datasource.DriverManagerDataSource",
                        "spring.datasource.url=" + migrations.url, "spring.datasource.username=" + runtime.role(),
                        "spring.datasource.password=" + runtime.password(),
                        "spring.flyway.url=" + migrations.url,
                        "spring.flyway.user=" + migrations.credentials.getProperty("user"),
                        "spring.flyway.password=" + migrations.credentials.getProperty("password", ""))
                .withInitializer(context -> {
                    try {
                        registerApplication(context, code);
                        if (!tenant) context.addBeanFactoryPostProcessor(bindings(context.getEnvironment(), code));
                    }
                    catch (Exception failed) { throw new IllegalStateException("Test fixture binding failed", failed); }
                });
    }

    protected interface Exercise {void run(CountingSource source) throws Exception;}
    protected void database(Exercise exercise) throws Exception {
        String url=System.getenv("VERNAC_PG_TEST_URL");Properties credentials=new Properties();
        if(System.getenv("VERNAC_PG_TEST_USER")!=null)credentials.setProperty("user",System.getenv("VERNAC_PG_TEST_USER"));if(System.getenv("VERNAC_PG_TEST_PASSWORD")!=null)credentials.setProperty("password",System.getenv("VERNAC_PG_TEST_PASSWORD"));
        String database="vernac_modules_"+UUID.randomUUID().toString().replace("-","");
        try(var admin=DriverManager.getConnection(url,credentials)) {
            try(var statement=admin.createStatement()){statement.execute("CREATE DATABASE "+database);}
            try{exercise.run(new CountingSource(SchemaCheckMojo.databaseUrl(url,database),credentials));}
            finally{try(var statement=admin.createStatement()){statement.execute("DROP DATABASE "+database+" WITH (FORCE)");}}
        }
    }
    protected static final class CountingSource extends AbstractDataSource {
        protected final String url;final Properties credentials;String searchPath;final AtomicInteger transactions=new AtomicInteger();
        CountingSource(String url,Properties credentials){this.url=url;this.credentials=credentials;}
        public Connection getConnection() throws SQLException {return wrap(DriverManager.getConnection(url,credentials));}
        public Connection getConnection(String user,String password) throws SQLException{return wrap(DriverManager.getConnection(url,user,password));}
        protected Connection wrap(Connection connection) throws SQLException {if(searchPath!=null)try(var statement=connection.createStatement()){statement.execute("SET search_path TO "+searchPath);}return (Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{if(m.getName().equals("setAutoCommit")&&Boolean.FALSE.equals(a[0]))transactions.incrementAndGet();try{return m.invoke(connection,a);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}});}
    }
    protected static long count(CountingSource source,String sql) throws Exception {try(var c=source.getConnection();var s=c.createStatement();var rows=s.executeQuery(sql)){rows.next();return rows.getLong(1);}}
}
