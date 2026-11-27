// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Launches the local JSON protocol using a workspace captured at startup. */
public final class ScenarioDaemonLauncher {
    static final Set<String> PROFILES = Set.of("domain", "mvc", "postgres");

    private ScenarioDaemonLauncher() { }

    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args.length > 2
                || args.length == 2 && !PROFILES.contains(args[1])) {
            System.err.println("Usage: ScenarioDaemonLauncher <workspace> [domain|mvc|postgres]");
            System.exit(2);
            return;
        }
        String profile = args.length == 2 ? args[1] : null;
        var protocol = System.out;
        // Application and fixture output must not be interleaved with replies.
        System.setOut(System.err);
        serve(Path.of(args[0]), profile,
                new InputStreamReader(System.in, StandardCharsets.UTF_8),
                new OutputStreamWriter(protocol, StandardCharsets.UTF_8));
    }

    static void serve(Path workspaceRoot, String profile, Reader input, Writer output) throws IOException {
        try (var daemon = daemon(workspaceRoot, profile)) {
            daemon.serve(input, output);
        }
    }

    static ScenarioDaemon daemon(Path workspaceRoot, String profile) throws IOException {
        var root = workspaceRoot.toRealPath();
        var classpath = classpath();
        return new ScenarioDaemon(openWorkspace(root, profile, classpath), () -> executor(root, classpath));
    }

    static List<Path> classpath() {
        return Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator)))
                .map(Path::of).toList();
    }

    static ScenarioWorkspace openWorkspace(Path workspaceRoot, String profile, List<Path> classpath) throws IOException {
        var root = workspaceRoot.toRealPath();
        return ScenarioWorkspace.openSaved(root, executor(root, classpath), profile);
    }

    private static InProcessScenarioExecutor executor(Path root, List<Path> classpath) {
        return new InProcessScenarioExecutor(classpath,
                List.of(root.resolve("target/test-classes"), root.resolve("target/classes")));
    }
}
