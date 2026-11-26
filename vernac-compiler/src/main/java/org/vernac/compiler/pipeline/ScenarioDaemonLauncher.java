// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Launches the local JSON protocol using a workspace captured at startup. */
public final class ScenarioDaemonLauncher {
    private ScenarioDaemonLauncher() { }

    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args.length > 2
                || args.length == 2 && !Set.of("domain", "mvc", "postgres").contains(args[1])) {
            System.err.println("Usage: ScenarioDaemonLauncher <workspace> [domain|mvc|postgres]");
            System.exit(2);
            return;
        }
        var workspaceRoot = Path.of(args[0]);
        String profile = args.length == 2 ? args[1] : null;
        var classpath = classpath();
        var protocol = System.out;
        // Application and fixture output must not be interleaved with replies.
        System.setOut(System.err);
        new ScenarioDaemon(openWorkspace(workspaceRoot, profile, classpath),
                () -> new InProcessScenarioExecutor(classpath)).serve(
                new InputStreamReader(System.in, StandardCharsets.UTF_8),
                new OutputStreamWriter(protocol, StandardCharsets.UTF_8));
    }

    static List<Path> classpath() {
        return Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator)))
                .map(Path::of).toList();
    }

    static ScenarioWorkspace openWorkspace(Path workspaceRoot, String profile, List<Path> classpath) throws IOException {
        return ScenarioWorkspace.openSaved(workspaceRoot, new InProcessScenarioExecutor(classpath), profile);
    }
}
