// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class InProcessScenarioExecutorTest {
    @TempDir Path root;

    @Test
    void loadsCurrentTemplatesAndIgnoresRemovedProjectTemplates() throws Exception {
        var output = root.resolve("target/classes");
        String name = "templates/vernac-generated/app/Welcome.html";
        var old = output.resolve(name);
        Files.createDirectories(old.getParent());
        Files.writeString(old, "stale");
        Files.writeString(output.resolve("templates/handwritten.html"), "handwritten");
        var viewFile = Path.of("app/view.vernac");
        var sources = new VersionedSources(root.resolve("src/main/vernac"), Map.of(viewFile,
                "namespace app; view Welcome() { page \"Fresh view\" { heading \"Fresh template\"; } }"));
        var planning = new ScenarioPlanningSession(root.resolve("src/main/vernac"), Map.of(), List.of());
        var executor = new InProcessScenarioExecutor(ScenarioDaemonLauncher.classpath(), List.of(output));
        var probe = MethodSpec.methodBuilder("resources")
                .addAnnotation(Test.class)
                .addAnnotation(AnnotationSpec.builder(DisplayName.class).addMember("value", "$S", "resources").build())
                .addException(Exception.class)
                .addStatement("var loader = $T.currentThread().getContextClassLoader()", Thread.class)
                .addStatement("var template = loader.getResource($S)", name)
                .addStatement("var matches = $T.list(loader.getResources($S))", java.util.Collections.class, name)
                .addStatement("$T.assertEquals(template == null ? 0 : 1, matches.size())", Assertions.class)
                .beginControlFlow("if (template != null)")
                .beginControlFlow("try (var input = template.openStream())")
                .addStatement("$T.assertTrue(new String(input.readAllBytes(), $T.UTF_8).contains($S))",
                        Assertions.class, StandardCharsets.class, "Fresh template")
                .endControlFlow()
                .endControlFlow()
                .addStatement("$T.assertNotNull(loader.getResource($S))", Assertions.class, "templates/handwritten.html");
        var currentProbe = probe.build().toBuilder().addStatement("$T.assertNotNull(template)", Assertions.class).build();
        var bridge = JavaFile.builder("app", TypeSpec.classBuilder("ResourceChecks").addMethod(currentProbe).build()).build();
        var original = planning.plan(Optional.empty(), new ScenarioPlanningSession.Inputs(sources.snapshot(), Map.of()), false, null);
        assertEquals(1, original.target().project().webTemplates().size());
        assertEquals(Map.of("resources", ScenarioRunSession.Outcome.PASSED), executor.execute(new ScenarioRunSession.Execution(
                0, original.target().project(), bridge, List.of())));

        var removed = sources.apply(0, List.of(new VersionedSources.Delete(viewFile)));
        var next = planning.plan(Optional.empty(), new ScenarioPlanningSession.Inputs(removed, Map.of()), false, null);
        assertEquals(Map.of(), next.target().project().webTemplates());
        var absentProbe = probe.addStatement("$T.assertNull(template)", Assertions.class).build();
        var absentBridge = JavaFile.builder("app", TypeSpec.classBuilder("ResourceChecks").addMethod(absentProbe).build()).build();
        assertEquals(Map.of("resources", ScenarioRunSession.Outcome.PASSED), executor.execute(new ScenarioRunSession.Execution(
                1, next.target().project(), absentBridge, List.of())));
    }

    @Test
    void executesWithJupiterAndLoadsFreshClassesAfterAnEdit() throws Exception {
        var classpath = Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator)))
                .map(Path::of).toList();
        var executor = new InProcessScenarioExecutor(classpath);
        var runner = new ScenarioRunSession(new ScenarioPlanningSession(root, Map.of(), List.of()), executor, null);
        var path = Path.of("app/counter.vernac");
        var sources = new VersionedSources(root, Map.of(path, "namespace app; value Counter(int);"));
        var tests = Map.of("checks.vernac", """
                namespace app;
                suite Checks {
                    scenario counter { given counter = Counter(1); expect counter.intValue equals 1; }
                }
                """);
        var initial = new ScenarioPlanningSession.Inputs(sources.snapshot(), tests);
        var originalLoader = Thread.currentThread().getContextClassLoader();
        assertEquals(Map.of("app.Checks.counter", ScenarioRunSession.Outcome.PASSED), runner.run(initial, false).outcomes());

        assertEquals(2, executor.compilationCount());
        assertEquals(1, executor.applicationCompilationCount());

        var editedTests = Map.of("checks.vernac", tests.get("checks.vernac").replace("equals 1", "equals 2"));
        var testOnly = runner.run(new ScenarioPlanningSession.Inputs(initial.main(), editedTests), false);
        assertEquals(Map.of("app.Checks.counter", ScenarioRunSession.Outcome.FAILED), testOnly.outcomes());
        assertEquals(3, executor.compilationCount());
        assertEquals(1, executor.applicationCompilationCount());

        var rejected = sources.apply(0, List.of(new VersionedSources.Put(path,
                "namespace app; value Counter(int) validates { require self intValue > 1 else TooSmall. };")));
        var result = runner.run(new ScenarioPlanningSession.Inputs(rejected, tests), false);
        assertEquals(1, result.revision());
        assertEquals(Map.of("app.Checks.counter", ScenarioRunSession.Outcome.FAILED), result.outcomes());
        assertEquals(5, executor.compilationCount());
        assertEquals(2, executor.applicationCompilationCount());

        var changed = new ScenarioPlanningSession.Inputs(rejected, tests);
        assertEquals(result.outcomes(), runner.run(changed, false).outcomes());
        assertEquals(5, executor.compilationCount());
        assertEquals(result.outcomes(), runner.run(changed, true).outcomes());
        assertEquals(7, executor.compilationCount());
        assertEquals(3, executor.applicationCompilationCount());
        assertSame(originalLoader, Thread.currentThread().getContextClassLoader());
    }
}
