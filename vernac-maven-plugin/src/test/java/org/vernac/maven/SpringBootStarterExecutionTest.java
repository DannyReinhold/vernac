// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.pipeline.VernacCompiler;

import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;

import javax.tools.ToolProvider;

/** The bundled application is an executable contract, not an untested set of snippets. */
class SpringBootStarterExecutionTest {
    @TempDir Path temporary;

    @Test
    void actualStarterSourcesCompileAndItsDomainAndHtmlTestsExecute() throws Exception {
        Path template = Path.of("../templates/vernac-spring-boot");
        var compiler = new VernacCompiler();
        var project = compiler.analyzeProject(template.resolve("src/main/vernac"));
        var generated = compiler.generate(project, org.vernac.compiler.persistence.SchemaModel.empty(), Map.of());
        Path java = temporary.resolve("java"), classes = temporary.resolve("classes");
        Files.createDirectories(classes);
        for (var entry : project.webTemplates().entrySet()) {
            Path resource = classes.resolve("templates").resolve(entry.getKey());
            Files.createDirectories(resource.getParent());
            Files.writeString(resource, entry.getValue());
        }
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
        String viewAction = Files.readString(Path.of("../docs/ai/test-view-action.md"));
        sources.add(AiReferenceExamplesTest.scenarioBridge(temporary, viewAction, project, "mvc").toString());
        List<String> arguments =
                new ArrayList<>(
                        List.of(
                                "--release",
                                "21",
                                "-Xlint:deprecation,removal",
                                "-Werror",
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
            AiReferenceExamplesTest.assertTestsPass(loader, 1, "org.vernac.generated.VernacScenariosTest");
            AiReferenceExamplesTest.assertTestsPass(loader, 7, "org.example.tasks.TaskTest",
                    "org.example.tasks.TaskWebTest", "org.example.tasks.TaskConfigurationTest");
        }
    }
}
