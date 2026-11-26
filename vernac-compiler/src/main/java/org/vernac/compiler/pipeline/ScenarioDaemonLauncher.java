// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.vernac.compiler.application.ApplicationDefinition;
import org.vernac.compiler.modules.ModuleArtifacts;
import org.vernac.compiler.modules.ModuleDefinition;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        var root = workspaceRoot.toRealPath();
        if (!Files.isDirectory(root)) throw new IOException("Expected a workspace directory: " + root);
        var main = root.resolve("src/main/vernac");
        var tests = root.resolve("src/test/vernac");
        var sources = new LinkedHashMap<Path, String>();
        var configuration = new LinkedHashMap<Path, String>();
        capture(main, sources, configuration);
        capture(tests, sources, new LinkedHashMap<>());
        for (String name : List.of(ApplicationDefinition.FILE, ModuleDefinition.FILE)) {
            var config = root.resolve(name);
            if (Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS))
                configuration.put(config, Files.readString(config));
        }
        return new ScenarioWorkspace(root, sources, configuration, ModuleArtifacts.editorModules(main),
                new InProcessScenarioExecutor(classpath), profile);
    }

    private static void capture(Path root, Map<Path, String> sources, Map<Path, String> configuration)
            throws IOException {
        if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Expected a Vernac source directory: " + root);
        try (var paths = Files.walk(root)) {
            for (var path : paths.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)).toList()) {
                String name = path.getFileName().toString();
                if (name.endsWith(".vernac")) sources.put(path, Files.readString(path));
                else if (name.equals(ModuleDefinition.FILE)) configuration.put(path, Files.readString(path));
            }
        }
    }
}
