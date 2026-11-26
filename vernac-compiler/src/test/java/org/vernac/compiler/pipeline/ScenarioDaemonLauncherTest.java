// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioDaemonLauncherTest {
    @TempDir Path root;

    @Test
    void launchesRunsScenariosAndReopensTheSavedWorkspaceOnRestart() throws Exception {
        var model = Files.createDirectories(root.resolve("src/main/vernac/app")).resolve("counter.vernac");
        Files.writeString(model, """
                namespace app;
                value Counter(int) behavior {
                    public int echo() { System.out.println("scenario fixture output"); return self.intValue(); }
                };
                """);
        var cases = Files.createDirectories(root.resolve("src/test/vernac/app")).resolve("checks.vernac");
        var scenario = """
                namespace app;
                suite Checks {
                    scenario counter { given n = Counter(1); when answer = n.echo(); expect answer equals 1; }
                }
                """;
        Files.writeString(cases, scenario);

        var first = runProcess(ScenarioDaemonLauncher.class.getName(), root.toString(), "domain");
        assertEquals("EXPLICIT_SELECTION", first.get(1).at("/result/selected/0/reason").asText());
        assertEquals("PASSED", first.get(2).at("/result/outcomes/app.Checks.counter").asText());
        assertEquals("NO_BASELINE", first.get(2).at("/result/selected/0/reason").asText());
        assertTrue(Files.readString(root.resolve("daemon-stderr.log")).contains("scenario fixture output"));

        Files.writeString(cases, scenario.replace("equals 1", "equals 2"));
        var restarted = runProcess(ScenarioCli.class.getName(), "daemon");
        assertEquals(0, restarted.getFirst().at("/result/baseRevision").asInt());
        assertEquals("FAILED", restarted.get(1).at("/result/outcomes/app.Checks.counter").asText());
        assertEquals("FAILED", restarted.get(2).at("/result/outcomes/app.Checks.counter").asText());
        assertEquals(1, restarted.get(3).at("/result/protocolVersion").intValue());
        assertEquals(0, restarted.get(3).at("/result/revision").intValue());
    }

    @Test
    void cliReturnsDifferentExitCodesForSuccessDiagnosticsFailuresAndUsage() throws Exception {
        var model = Files.createDirectories(root.resolve("src/main/vernac/app")).resolve("counter.vernac");
        Files.writeString(model, "namespace app; value Counter(int);");
        var cases = Files.createDirectories(root.resolve("src/test/vernac/app")).resolve("checks.vernac");
        var scenario = """
                namespace app;
                suite Checks {
                    scenario counter { given n = Counter(1); expect n.intValue equals 1; }
                    scenario other { given n = Counter(3); expect n.intValue equals 3; }
                }
                """;
        Files.writeString(cases, scenario);
        assertTrue(runCli("check", 0).path("valid").asBoolean());
        assertEquals("PASSED", runCli("test", 0).at("/outcomes/app.Checks.counter").asText());

        Files.writeString(cases, scenario.replace("equals 1", "equals 2"));
        assertEquals("FAILED", runCli("test", 1).at("/outcomes/app.Checks.counter").asText());
        var selected = runCli("test", 0, "--scenario", "app.Checks.other");
        assertEquals(1, selected.path("outcomes").size());
        assertEquals("PASSED", selected.at("/outcomes/app.Checks.other").asText());
        assertEquals("EXPLICIT_SELECTION", selected.at("/selected/0/reason").asText());
        assertEquals("execution_error", runCli("test", 2, "--scenario", "app.Checks.unknown").path("error").asText());

        Files.writeString(cases, "namespace app; suite");
        var broken = runCli("check", 1);
        assertFalse(broken.path("valid").asBoolean());
        assertEquals("src/test/vernac/app/checks.vernac", broken.at("/diagnostics/0/location/sourceName").asText());
        assertEquals("usage", runCli("unsupported", 2).path("error").asText());
    }

    private JsonNode runCli(String command, int expectedExit, String... options) throws Exception {
        var stderr = root.resolve("cli-stderr.log");
        var stdout = root.resolve("cli-stdout.log");
        var arguments = new ArrayList<>(List.of(javaExecutable().toString(), "-cp", System.getProperty("java.class.path"),
                ScenarioCli.class.getName(), command, root.toString(), "domain"));
        arguments.addAll(List.of(options));
        var process = new ProcessBuilder(arguments)
                .redirectError(stderr.toFile()).redirectOutput(stdout.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "CLI did not exit");
            assertEquals(expectedExit, process.exitValue(), Files.readString(stderr));
            return JsonMapper.builder().build().readTree(Files.readString(stdout));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static Path javaExecutable() {
        var executable = Path.of(System.getProperty("java.home"), "bin", "java");
        return Files.isRegularFile(executable) ? executable : executable.resolveSibling("java.exe");
    }

    private List<JsonNode> runProcess(String... invocation) throws Exception {
        var stderr = root.resolve("daemon-stderr.log");
        var stdout = root.resolve("daemon-stdout.log");
        var arguments = new ArrayList<>(List.of(javaExecutable().toString(), "-cp", System.getProperty("java.class.path")));
        arguments.addAll(List.of(invocation));
        var process = new ProcessBuilder(arguments).directory(root.toFile())
                .redirectError(stderr.toFile()).redirectOutput(stdout.toFile()).start();
        try {
            try (var input = process.getOutputStream()) {
                input.write("""
                        {"id":1,"op":"open","revision":0}
                        {"id":2,"op":"runScenario","client":"1","revision":0,"scenario":"app.Checks.counter"}
                        {"id":3,"op":"run","client":"1","revision":0}
                        {"id":4,"op":"hello"}
                        """.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Daemon did not exit after EOF");
            assertEquals(0, process.exitValue(), Files.readString(stderr));
            var json = JsonMapper.builder().build();
            var responses = Files.readAllLines(stdout).stream().map(json::readTree).toList();
            assertEquals(4, responses.size(), Files.readString(stdout));
            return responses;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
