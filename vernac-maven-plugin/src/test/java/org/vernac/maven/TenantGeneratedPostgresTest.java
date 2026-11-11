// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.URLClassLoader;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import javax.tools.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.vernac.compiler.pipeline.VernacCompiler;
import org.vernac.compiler.persistence.*;
import org.vernac.compiler.modules.*;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantGeneratedPostgresTest {
    @TempDir static Path directory;
    private URLClassLoader code;
    private SchemaModel schema;
    private URLClassLoader moduleCode;
    private SchemaModel moduleSchema;
    private record Compiled(URLClassLoader code, SchemaModel schema) { }

    @BeforeAll void compile() throws Exception {
        var local = compileFixture(false);
        code = local.code(); schema = local.schema();
        var imported = compileFixture(true);
        moduleCode = imported.code(); moduleSchema = imported.schema();
    }

    private Compiled compileFixture(boolean imported) throws Exception {
        Path fixture = Files.createTempDirectory(directory, imported ? "imported-" : "local-");
        Path root = Files.createDirectories(fixture.resolve("model/tenantdemo")).getParent();
        String model = resource("model.vernac");
        List<ModuleDescriptor> modules = List.of();
        Path dependency = null;
        if (imported) {
            dependency = publishEntities(fixture, model);
            modules = ModuleArtifacts.read(List.of(dependency));
            model = "namespace tenantdemo; id InvoiceId; id AuditId; id CatalogId; id ArchiveId;\n"
                    + model.substring(model.indexOf("tenant aggregate Invoice"));
        }
        Files.writeString(root.resolve("tenantdemo/model.vernac"), model);
        var compiler = new VernacCompiler();
        var resolved = compiler.analyzeProject(root, modules);
        var schema = new SchemaBuilder(resolved, SchemaModel.empty()).build();
        Path java = Files.createDirectories(fixture.resolve("java"));
        Path classes = Files.createDirectories(fixture.resolve("classes"));
        var generated = compiler.generate(resolved, SchemaModel.empty(), Map.of());
        if (imported) assertTrue(generated.generatedFiles().stream().noneMatch(f -> f.typeSpec().name().equals("Stop")));
        generated.writeTo(java);
        Files.writeString(Files.createDirectories(java.resolve("tenantdemo")).resolve("TenantProbe.java"), resource("TenantProbe.java.txt"));
        compileJava(java, classes, dependency);
        var urls = new ArrayList<java.net.URL>(); urls.add(classes.toUri().toURL());
        if (dependency != null) urls.add(dependency.toUri().toURL());
        return new Compiled(new URLClassLoader(urls.toArray(java.net.URL[]::new), getClass().getClassLoader()), schema);
    }

    private Path publishEntities(Path fixture, String model) throws Exception {
        Path base = Files.createDirectories(fixture.resolve("producer"));
        Path root = Files.createDirectories(base.resolve("src/main/vernac/tenantdemo")).getParent();
        String source = model.substring(0, model.indexOf("tenant aggregate Invoice"));
        for (String id : List.of("InvoiceId", "AuditId", "CatalogId", "ArchiveId")) source = source.replace("id " + id + ";", "");
        Files.writeString(root.resolve("tenantdemo/model.vernac"), source);
        String manifest = "module example.entities {"
                + List.of("StopId", "ParcelId", "Title", "Tag", "Tags", "TagGroup", "TagGroups", "Parcel", "Parcels", "Stop", "Stops")
                    .stream().map(n -> "export tenantdemo." + n + ";").collect(java.util.stream.Collectors.joining()) + "}";
        Path definition = base.resolve(ModuleDefinition.FILE); Files.writeString(definition, manifest);
        var compiler = new VernacCompiler(); var resolved = compiler.analyzeProject(root);
        var generated = compiler.generate(resolved, SchemaModel.empty(), Map.of());
        var descriptor = ModuleContracts.publish(ModuleDefinition.parse(definition, manifest), resolved,
                generated.generatedFiles(), "example", "entities", "1", List.of());
        Path java = Files.createDirectories(base.resolve("java")); generated.writeTo(java);
        Path classes = Files.createDirectories(base.resolve("classes")); compileJava(java, classes, null);
        ModuleDescriptor.write(classes.resolve(ModuleDescriptor.RESOURCE), descriptor);
        Path jar = base.resolve("entities.jar");
        try (var output = new java.util.jar.JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                output.putNextEntry(new java.util.jar.JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, output); output.closeEntry();
            }
        }
        return jar;
    }

    private void compileJava(Path java, Path classes, Path dependency) throws Exception {
        var javac = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler());
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        String classpath = System.getProperty("java.class.path")
                + (dependency == null ? "" : System.getProperty("path.separator") + dependency);
        try (var manager = javac.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8); var paths = Files.walk(java)) {
            var files = paths.filter(p -> p.toString().endsWith(".java")).toList();
            assertTrue(javac.getTask(null, manager, diagnostics,
                    List.of("--release", "21", "-proc:none", "-classpath", classpath, "-d", classes.toString()), null,
                    manager.getJavaFileObjectsFromPaths(files)).call(), () -> diagnostics.getDiagnostics().toString());
        }
    }

    @AfterAll void close() throws Exception {
        if (code != null) code.close();
        if (moduleCode != null) moduleCode.close();
    }
    @Test void importedModuleEntitiesCompileFromPackagedClasses() {assertNotNull(moduleCode);}
    @Test @EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
    void importedModuleEntitiesRetainOwnershipAndSharedIdentityAcrossTenants() throws Exception {
        database("lifecycle", false, moduleCode, moduleSchema);
    }
    @Test void generatedTenantRepositoriesCompile(){assertNotNull(code);}
    @Test @EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
    void generatedGraphsQueriesPaginationAndOwnershipRemainTenantScoped() throws Exception {database("lifecycle",false);}
    @Test @EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
    void concurrentGeneratedRepositoriesRetainIndependentTenantTransactions() throws Exception {database("parallel",false);}
    @Test @EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
    void generatedStoresRejectOwnerRolesAndChangedPolicies() throws Exception {database("policies",true);}
    @Test @EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
    void tenantMigrationBackfillsAllTenantsWithDedicatedMigrationPrivileges() throws Exception {database("migration",false);}
    private void evolve(DataSource owner, SchemaModel schema) throws Exception {
        var tables=schema.tables().stream().map(t->{
            if(!t.name().equals("Invoice"))return t;
            var columns=new ArrayList<>(t.columns());
            columns.add(new SchemaModel.Column("reference","TEXT",false,"String:","tenantdemo.Invoice.reference"));
            return new SchemaModel.Table(t.namespace(),t.name(),t.origin(),columns,t.primaryKey(),t.constraints(),t.tenantIsolation());
        }).toList();
        var after=new SchemaModel(2,1,tables,schema.enumCodes());
        var plan=new MigrationPlanner().plan(schema,after,new MigrationPlanner.Options(false,
                Map.of("tenantdemo/Invoice/reference","'LEGACY'"),Map.of()));
        try(var connection=owner.getConnection();var statement=connection.createStatement()){
            statement.execute(plan.sql());
            try(var rows=statement.executeQuery("SELECT count(*) FROM tenantdemo."+SqlNames.name("Invoice")+" WHERE reference='LEGACY'")){
                rows.next();assertEquals(2,rows.getInt(1));
            }
        }
    }
    private void database(String method,boolean needsAdmin) throws Exception {
        database(method, needsAdmin, code, schema);
    }
    private void database(String method, boolean needsAdmin, URLClassLoader code, SchemaModel schema) throws Exception {
        String url=System.getenv("VERNAC_PG_TEST_URL");
        var credentials=new Properties();credentials.setProperty("user",System.getenv("VERNAC_PG_TEST_USER"));
        credentials.setProperty("password",System.getenv("VERNAC_PG_TEST_PASSWORD"));
        String suffix=UUID.randomUUID().toString().replace("-","");
        String db="vtg_"+suffix,role="vtg_role_"+suffix,password=UUID.randomUUID().toString();
        try(var admin=DriverManager.getConnection(url,credentials)) {
            boolean roleCreated=false,databaseCreated=false;
            try {
                try(var s=admin.createStatement()){
                    s.execute("CREATE ROLE "+role+" LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '"+password+"'");roleCreated=true;
                    s.execute("CREATE DATABASE "+db);databaseCreated=true;
                }
                String target=SchemaCheckMojo.databaseUrl(url,db);
                var owner=new DriverManagerDataSource(target);owner.setConnectionProperties(credentials);
                try(var c=owner.getConnection();var s=c.createStatement()){
                    s.execute(new MigrationPlanner().plan(SchemaModel.empty(),schema,MigrationPlanner.Options.safe()).sql());
                    s.execute("GRANT USAGE ON SCHEMA tenantdemo TO "+role);
                    s.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA tenantdemo TO "+role);
                }
                var data=new DriverManagerDataSource(target,role,password);
                try {
                    var probe=code.loadClass("tenantdemo.TenantProbe");
                    if(method.equals("migration")){probe.getMethod("seed",DataSource.class).invoke(null,data);evolve(owner,schema);}
                    else if(needsAdmin)probe.getMethod(method,DataSource.class,DataSource.class).invoke(null,data,owner);
                    else probe.getMethod(method,DataSource.class).invoke(null,data);
                } catch(java.lang.reflect.InvocationTargetException failure) {
                    if(failure.getCause() instanceof Error error)throw error;
                    if(failure.getCause() instanceof Exception error)throw error;
                    throw failure;
                }
            } finally {try(var s=admin.createStatement()){
                if(databaseCreated)s.execute("DROP DATABASE "+db+" WITH (FORCE)");
                if(roleCreated)s.execute("DROP ROLE "+role);
            }}
        }
    }
    private String resource(String name) throws Exception {
        try(var stream=getClass().getResourceAsStream("/persistence/tenants/"+name)){
            return new String(Objects.requireNonNull(stream).readAllBytes(),StandardCharsets.UTF_8);
        }
    }
}
