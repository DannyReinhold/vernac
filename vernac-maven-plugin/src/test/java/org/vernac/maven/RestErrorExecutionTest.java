// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.pipeline.VernacCompiler;
import static org.junit.jupiter.api.Assertions.*;

class RestErrorExecutionTest {
    @TempDir Path directory;

    @Test void declarativeRulesPreserveClassificationAndUnknownBodySemantics() throws Exception {
        String document = Files.readString(Path.of("../docs/tutorials/declarative-errors.md")).replace("\r\n", "\n").replace('\r', '\n');
        String model = vernacExample(document);
        assertEquals(model, vernacExample(document.replace("\r\n", "\n").replace("\n", "\r\n")));
        Path root = Files.createDirectories(directory.resolve("model/example/errors"));
        Files.writeString(root.resolve("catalog.vernac"), model);
        Path sources = Files.createDirectories(directory.resolve("java"));
        Path classes = Files.createDirectories(directory.resolve("classes"));
        new VernacCompiler().compileProject(directory.resolve("model")).writeTo(sources);
        try (var input = getClass().getResourceAsStream("/rest-errors/Probe.java.txt")) {
            Files.writeString(sources.resolve("example/errors/Probe.java"),
                    new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8));
        }
        var compiler = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler(), "A JDK is required");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
             var paths = Files.walk(sources)) {
            var files = paths.filter(path -> path.toString().endsWith(".java")).toList();
            assertTrue(compiler.getTask(null, manager, diagnostics, List.of("--release", "21", "-encoding", "UTF-8",
                    "-proc:none", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()),
                    null, manager.getJavaFileObjectsFromPaths(files)).call(), () -> diagnostics.getDiagnostics().toString());
        }
        try (var code = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            try { code.loadClass("example.errors.Probe").getMethod("run").invoke(null); }
            catch (java.lang.reflect.InvocationTargetException ex) { throw new AssertionError(ex.getCause()); }
        }
    }

    private static String vernacExample(String document) {
        document = document.replace("\r\n", "\n").replace('\r', '\n');
        String fence = "```vernac\n";
        int opening = document.indexOf(fence);
        assertTrue(opening >= 0, "HTTP tutorial must contain a Vernac example");
        int start = opening + fence.length();
        int end = document.indexOf("```", start);
        assertTrue(end >= 0, "HTTP tutorial example must have a closing fence");
        return document.substring(start, end);
    }
}
