// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.vernac.compiler.analyzer.SemanticValidationException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** CLI for a saved workspace check, test run, application build or local daemon session. */
public final class ScenarioCli {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ScenarioCli() { }

    public static void main(String[] args) {
        var output = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        System.setOut(System.err);
        System.exit(execute(args, output));
    }

    private static int execute(String[] args, PrintStream output) {
        try {
            if (args.length < 1) return usage(output);
            if (args[0].equals("build")) return build(args, output);
            if (!Set.of("check", "test", "daemon", "mcp").contains(args[0])) return usage(output);
            boolean test = args[0].equals("test");
            int next = 1;
            Path root = Path.of(".");
            if (next < args.length && !args[next].startsWith("--")) root = Path.of(args[next++]);
            String profile = null;
            if (next < args.length && ScenarioDaemonLauncher.PROFILES.contains(args[next])) profile = args[next++];
            String scenario = null;
            if (next < args.length) {
                if (!test || args.length - next != 2 || !args[next].equals("--scenario") || args[next + 1].isBlank()) {
                    return usage(output);
                }
                scenario = args[next + 1];
            }
            if (args[0].equals("mcp")) {
                try (var daemon = ScenarioDaemonLauncher.daemon(root, profile)) {
                    new VernacMcpServer(daemon).serve(
                            new InputStreamReader(System.in, StandardCharsets.UTF_8),
                            new OutputStreamWriter(output, StandardCharsets.UTF_8));
                    return 0;
                } catch (IOException | RuntimeException failure) {
                    System.err.println("Vernac MCP: " + failure.getMessage());
                    return 2;
                }
            }
            if (args[0].equals("daemon")) {
                ScenarioDaemonLauncher.serve(root, profile, new InputStreamReader(System.in, StandardCharsets.UTF_8),
                        new OutputStreamWriter(output, StandardCharsets.UTF_8));
                return 0;
            }
            var workspace = ScenarioDaemonLauncher.openWorkspace(root, profile, ScenarioDaemonLauncher.classpath());
            long revision = workspace.revision();
            if (!test) {
                var check = workspace.check(revision);
                write(output, Map.of("revision", revision, "valid", check.valid(), "diagnostics", check.diagnostics()));
                return check.valid() ? 0 : 1;
            }
            var run = scenario == null ? workspace.run(revision, false) : workspace.runScenario(revision, scenario);
            write(output, run);
            return run.outcomes().values().stream().allMatch(value -> value == ScenarioRunSession.Outcome.PASSED) ? 0 : 1;
        } catch (SemanticValidationException failure) {
            write(output, Map.of("error", "diagnostics", "diagnostics", failure.diagnostics()));
            return 1;
        } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
            write(output, Map.of("error", "execution_error", "message", String.valueOf(failure.getMessage())));
            return 2;
        }
    }

    private static int build(String[] args, PrintStream output) throws IOException {
        boolean development = false;
        Path workspace = null;
        for (int index = 1; index < args.length; index++) {
            String argument = args[index];
            if (argument.equals("--dev") && !development) development = true;
            else if (!argument.startsWith("--") && workspace == null) workspace = Path.of(argument);
            else return usage(output);
        }
        Path root = (workspace == null ? Path.of(".") : workspace).toRealPath();
        if (development) {
            var reply = JSON.readTree(DevelopmentBuildServer.build(root));
            write(output, reply);
            return switch (reply.path("status").asText()) {
                case "activated" -> 0;
                case "invalid", "rebuild_required" -> 1;
                default -> 2;
            };
        }
        int status = MavenProjectBuild.packageProject(root);
        if (status == 0) {
            write(output, Map.of("built", true, "backend", "maven", "workspace", root.toString()));
            return 0;
        }
        write(output, Map.of("built", false, "backend", "maven", "workspace", root.toString(),
                "error", "build_failed", "mavenExitCode", status));
        return 1;
    }

    private static int usage(PrintStream output) {
        write(output, Map.of("error", "usage", "message",
                "Usage: vernac <check|test|daemon|mcp> [workspace [domain|mvc|postgres]] [--scenario <id> (test only)] or vernac build [--dev] [workspace]"));
        return 2;
    }

    private static void write(PrintStream output, Object result) {
        output.println(JSON.writeValueAsString(result));
    }
}
