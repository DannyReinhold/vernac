// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.URLClassLoader;
import java.util.*;
import java.sql.*;
import javax.tools.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.vernac.compiler.pipeline.VernacCompiler;
import org.vernac.compiler.persistence.*;
import static org.junit.jupiter.api.Assertions.*;

class EventStoragePostgresTest {
    @TempDir Path directory;
    private Path model() throws Exception {
        Path root=Files.createDirectories(directory.resolve("model"));
        Files.createDirectories(root.resolve("eventdemo"));
        Files.writeString(root.resolve("eventdemo/model.vernac"),resource("model.vernac"));
        return root;
    }
    @Test void generatedEventStorageCompiles() throws Exception {
        try(var code=compile(model())) { assertNotNull(code.loadClass("eventdemo.EventProbe")); }
    }
    @Test @EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
    void eventGraphsAndDeliveriesCommitOrRollbackTogether() throws Exception {
        Path root=model();
        var schema=new SchemaBuilder(new VernacCompiler().analyzeProject(root),SchemaModel.empty()).build();
        String ddl=new MigrationPlanner().plan(SchemaModel.empty(),schema,MigrationPlanner.Options.safe()).sql();
        Properties credentials=new Properties();
        for(String key:List.of("user","password")) {
            String value=System.getenv("VERNAC_PG_TEST_"+key.toUpperCase(Locale.ROOT));
            if(value!=null) credentials.setProperty(key,value);
        }
        String database="vernac_events_"+UUID.randomUUID().toString().replace("-","");
        String url=System.getenv("VERNAC_PG_TEST_URL");
        try(var admin=DriverManager.getConnection(url,credentials);var code=compile(root)) {
            try(var stmt=admin.createStatement()) { stmt.execute("CREATE DATABASE "+database); }
            try {
                var data=new DriverManagerDataSource(SchemaCheckMojo.databaseUrl(url,database));
                data.setConnectionProperties(credentials);
                try(var connection=data.getConnection();var statement=connection.createStatement()) { statement.execute(ddl); }
                try {
                    var probe=code.loadClass("eventdemo.EventProbe");
                    probe.getMethod("run",DataSource.class).invoke(null,data);
                }
                catch(java.lang.reflect.InvocationTargetException e) {
                    if(e.getCause() instanceof Error error) throw error;
                    if(e.getCause() instanceof Exception error) throw error;
                    throw e;
                }
            } finally { try(var stmt=admin.createStatement()) { stmt.execute("DROP DATABASE "+database+" WITH (FORCE)"); } }
        }
    }
    @Test @EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
    void generatedAdaptersRequireMatchingLayoutAndLoadReviewedLegacyGraphs() throws Exception {
        Path root=model();
        var current=new SchemaBuilder(new VernacCompiler().analyzeProject(root),SchemaModel.empty()).build();
        var legacyTables=current.tables().stream().map(t->{
            if(t.eventIsolation()==null)return t;
            var columns=t.columns().stream().filter(c->!Set.of("@scopeKey","@tenantId").contains(c.name())).toList();
            var constraints=t.constraints().stream().filter(c->!c.name().startsWith("@check:event-origin")
                &&!c.name().startsWith("@unique:event-origin:")&&!c.name().startsWith("@unique:event-row:"))
                .map(c->new SchemaModel.Constraint(c.name(),c.clause().startsWith("FOREIGN KEY")?c.clause().replace("\"@scopeKey\", ",""):c.clause())).toList();
            return new SchemaModel.Table(t.namespace(),t.name(),t.origin(),columns,t.primaryKey(),constraints);
        }).toList();
        var legacy=new SchemaModel(1,1,legacyTables,current.enumCodes());
        Map<String,String> backfills=new HashMap<>();
        current.tables().stream().filter(t->t.eventIsolation()!=null).forEach(t->backfills.put(t.identity()+"/@scopeKey","'GLOBAL'"));
        var planner=new MigrationPlanner();
        Properties credentials=new Properties();
        for(String key:List.of("user","password")){String value=System.getenv("VERNAC_PG_TEST_"+key.toUpperCase(Locale.ROOT));if(value!=null)credentials.setProperty(key,value);}
        String database="vernac_event_upgrade_"+UUID.randomUUID().toString().replace("-","");
        String url=System.getenv("VERNAC_PG_TEST_URL");
        try(var admin=DriverManager.getConnection(url,credentials);var code=compile(root)) {
            try(var sql=admin.createStatement()){sql.execute("CREATE DATABASE "+database);}
            try {
                var data=new DriverManagerDataSource(SchemaCheckMojo.databaseUrl(url,database));data.setConnectionProperties(credentials);
                try(var c=data.getConnection();var sql=c.createStatement()){sql.execute(planner.plan(SchemaModel.empty(),legacy,MigrationPlanner.Options.safe()).sql());}
                var probe=code.loadClass("eventdemo.OriginLayoutProbe");
                UUID event=(UUID)invoke(probe,"beforeUpgrade",new Class<?>[]{DataSource.class},data);
                String migration=planner.plan(legacy,current,new MigrationPlanner.Options(false,backfills,Map.of())).sql();
                assertTrue(migration.contains("EVENT ORIGIN UPGRADE"));
                try(var c=data.getConnection();var sql=c.createStatement()){sql.execute(migration);}
                invoke(probe,"afterUpgrade",new Class<?>[]{DataSource.class,UUID.class},data,event);
                String role="event_reader_"+UUID.randomUUID().toString().replace("-","");
                try(var c=data.getConnection();var sql=c.createStatement()) {
                    sql.execute("CREATE ROLE "+role+" LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'fixture-only'");
                    try {
                        sql.execute("GRANT USAGE ON SCHEMA eventdemo,vernac TO "+role);
                        sql.execute("GRANT SELECT,INSERT ON ALL TABLES IN SCHEMA eventdemo,vernac TO "+role);
                        sql.execute("GRANT UPDATE ON \"vernac\".\"@delivery\" TO "+role);
                        var restricted=new DriverManagerDataSource(SchemaCheckMojo.databaseUrl(url,database),role,"fixture-only");
                        invoke(probe,"restrictedRoundTrip",new Class<?>[]{DataSource.class},restricted);
                    } finally {sql.execute("DROP OWNED BY "+role);sql.execute("DROP ROLE "+role);}
                }
            } finally {try(var sql=admin.createStatement()){sql.execute("DROP DATABASE "+database+" WITH (FORCE)");}}
        }
    }
    private static Object invoke(Class<?> probe,String method,Class<?>[] types,Object... args) throws Exception {
        try{return probe.getMethod(method,types).invoke(null,args);}
        catch(java.lang.reflect.InvocationTargetException failed){
            if(failed.getCause() instanceof Error error)throw error;
            if(failed.getCause() instanceof Exception error)throw error;
            throw failed;
        }
    }
    private URLClassLoader compile(Path model) throws Exception {
        Path sources = Files.createDirectories(directory.resolve("java"));
        Path classes = Files.createDirectories(directory.resolve("classes"));
        new VernacCompiler().compileProject(model).writeTo(sources);
        Files.createDirectories(sources.resolve("eventdemo"));
        Files.writeString(sources.resolve("eventdemo/EventProbe.java"), resource("EventProbe.java.txt"));
        Files.writeString(sources.resolve("eventdemo/OriginLayoutProbe.java"), resource("OriginLayoutProbe.java.txt"));
        var compiler = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler(), "A JDK is required");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
             var paths = Files.walk(sources)) {
            var files = paths.filter(path -> path.toString().endsWith(".java")).toList();
            var options = List.of("--release", "21", "-encoding", "UTF-8", "-proc:none",
                    "-classpath", System.getProperty("java.class.path"), "-d", classes.toString());
            assertTrue(compiler.getTask(null, manager, diagnostics, options, null,
                    manager.getJavaFileObjectsFromPaths(files)).call(), () -> diagnostics.getDiagnostics().toString());
        }
        return new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader());
    }

    private static String resource(String name) throws Exception {
        try(var stream=EventStoragePostgresTest.class.getResourceAsStream("/persistence/events/"+name)) {
            return new String(Objects.requireNonNull(stream).readAllBytes(),StandardCharsets.UTF_8);
        }
    }
}
