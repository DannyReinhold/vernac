// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.pipeline.VernacCompiler;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MappingExecutionTest {
    @TempDir
    Path directory;

    private static String resource(String name) throws Exception {
        try (var stream = MappingExecutionTest.class.getResourceAsStream("/mappings/" + name)) {
            return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Path model() throws Exception {
        Path root = Files.createDirectories(directory.resolve("model"));
        Files.createDirectories(root.resolve("mapdemo"));
        Files.writeString(root.resolve("mapdemo/model.vernac"), resource("model.vernac"));
        return root;
    }

    @Test
    void generatedMappingsExecuteFactoriesAndPreserveIntent() throws Exception {
        try (var code = compile(model(), true)) {
            try {
                code.loadClass("mapdemo.Probe").getMethod("run").invoke(null);
            } catch (java.lang.reflect.InvocationTargetException ex) {
                throw new AssertionError(ex.getCause());
            }
        }
    }

    @Test
    void tutorialCompilesAndRunsThroughTheAdapter() throws Exception {
        String document = Files.readString(
                Path.of("../docs/tutorials/adapter-mappings.md")
        ).replace("\r\n", "\n").replace('\r', '\n');

        String opening = "```vernac\n";
        int openingPosition = document.indexOf(opening);
        assertTrue(openingPosition >= 0,
                "Tutorial must contain a fenced Vernac example");

        int start = openingPosition + opening.length();
        int end = document.indexOf("```", start);
        assertTrue(end >= 0,
                "Tutorial Vernac example must have a closing fence");

        String model = document.substring(start, end);
        Path root = Files.createDirectories(directory.resolve("tutorial"));
        Path folder = Files.createDirectories(root.resolve("example/catalog"));
        Files.writeString(folder.resolve("catalog.vernac"), model);
        try (var code = compile(root, false)) {
            var adapter = code.loadClass("example.catalog.adapter.outbound.LocalCatalog");
            Object result = adapter.getMethod("load").invoke(adapter.getConstructor().newInstance());
            assertTrue(result.toString().contains("Notebook"));
        }
    }

    private URLClassLoader compile(Path model, boolean fixtures) throws Exception {
        Path sources = Files.createDirectories(directory.resolve("java"));
        Path classes = Files.createDirectories(directory.resolve("classes"));
        new VernacCompiler().compileProject(model).writeTo(sources);
        if (fixtures) {
            Files.createDirectories(sources.resolve("mapdemo"));
            Files.writeString(sources.resolve("mapdemo/Probe.java"), resource("Probe.java.txt"));
            Files.writeString(sources.resolve("mapdemo/External.java"), resource("External.java.txt"));
        }
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
}
