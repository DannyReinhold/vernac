// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Builds a Maven-backed application using its configured lifecycle and packaging. */
final class MavenProjectBuild {
    private MavenProjectBuild() { }

    static int packageProject(Path workspace) throws IOException {
        var root = workspace.toRealPath();
        if (!Files.isRegularFile(root.resolve("pom.xml"))) {
            throw new IllegalArgumentException("Expected a Maven project with pom.xml in " + root);
        }

        var process = new ProcessBuilder(command(root)).directory(root.toFile())
                .redirectErrorStream(true).start();
        var shutdown = new Thread(() -> stop(process), "vernac-stop-maven");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            process.getOutputStream().close();
            try (var logs = process.getInputStream()) {
                logs.transferTo(System.err);
            }
            return process.waitFor();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Maven build interrupted", interrupted);
        } finally {
            stop(process);
            Runtime.getRuntime().removeShutdownHook(shutdown);
        }
    }

    private static List<String> command(Path root) {
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        var wrapper = root.resolve(windows ? "mvnw.cmd" : "mvnw");
        var arguments = new ArrayList<String>();
        if (windows) {
            // Keep the cmd command line fixed. The project path is passed as the working directory.
            arguments.addAll(List.of("cmd.exe", "/d", "/c",
                    Files.isRegularFile(wrapper) ? ".\\mvnw.cmd" : "mvn.cmd"));
        } else if (Files.isRegularFile(wrapper)) {
            arguments.addAll(List.of("sh", "./mvnw"));
        } else {
            arguments.add("mvn");
        }
        arguments.addAll(List.of("-B", "-ntp", "clean", "package"));
        return arguments;
    }

    private static void stop(Process process) {
        if (process.isAlive()) {
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroy();
        }
    }
}
