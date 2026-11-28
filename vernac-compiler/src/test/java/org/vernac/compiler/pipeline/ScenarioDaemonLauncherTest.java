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
import java.util.OptionalLong;
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

    @Test
    void warmChecksStartReuseAndRecoverTheWorkspaceProcess() throws Exception {
        Path model = Files.createDirectories(root.resolve("workspace/src/main/vernac/app")).resolve("counter.vernac");
        Path workspace = root.resolve("workspace");
        List<String> check = List.of("check", "--warm", workspace.toString(), "domain");
        Files.writeString(model, "namespace app; value Counter(int);");
        try {
            assertTrue(runCli(check, 0).get("valid").asBoolean());
            long initialPid = warmProcessId();
            Files.writeString(model, "namespace app; value Counter(Missing);");
            var invalid = runCli(check, 1);
            assertFalse(invalid.get("valid").asBoolean());
            assertEquals(1, invalid.get("revision").asLong());
            assertEquals(initialPid, warmProcessId());
            assertEquals(invalid, runCli(check, 1));

            Files.writeString(model, "namespace app; value Counter(int);");
            Files.writeString(workspace.resolve("vernac-module.vmodule"), "module app.checks { export app.Counter; }");
            var configured = runCli(check, 0);
            assertTrue(configured.get("valid").asBoolean());
            assertEquals(2, configured.get("revision").asLong());
            assertEquals(initialPid, warmProcessId());

            stopProcess(initialPid);
            var restarted = runCli(check, 0);
            assertTrue(restarted.get("valid").asBoolean());
            assertEquals(0, restarted.get("revision").asLong());
            assertNotEquals(initialPid, warmProcessId());
        } finally {
            var pid = warmProcessIdIfStarted();
            if (pid.isPresent()) stopProcess(pid.getAsLong());
        }
    }

    @Test
    void mcpSharesWarmSavedChecksWithCliAndKeepsItsOwnSessionRevision() throws Exception {
        Path workspace = root.resolve("workspace");
        Path model = Files.createDirectories(workspace.resolve("src/main/vernac/app")).resolve("counter.vernac");
        Files.writeString(model, "namespace app; value Counter(int);");
        List<String> check = List.of("check", "--warm", workspace.toString(), "domain");
        List<String> mcp = List.of("mcp", workspace.toString(), "domain");
        String initialize = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """;
        String sharedCheck = """
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"check_shared_saved"}}
                """;
        var json = JsonMapper.builder().build();
        try {
            runCliOutput(mcp, 0, initialize);
            assertTrue(warmProcessIdIfStarted().isEmpty());
            var first = runCliOutput(mcp, 0, initialize + sharedCheck).lines().map(json::readTree).toList();
            assertFalse(first.get(1).at("/result/isError").asBoolean());
            assertEquals(runCli(check, 0), first.get(1).at("/result/structuredContent"));
            long pid = warmProcessId();

            Files.writeString(model, "namespace app; value Counter(Missing);");
            String revision = """
                    {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"workspace_revision"}}
                    """;
            var replies = runCliOutput(mcp, 0, initialize + sharedCheck + revision).lines()
                    .map(json::readTree).toList();
            var checked = replies.get(1).at("/result/structuredContent");
            assertFalse(replies.get(1).at("/result/isError").asBoolean());
            assertFalse(checked.get("valid").asBoolean());
            assertEquals(1, checked.get("revision").asLong());
            assertEquals(checked, runCli(check, 1));
            assertEquals(0, replies.get(2).at("/result/structuredContent/revision").asLong());
            assertEquals(pid, warmProcessId());
        } finally {
            var pid = warmProcessIdIfStarted();
            if (pid.isPresent()) stopProcess(pid.getAsLong());
        }
    }

    private long warmProcessId() throws Exception {
        return warmProcessIdIfStarted().orElseThrow();
    }

    private OptionalLong warmProcessIdIfStarted() throws Exception {
        if (!Files.isDirectory(root.resolve(".vernac-workspaces"))) return OptionalLong.empty();
        try (var files = Files.walk(root.resolve(".vernac-workspaces"))) {
            var endpoint = files.filter(path -> path.getFileName().toString().equals("endpoint")).findFirst();
            if (endpoint.isEmpty()) return OptionalLong.empty();
            long pid = JsonMapper.builder().build().readTree(Files.readString(endpoint.orElseThrow())).get("pid").asLong();
            return OptionalLong.of(pid);
        }
    }

    private static void stopProcess(long pid) throws Exception {
        var process = ProcessHandle.of(pid).orElse(null);
        if (process == null || !process.isAlive()) return;
        process.destroyForcibly();
        process.onExit().get(10, TimeUnit.SECONDS);
    }

    @Test
    void buildsTheRequestedProjectWithItsWrapperAndKeepsLogsOffProtocolOutput() throws Exception {
        var project = Files.createDirectory(root.resolve("application with spaces"));
        var arguments = List.of("build", project.toString());
        var missing = runCli(arguments, 2);
        assertEquals("execution_error", missing.path("error").asText());
        assertTrue(missing.path("message").asText().contains("pom.xml"));

        Files.writeString(project.resolve("pom.xml"), "<project/>");
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        var wrapper = project.resolve(windows ? "mvnw.cmd" : "mvnw");
        Files.writeString(wrapper, windows ? """
                @echo off
                echo %* > invocation.txt
                echo wrapper build log
                exit /b 0
                """ : """
                #!/bin/sh
                printf '%s' "$*" > invocation.txt
                echo 'wrapper build log'
                """);
        var success = runCli(arguments, 0);
        assertTrue(success.path("built").asBoolean());
        assertEquals(project.toRealPath().toString(), success.path("workspace").asText());
        assertEquals("maven", success.path("backend").asText());
        assertEquals("-B -ntp clean package", Files.readString(project.resolve("invocation.txt")).strip());
        assertTrue(Files.readString(root.resolve("cli-stderr.log")).contains("wrapper build log"));

        Files.writeString(wrapper, windows ? "@exit /b 7\r\n" : "exit 7\n");
        var failure = runCli(arguments, 1);
        assertFalse(failure.path("built").asBoolean());
        assertEquals("build_failed", failure.path("error").asText());
        assertEquals(7, failure.path("mavenExitCode").asInt());
    }

    private JsonNode runCli(String command, int expectedExit, String... options) throws Exception {
        var invocation = new ArrayList<>(List.of(command, root.toString(), "domain"));
        invocation.addAll(List.of(options));
        return runCli(invocation, expectedExit);
    }

    private JsonNode runCli(List<String> invocation, int expectedExit) throws Exception {
        return JsonMapper.builder().build().readTree(runCliOutput(invocation, expectedExit, ""));
    }

    private String runCliOutput(List<String> invocation, int expectedExit, String input) throws Exception {
        var stderr = root.resolve("cli-stderr.log");
        var stdout = root.resolve("cli-stdout.log");
        var stdin = root.resolve("cli-stdin.jsonl");
        Files.writeString(stdin, input);
        var arguments = new ArrayList<>(List.of(javaExecutable().toString(), "-Duser.home=" + root,
                "-cp", System.getProperty("java.class.path"),
                ScenarioCli.class.getName()));
        arguments.addAll(invocation);
        var process = new ProcessBuilder(arguments)
                .redirectInput(stdin.toFile()).redirectError(stderr.toFile()).redirectOutput(stdout.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "CLI did not exit");
            String reply = Files.readString(stdout);
            assertEquals(expectedExit, process.exitValue(), Files.readString(stderr) + reply);
            return reply;
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
