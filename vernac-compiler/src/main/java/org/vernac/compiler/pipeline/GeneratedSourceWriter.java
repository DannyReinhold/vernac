// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import com.palantir.javapoet.JavaFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Preflights the actual output filesystem before replacing any generated source. */
final class GeneratedSourceWriter {
    private GeneratedSourceWriter() { }

    static void write(List<JavaFile> files, Path directory) throws IOException {
        Path output = directory.toAbsolutePath().normalize();
        Files.createDirectories(output);
        try (Staging staging = new Staging(Files.createTempDirectory(output, ".vernac-stage-"))) {
            Map<Path, Set<String>> directoryNames = new HashMap<>();
            List<Path> paths = new ArrayList<>();
            for (JavaFile file : files) {
                Path relative = Path.of(file.packageName().replace('.', '/')).resolve(file.typeSpec().name() + ".java");
                if (relative.isAbsolute() || relative.normalize().startsWith(".."))
                    throw new IOException("Invalid generated source path: " + relative);
                Path parent = staging.path();
                if (relative.getParent() != null) {
                    for (Path segment : relative.getParent()) {
                        Set<String> names = directoryNames.computeIfAbsent(parent.toRealPath(), ignored -> new HashSet<>());
                        Path child = parent.resolve(segment);
                        if (Files.exists(child) && !names.contains(segment.toString())) throw collision(relative);
                        Files.createDirectories(child);
                        names.add(segment.toString());
                        parent = child;
                    }
                }
                try {
                    Files.writeString(staging.path().resolve(relative), file.toString(), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                } catch (FileAlreadyExistsException e) {
                    throw collision(relative);
                }
                checkExistingSpelling(output, relative);
                paths.add(relative);
            }
            // All spelling/collision checks complete before any existing source is replaced.
            for (Path relative : paths) {
                Path destination = output.resolve(relative);
                Files.createDirectories(destination.getParent());
                Files.copy(staging.path().resolve(relative), destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void checkExistingSpelling(Path output, Path relative) throws IOException {
        Path parent = output;
        for (Path segment : relative) {
            Path candidate = parent.resolve(segment);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) return;
            if (Files.isSymbolicLink(candidate)) throw new IOException("Generated output must not follow symbolic links: " + candidate);
            try (var children = Files.list(parent)) {
                if (children.noneMatch(child -> child.getFileName().toString().equals(segment.toString())))
                    throw collision(relative);
            }
            parent = candidate;
        }
    }

    private static IOException collision(Path path) {
        return new IOException("Generated source path collision on this filesystem: " + path
                + ". Distinct Vernac names must have distinct output paths; no existing sources were replaced.");
    }

    private record Staging(Path path) implements AutoCloseable {
        @Override public void close() throws IOException {
            try (var entries = Files.walk(path)) {
                for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(entry);
            }
        }
    }
}
