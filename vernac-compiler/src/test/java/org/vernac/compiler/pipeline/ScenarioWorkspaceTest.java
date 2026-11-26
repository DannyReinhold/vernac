// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioWorkspaceTest {
    @TempDir Path root;

    @Test
    void separatesClientEditsAndExecutionBaselinesFromTheSharedRevision() throws Exception {
        var model = Path.of("src/main/vernac/app/model.vernac");
        var test = Path.of("src/test/vernac/app/checks.vernac");
        var scenario = """
                namespace app;
                suite Checks {
                    scenario counter { given counter = Counter(1); expect counter.intValue equals 1; }
                }
                """;
        var classpath = Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator)))
                .map(Path::of).toList();
        var workspace = new ScenarioWorkspace(root, Map.of(model, "namespace app; value Counter(int);", test, scenario),
                Map.of(), List.of(), new InProcessScenarioExecutor(classpath), null);
        workspace.apply(0, List.of(new VersionedSources.Put(test, scenario + "\n")));
        var first = workspace.openOverlay(1, new InProcessScenarioExecutor(classpath));
        var second = workspace.openOverlay(1, new InProcessScenarioExecutor(classpath));
        assertEquals(1, first.baseRevision());
        assertEquals(0, first.workspace().revision());
        assertEquals(0, second.workspace().revision());

        assertEquals(1, first.workspace().apply(0, List.of(new VersionedSources.Put(test,
                scenario.replace("equals 1", "equals 2")))));
        assertEquals(2, workspace.apply(1, List.of(new VersionedSources.Delete(test))));
        assertEquals(2, assertThrows(VersionedSources.Conflict.class,
                () -> workspace.openOverlay(1, new InProcessScenarioExecutor(classpath))).currentRevision());

        var failed = first.workspace().run(1, false);
        assertEquals(Map.of("app.Checks.counter", ScenarioRunSession.Outcome.FAILED), failed.outcomes());
        var passed = second.workspace().run(0, false);
        assertEquals(Map.of("app.Checks.counter", ScenarioRunSession.Outcome.PASSED), passed.outcomes());
        assertEquals(AffectedScenarioSelector.Reason.NO_BASELINE, passed.selected().getFirst().reason());
        assertTrue(second.workspace().run(0, false).selected().isEmpty());
        assertEquals(failed.outcomes(), first.workspace().run(1, false).outcomes());
        assertTrue(workspace.run(2, false).selected().isEmpty());
    }

    @Test
    void keepsAnInFlightRunPinnedWhilePublishingAMixedEdit() throws Exception {
        var model = Path.of("src/main/vernac/app/model.vernac");
        var test = Path.of("src/test/vernac/app/checks.vernac");
        String original = "namespace app; value Counter(int);";
        String scenario = """
                namespace app;
                suite Checks {
                    scenario counter { given counter = Counter(1); expect counter.intValue equals 1; }
                }
                """;
        var classpath = Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator)))
                .map(Path::of).toList();
        var executor = new InProcessScenarioExecutor(classpath);
        var started = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var workspace = new ScenarioWorkspace(root, Map.of(model, original, test, scenario),
                Map.of(), List.of(), request -> {
                    if (request.revision() == 0) {
                        started.countDown();
                        try {
                            if (!resume.await(10, TimeUnit.SECONDS)) throw new IOException("Timed out waiting for editor");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted", e);
                        }
                    }
                    return executor.execute(request);
                }, null);

        try (var pool = Executors.newSingleThreadExecutor()) {
            var running = pool.submit(() -> workspace.run(0, false));
            try {
                assertTrue(started.await(10, TimeUnit.SECONDS));
                assertThrows(IllegalArgumentException.class, () -> workspace.apply(0, List.of(
                        new VersionedSources.Delete(model), new VersionedSources.Put(Path.of("outside.vernac"), original))));
                assertEquals(0, workspace.revision());
                assertEquals(1, workspace.apply(0, List.of(
                        new VersionedSources.Put(model, original.replace("Counter", "Count")),
                        new VersionedSources.Put(test, scenario.replace("Counter", "Count").replace("equals 1", "equals 2")))));
                assertEquals(1, assertThrows(VersionedSources.Conflict.class,
                        () -> workspace.run(0, false)).currentRevision());
                assertEquals(1, assertThrows(VersionedSources.Conflict.class,
                        () -> workspace.apply(0, List.of())).currentRevision());
            } finally {
                resume.countDown();
            }
            var first = running.get(10, TimeUnit.SECONDS);
            assertEquals(0, first.revision());
            assertEquals(Map.of("app.Checks.counter", ScenarioRunSession.Outcome.PASSED), first.outcomes());
        }
        var second = workspace.run(1, false);
        assertEquals(1, second.revision());
        assertEquals(Map.of("app.Checks.counter", ScenarioRunSession.Outcome.FAILED), second.outcomes());
    }
}
