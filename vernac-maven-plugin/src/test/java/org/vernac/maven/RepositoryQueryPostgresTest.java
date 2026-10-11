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

class RepositoryQueryPostgresTest {
    @TempDir Path directory;
    private Path model() throws Exception {
        Path root=Files.createDirectories(directory.resolve("model"));
        Files.createDirectories(root.resolve("querydemo"));
        Files.writeString(root.resolve("querydemo/model.vernac"),resource("model.vernac"));
        return root;
    }
    @Test void generatedRepositoryCompiles() throws Exception {
        try(var code=compile(model())) { assertNotNull(code.loadClass("querydemo.QueryProbe")); }
    }
    @Test @EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
    void generatedRepositoryExecutesQueriesAndLoadsCompleteGraphs() throws Exception {
        Path root=model();
        var schema=new SchemaBuilder(new VernacCompiler().analyzeProject(root),SchemaModel.empty()).build();
        String ddl=new MigrationPlanner().plan(SchemaModel.empty(),schema,MigrationPlanner.Options.safe()).sql();
        Properties credentials=new Properties();
        for(String key:List.of("user","password")) {
            String value=System.getenv("VERNAC_PG_TEST_"+key.toUpperCase(Locale.ROOT));
            if(value!=null) credentials.setProperty(key,value);
        }
        String database="vernac_queries_"+UUID.randomUUID().toString().replace("-","");
        String url=System.getenv("VERNAC_PG_TEST_URL");
        try(var admin=DriverManager.getConnection(url,credentials);var code=compile(root)) {
            try(var stmt=admin.createStatement()) { stmt.execute("CREATE DATABASE "+database); }
            try {
                var data=new DriverManagerDataSource(SchemaCheckMojo.databaseUrl(url,database));
                data.setConnectionProperties(credentials);
                try(var connection=data.getConnection();var statement=connection.createStatement()) { statement.execute(ddl); }
                try {
                    var probe=code.loadClass("querydemo.QueryProbe");
                    probe.getMethod("run",DataSource.class).invoke(null,data);
                    probe.getMethod("concurrent",DataSource.class,boolean.class).invoke(null,data,false);
                    probe.getMethod("concurrent",DataSource.class,boolean.class).invoke(null,data,true);
                }
                catch(java.lang.reflect.InvocationTargetException e) {
                    if(e.getCause() instanceof Error error) throw error;
                    if(e.getCause() instanceof Exception error) throw error;
                    throw e;
                }
            } finally { try(var stmt=admin.createStatement()) { stmt.execute("DROP DATABASE "+database+" WITH (FORCE)"); } }
        }
    }
    private URLClassLoader compile(Path model) throws Exception {
        Path sources = Files.createDirectories(directory.resolve("java"));
        Path classes = Files.createDirectories(directory.resolve("classes"));
        new VernacCompiler().compileProject(model).writeTo(sources);
        Files.createDirectories(sources.resolve("querydemo"));
        Files.writeString(sources.resolve("querydemo/QueryProbe.java"), resource("QueryProbe.java.txt"));
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
        try(var stream=RepositoryQueryPostgresTest.class.getResourceAsStream("/persistence/queries/"+name)) {
            return new String(Objects.requireNonNull(stream).readAllBytes(),StandardCharsets.UTF_8);
        }
    }
}
