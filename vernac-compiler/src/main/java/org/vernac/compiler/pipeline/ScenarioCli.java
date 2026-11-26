// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.vernac.compiler.analyzer.SemanticValidationException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** One-shot CLI for a saved workspace check or test run. */
public final class ScenarioCli {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> PROFILES = Set.of("domain", "mvc", "postgres");

    private ScenarioCli() { }

    public static void main(String[] args) {
        var output = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        System.setOut(System.err);
        System.exit(execute(args, output));
    }

    private static int execute(String[] args, PrintStream output) {
        try {
            if (args.length < 1 || !Set.of("check", "test").contains(args[0])) return usage(output);
            boolean test = args[0].equals("test");
            int next = 1;
            Path root = Path.of(".");
            if (next < args.length && !args[next].startsWith("--")) root = Path.of(args[next++]);
            String profile = null;
            if (next < args.length && PROFILES.contains(args[next])) profile = args[next++];
            String scenario = null;
            if (next < args.length) {
                if (!test || args.length - next != 2 || !args[next].equals("--scenario") || args[next + 1].isBlank()) {
                    return usage(output);
                }
                scenario = args[next + 1];
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

    private static int usage(PrintStream output) {
        write(output, Map.of("error", "usage", "message",
                "Usage: vernac <check|test> [workspace [domain|mvc|postgres]] [--scenario <id> (test only)]"));
        return 2;
    }

    private static void write(PrintStream output, Object result) {
        output.println(JSON.writeValueAsString(result));
    }
}
