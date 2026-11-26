// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class InProcessScenarioExecutorTest {
    @TempDir Path root;

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
