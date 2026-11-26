// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.vernac.compiler.persistence.SchemaModel;
import org.vernac.compiler.pipeline.ResolvedProject;
import org.vernac.compiler.pipeline.VernacCompiler;
import org.vernac.compiler.pipeline.VernacScenarioCompiler;

import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/** Compile only the sources supplied by the topic a reader opened. */
class AiReferenceExamplesTest {
    private static final Path DOCS = Path.of("../docs");
    @TempDir Path temporary;

    private static List<String> blocks(String text, String language) {
        var fence = Pattern.compile("^```" + Pattern.quote(language) + "\\R(.*?)^```[ \\t]*$",
                Pattern.MULTILINE | Pattern.DOTALL);
        return fence.matcher(text).results().map(match -> match.group(1)).toList();
    }

    static Stream<Arguments> workedExamples() throws Exception {
        String details = Files.readString(DOCS.resolve("ai-language-details.md"));
        return Arrays.stream(details.split("(?m)(?=^#{2,3} )"))
                .filter(section -> !blocks(section, "vernac").isEmpty())
                .map(section -> Arguments.of(section.lines().findFirst().orElseThrow(), section));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("workedExamples")
    void workedExampleCompilesIndependently(String heading, String section) throws Exception {
        compile(temporary, blocks(section, "vernac"), blocks(section, "java"), null);
    }

    @Test void standaloneStarterRunsItsDomainScenario() throws Exception {
        String page = Files.readString(DOCS.resolve("ai/start-model.md"));
        var model = blocks(page, "vernac");
        assertEquals(1, model.size());
        compile(temporary, model, List.of(), page);
    }

    @ParameterizedTest
    @ValueSource(strings = {"change-aggregate", "add-query", "add-usecase"})
    void editCompilesBeforeAndAfter(String task) throws Exception {
        String page = Files.readString(DOCS.resolve("ai/" + task + ".md"));
        var diffs = blocks(page, "diff");
        assertEquals(1, diffs.size());
        var before = new StringBuilder();
        var after = new StringBuilder();
        for (var line : diffs.getFirst().lines().toList()) {
            assertTrue(line.startsWith(" ") || line.startsWith("+") || line.startsWith("-"), line);
            if (!line.startsWith("+")) before.append(line.substring(1)).append('\n');
            if (!line.startsWith("-")) after.append(line.substring(1)).append('\n');
        }
        assertNotEquals(before.toString(), after.toString());
        compile(temporary.resolve("before"), List.of(before.toString()), List.of(), null);
        compile(temporary.resolve("after"), List.of(after.toString()), blocks(page, "java"),
                task.equals("change-aggregate") ? page : null);
    }

    private static Path writeModel(Path root, String filename, String model) throws Exception {
        var namespace = Pattern.compile("(?m)^namespace ([\\p{L}\\p{N}_.]+);").matcher(model);
        assertTrue(namespace.find(), "The documented model needs its namespace");
        Path file = root.resolve(namespace.group(1).replace('.', '/')).resolve(filename);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, model);
    }

    static Path scenarioBridge(Path work, String page, ResolvedProject project, String profile) throws Exception {
        var suites = blocks(page, "vernac-test");
        assertEquals(1, suites.size());
        Path root = work.resolve("test-model");
        Path file = writeModel(root, "scenario.vernac", suites.getFirst());
        Path output = work.resolve("test-java");
        assertEquals(1, new VernacScenarioCompiler().generate(root, List.of(file), project, output, profile));
        return output.resolve("org/vernac/generated/VernacScenariosTest.java");
    }

    private static void compile(Path work, List<String> models, List<String> handwritten, String scenarioPage)
            throws Exception {
        assertFalse(models.isEmpty());
        Path root = work.resolve("model");
        for (int i = 0; i < models.size(); i++) writeModel(root, "example" + i + ".vernac", models.get(i));
        var compiler = new VernacCompiler();
        var project = compiler.analyzeProject(root);
        Path sources = work.resolve("java");
        compiler.generate(project, SchemaModel.empty(), Map.of()).writeTo(sources);
        for (String source : handwritten) {
            var pkg = Pattern.compile("(?m)^package ([\\p{L}\\p{N}_.]+);").matcher(source);
            var type = Pattern.compile("public (?:final )?class (\\w+)").matcher(source);
            assertTrue(pkg.find() && type.find(), "The documented Java file needs its package and class");
            Path file = sources.resolve(pkg.group(1).replace('.', '/')).resolve(type.group(1) + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source);
        }
        Path classes = Files.createDirectories(work.resolve("classes"));
        var arguments = new ArrayList<>(List.of("--release", "21", "-proc:none", "-classpath",
                System.getProperty("java.class.path"), "-d", classes.toString()));
        try (var files = Files.walk(sources)) {
            files.filter(file -> file.toString().endsWith(".java")).map(Path::toString).forEach(arguments::add);
        }
        if (scenarioPage != null) arguments.add(scenarioBridge(work, scenarioPage, project, "domain").toString());
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)));
        if (scenarioPage != null) {
            try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},
                    AiReferenceExamplesTest.class.getClassLoader())) {
                assertTestsPass(loader, 1, "org.vernac.generated.VernacScenariosTest");
            }
        }
    }

    static void assertTestsPass(ClassLoader loader, int expectedCount, String... names) throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(loader);
            var request = LauncherDiscoveryRequestBuilder.request();
            for (String name : names) {
                request.selectors(selectClass(loader.loadClass(name)));
            }
            var listener = new SummaryGeneratingListener();
            LauncherFactory.create().execute(request.build(), listener);
            var summary = listener.getSummary();
            var failures = new java.io.StringWriter();
            summary.printFailuresTo(new java.io.PrintWriter(failures));
            assertEquals(0, summary.getTotalFailureCount(), failures::toString);
            assertEquals(expectedCount, summary.getTestsFoundCount());
            assertEquals(expectedCount, summary.getTestsSucceededCount());
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }
}
