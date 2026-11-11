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
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantGeneratedPostgresTest {
    @TempDir static Path directory;
    private URLClassLoader code;
    private SchemaModel schema;
    @BeforeAll void compile() throws Exception {
        Path root=Files.createDirectories(directory.resolve("model/tenantdemo")).getParent();
        Files.writeString(root.resolve("tenantdemo/model.vernac"),resource("model.vernac"));
        var compiler=new VernacCompiler();
        schema=new SchemaBuilder(compiler.analyzeProject(root),SchemaModel.empty()).build();
        Path java=Files.createDirectories(directory.resolve("java"));
        Path classes=Files.createDirectories(directory.resolve("classes"));
        compiler.compileProject(root).writeTo(java);
        Files.writeString(Files.createDirectories(java.resolve("tenantdemo")).resolve("TenantProbe.java"),resource("TenantProbe.java.txt"));
        var javac=Objects.requireNonNull(ToolProvider.getSystemJavaCompiler());
        var diagnostics=new DiagnosticCollector<JavaFileObject>();
        try(var manager=javac.getStandardFileManager(diagnostics,null,StandardCharsets.UTF_8);var paths=Files.walk(java)) {
            var files=paths.filter(p->p.toString().endsWith(".java")).toList();
            assertTrue(javac.getTask(null,manager,diagnostics,List.of("--release","21","-proc:none","-classpath",System.getProperty("java.class.path"),"-d",classes.toString()),null,
                    manager.getJavaFileObjectsFromPaths(files)).call(),()->diagnostics.getDiagnostics().toString());
        }
        code=new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},getClass().getClassLoader());
    }
    @AfterAll void close() throws Exception {if(code!=null)code.close();}
    @Test void generatedTenantRepositoriesCompile(){assertNotNull(code);}
    @Test @EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
    void generatedGraphsQueriesPaginationAndOwnershipRemainTenantScoped() throws Exception {database("lifecycle",false);}
    @Test @EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
    void concurrentGeneratedRepositoriesRetainIndependentTenantTransactions() throws Exception {database("parallel",false);}
    @Test @EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
    void generatedStoresRejectOwnerRolesAndChangedPolicies() throws Exception {database("policies",true);}
    @Test @EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
    void tenantMigrationBackfillsAllTenantsWithDedicatedMigrationPrivileges() throws Exception {database("migration",false);}
    private void evolve(DataSource owner) throws Exception {
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
                    if(method.equals("migration")){probe.getMethod("seed",DataSource.class).invoke(null,data);evolve(owner);}
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
