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

class DomainServiceExecutionTest {
    @TempDir Path directory;
    private Path model() throws Exception {
        Path root=Files.createDirectories(directory.resolve("model"));
        Files.createDirectories(root.resolve("operations"));
        Files.writeString(root.resolve("operations/model.vernac"),resource("model.vernac"));
        return root;
    }
    @Test void generatedServicesCompileAndExecuteTheirContracts() throws Exception {
        try(var code=compile(model())) {
            try { code.loadClass("operations.Probe").getMethod("run").invoke(null); }
            catch(java.lang.reflect.InvocationTargetException ex) { throw new AssertionError(ex.getCause()); }
        }
    }
    private URLClassLoader compile(Path model) throws Exception {
        Path sources = Files.createDirectories(directory.resolve("java"));
        Path classes = Files.createDirectories(directory.resolve("classes"));
        new VernacCompiler().compileProject(model).writeTo(sources);
        Files.createDirectories(sources.resolve("operations"));
        Files.writeString(sources.resolve("operations/Probe.java"),resource("Probe.java.txt"));
        Files.writeString(sources.resolve("operations/ExternalImplementation.java"),resource("ExternalImplementation.java.txt"));
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
        try(var stream=DomainServiceExecutionTest.class.getResourceAsStream("/services/"+name)) {
            return new String(Objects.requireNonNull(stream).readAllBytes(),StandardCharsets.UTF_8);
        }
    }
}
