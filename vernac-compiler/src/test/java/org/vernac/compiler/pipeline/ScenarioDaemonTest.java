// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.application.ApplicationDefinition;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioDaemonTest {
    @TempDir Path root;

    @Test
    void runsOnlyAffectedSavedScenariosAgainstTheLastPassingRevision() throws Exception {
        var main = Files.createDirectories(root.resolve("src/main/vernac/app"));
        Files.writeString(main.resolve("counter.vernac"), "namespace app; value Counter(int);");
        var tests = Files.createDirectories(root.resolve("src/test/vernac/app"));
        String passing = "namespace app; suite Edited { scenario counter { given n = Counter(1); expect n.intValue equals 1; } }";
        var edited = Files.writeString(tests.resolve("edited.vernac"), passing);
        Files.writeString(tests.resolve("other.vernac"),
                "namespace app; suite Other { scenario counter { given n = Counter(2); expect n.intValue equals 2; } }");
        var classpath = ScenarioDaemonLauncher.classpath();
        var daemon = new ScenarioDaemon(ScenarioWorkspace.openSaved(root, new InProcessScenarioExecutor(classpath), "domain"),
                () -> fail("saved runs must use the shared executor"));
        var output = new StringWriter();
        daemon.serve(new StringReader("""
                {"id":1,"op":"runSaved","revision":0}
                """), output);
        Files.writeString(edited, passing.replace("equals 1", "equals 3"));
        daemon.serve(new StringReader("""
                {"id":2,"op":"runSaved","revision":0}
                {"id":3,"op":"runSaved","revision":0}
                {"id":4,"op":"runSaved","revision":1}
                """), output);
        Files.writeString(edited, passing.replace("Counter(1)", "Counter(3)").replace("equals 1", "equals 3"));
        daemon.serve(new StringReader("""
                {"id":5,"op":"runSaved","revision":1}
                {"id":6,"op":"runSaved","revision":2}
                """), output);

        var json = JsonMapper.builder().build();
        var replies = output.toString().lines().map(json::readTree).toList();
        assertEquals(6, replies.size());
        var initial = replies.get(0).at("/result/outcomes");
        assertEquals("PASSED", initial.path("app.Edited.counter").asText());
        assertEquals("PASSED", initial.path("app.Other.counter").asText());
        assertEquals(1, replies.get(1).at("/result/revision").intValue());
        assertEquals(1, replies.get(1).at("/result/outcomes").size());
        assertEquals("FAILED", replies.get(1).at("/result/outcomes/app.Edited.counter").asText());
        assertEquals("stale_revision", replies.get(2).at("/error/code").asText());
        assertEquals("FAILED", replies.get(3).at("/result/outcomes/app.Edited.counter").asText());
        assertEquals(2, replies.get(4).at("/result/revision").intValue());
        assertEquals(1, replies.get(4).at("/result/outcomes").size());
        assertEquals("PASSED", replies.get(4).at("/result/outcomes/app.Edited.counter").asText());
        assertTrue(replies.get(5).at("/result/outcomes").isEmpty());
    }

    @Test
    void publishesSavedChangesAsOneRevisionWithoutRebasingOpenOverlays() throws Exception {
        var main = Files.createDirectories(root.resolve("src/main/vernac/app"));
        var tests = Files.createDirectories(root.resolve("src/test/vernac/app"));
        var source = Files.writeString(main.resolve("counter.vernac"), "namespace app; value Counter(int);");
        var scenario = Files.writeString(tests.resolve("checks.vernac"),
                "namespace app; suite Checks { scenario counter { expect true equals true; } }");
        ScenarioRunSession.Executor unused = request -> fail("check must not execute tests");
        var daemon = new ScenarioDaemon(ScenarioWorkspace.openSaved(root, unused, null), () -> unused);
        var json = JsonMapper.builder().build();
        var output = new StringWriter();
        daemon.serve(new StringReader("""
                {"id":1,"op":"open","revision":0}
                """), output);

        Files.move(source, main.resolve("renamed.vernac"));
        Files.writeString(scenario, "namespace app; suite");
        daemon.serve(new StringReader("""
                {"id":2,"op":"refresh","revision":0}
                {"id":3,"op":"refresh","revision":0}
                {"id":4,"op":"refresh","revision":1}
                {"id":5,"op":"open","revision":1}
                {"id":6,"op":"check","client":"1","revision":0}
                {"id":7,"op":"check","client":"2","revision":0}
                """), output);

        Files.writeString(root.resolve(ApplicationDefinition.FILE), "");
        daemon.serve(new StringReader("""
                {"id":8,"op":"refresh","revision":1}
                {"id":9,"op":"sharedRevision"}
                """), output);
        var replies = output.toString().lines().map(json::readTree).toList();
        assertEquals(9, replies.size());
        assertEquals(1, replies.get(1).at("/result/revision").intValue());
        assertEquals("stale_revision", replies.get(2).at("/error/code").asText());
        assertEquals(1, replies.get(3).at("/result/revision").intValue());
        assertTrue(replies.get(5).at("/result/valid").booleanValue());
        var diagnostics = replies.get(6).at("/result/diagnostics");
        assertFalse(replies.get(6).at("/result/valid").booleanValue());
        assertEquals(1, diagnostics.size());
        assertEquals("src/test/vernac/app/checks.vernac", diagnostics.get(0).at("/location/sourceName").asText());
        assertEquals("execution_error", replies.get(7).at("/error/code").asText());
        assertEquals(1, replies.get(8).at("/result/revision").intValue());
    }

    @Test
    void checksProductionAndScenarioDiagnosticsWithoutExecutingTests() throws Exception {
        var main = Path.of("src/main/vernac/app/counter.vernac");
        var test = Path.of("src/test/vernac/app/checks.vernac");
        String scenario = "namespace app; suite Checks { scenario counter { expect true equals true; } }";
        ScenarioRunSession.Executor unused = request -> fail("check must not execute tests");
        var workspace = new ScenarioWorkspace(root, Map.of(main, "namespace app; value Counter(int);", test, scenario),
                Map.of(), List.of(), unused, null);
        var daemon = new ScenarioDaemon(workspace, () -> unused);
        var json = JsonMapper.builder().build();
        var broken = json.writeValueAsString(Map.of("id", 3, "op", "apply", "client", "1", "revision", 0,
                "edits", List.of(Map.of("kind", "put", "path", test.toString(), "text", "namespace app; suite"))));
        var output = new StringWriter();
        daemon.serve(new StringReader("""
                {"id":1,"op":"open","revision":0}
                {"id":2,"op":"check","client":"1","revision":0}
                """ + broken + "\n" + """
                {"id":4,"op":"check","client":"1","revision":1}
                {"id":5,"op":"check","client":"1","revision":0}
                """), output);
        var replies = output.toString().lines().map(json::readTree).toList();
        assertEquals(5, replies.size());
        assertTrue(replies.get(1).at("/result/valid").asBoolean());
        assertFalse(replies.get(3).at("/result/valid").asBoolean());
        assertEquals(1, replies.get(3).at("/result/revision").asInt());
        assertEquals("src/test/vernac/app/checks.vernac",
                replies.get(3).at("/result/diagnostics/0/location/sourceName").asText());
        assertEquals("stale_revision", replies.get(4).at("/error/code").asText());
    }

    @Test
    void sendsRevisionedResultsAndKeepsOverlaysIsolated() throws Exception {
        var classpath = Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator)))
                .map(Path::of).toList();
        var main = Path.of("src/main/vernac/app/counter.vernac");
        var test = Path.of("src/test/vernac/app/checks.vernac");
        String scenario = "namespace app; suite Checks { scenario counter { given n = Counter(1); expect n.intValue equals 1; } }";
        var workspace = new ScenarioWorkspace(root, Map.of(main, "namespace app; value Counter(int);", test, scenario),
                Map.of(), List.of(), new InProcessScenarioExecutor(classpath), null);
        var daemon = new ScenarioDaemon(workspace, () -> new InProcessScenarioExecutor(classpath));
        var json = JsonMapper.builder().build();
        var edit = json.writeValueAsString(Map.of("id", 3, "op", "apply", "client", "1", "revision", 0,
                "edits", List.of(Map.of("kind", "put", "path", test.toString(),
                        "text", scenario.replace("equals 1", "equals 2")))));
        var input = """
                {"id":1,"op":"open","revision":0}
                {"id":2,"op":"open","revision":0}
                """ + edit + "\n" + """
                {"id":4,"op":"run","client":"1","revision":0}
                {"id":5,"op":"run","client":"1","revision":1}
                {"id":6,"op":"run","client":"2","revision":0}
                broken JSON
                {"id":8,"op":"close","client":"1"}
                {"id":9,"op":"run","client":"1","revision":1}
                """;
        var output = new StringWriter();
        daemon.serve(new StringReader(input), output);
        var replies = output.toString().lines().map(json::readTree).toList();
        assertEquals(9, replies.size());
        assertEquals("1", replies.get(0).at("/result/client").asText());
        assertEquals("2", replies.get(1).at("/result/client").asText());
        assertEquals(1, replies.get(2).at("/result/revision").asInt());
        assertEquals("stale_revision", replies.get(3).at("/error/code").asText());
        assertEquals(1, replies.get(3).at("/error/details/currentRevision").asInt());
        assertEquals(5, replies.get(4).path("id").asInt());
        assertEquals(1, replies.get(4).at("/result/revision").asInt());
        assertEquals("FAILED", replies.get(4).at("/result/outcomes/app.Checks.counter").asText());
        assertEquals("PASSED", replies.get(5).at("/result/outcomes/app.Checks.counter").asText());
        assertEquals("invalid_request", replies.get(6).at("/error/code").asText());
        assertTrue(replies.get(7).at("/result/closed").asBoolean());
        assertEquals("invalid_request", replies.get(8).at("/error/code").asText());
    }
}
