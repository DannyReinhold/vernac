// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.vernac.compiler.pipeline.VernacCompiler;

import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import javax.tools.ToolProvider;

/** The bundled application is an executable contract, not an untested set of snippets. */
class SpringBootStarterExecutionTest {
    @TempDir Path temporary;

    @Test
    void actualStarterSourcesCompileAndItsDomainAndHtmlTestsExecute() throws Exception {
        Path template = Path.of("../templates/vernac-spring-boot");
        var generated = new VernacCompiler().compileProject(template.resolve("src/main/vernac"));
        Path java = temporary.resolve("java"), classes = temporary.resolve("classes");
        Files.createDirectories(classes);
        List<String> sources = new ArrayList<>();
        for (var file : generated.generatedFiles()) {
            Path source =
                    java.resolve(file.packageName().replace('.', '/'))
                            .resolve(file.typeSpec().name() + ".java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, file.toString());
            sources.add(source.toString());
        }
        for (String directory : List.of("src/main/java", "src/test/java")) {
            try (var files = Files.walk(template.resolve(directory))) {
                files.filter(
                                p ->
                                        p.toString().endsWith(".java")
                                                && !p.toString().endsWith("IT.java"))
                        .forEach(p -> sources.add(p.toString()));
            }
        }
        List<String> arguments =
                new ArrayList<>(
                        List.of(
                                "--release",
                                "21",
                                "-parameters",
                                "-classpath",
                                System.getProperty("java.class.path"),
                                "-d",
                                classes.toString()));
        arguments.addAll(sources);
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler()
                        .run(null, null, null, arguments.toArray(String[]::new)));
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (var loader =
                new URLClassLoader(
                        new java.net.URL[] {
                            classes.toUri().toURL(),
                            template.resolve("src/main/resources").toUri().toURL()
                        },
                        previous)) {
            Thread.currentThread().setContextClassLoader(loader);
            var request =
                    LauncherDiscoveryRequestBuilder.request()
                            .selectors(
                                    selectClass(loader.loadClass("org.example.tasks.TaskTest")),
                                    selectClass(loader.loadClass("org.example.tasks.TaskWebTest")),
                                    selectClass(loader.loadClass("org.example.tasks.TaskConfigurationTest")))
                            .build();
            var listener = new SummaryGeneratingListener();
            LauncherFactory.create().execute(request, listener);
            var summary = listener.getSummary();
            assertEquals(7, summary.getTestsFoundCount());
            assertEquals(
                    7,
                    summary.getTestsSucceededCount(),
                    () ->
                            summary.getFailures().stream()
                                    .map(
                                            f ->
                                                    f.getTestIdentifier().getDisplayName()
                                                            + ": "
                                                            + f.getException())
                                    .collect(Collectors.joining("\n")));
            assertEquals(0, summary.getTestsSkippedCount());
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }
}
