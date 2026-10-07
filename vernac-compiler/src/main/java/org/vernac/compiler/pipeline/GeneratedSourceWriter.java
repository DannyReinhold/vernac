// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import com.palantir.javapoet.JavaFile;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Preflights the actual output filesystem before replacing any generated source. */
final class GeneratedSourceWriter {
    private static final String MANIFEST = ".vernac-generated-sources";
    private static final String HEADER = "vernac-generated-sources-v1";
    private GeneratedSourceWriter() { }

    static void write(List<JavaFile> files, Path directory) throws IOException {
        Path output = directory.toAbsolutePath().normalize();
        for (Path part = output; part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part)) throw new IOException("Symbolic link in generated output path: " + part);
        }
        Files.createDirectories(output);
        Path lockPath = output.resolve(".vernac-output.lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            try (var lock = channel.tryLock()) {
                if (lock == null) throw new IOException("Vernac output is already in use: " + output);
                synchronize(files, output);
            } catch (OverlappingFileLockException e) {
                throw new IOException("Vernac output is already in use: " + output, e);
            }
        }
    }

    private static void synchronize(List<JavaFile> files, Path output) throws IOException {
        Set<Path> previous = readManifest(output);
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
            // Validate the complete ownership transition before changing sources.
            for (Path relative : previous) checkExistingSpelling(output, relative);
            for (Path relative : paths) {
                Path destination = output.resolve(relative);
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS) && !previous.contains(relative))
                    throw new IOException("Generated source conflicts with an unowned file: " + destination);
            }
            Set<Path> current = new LinkedHashSet<>(paths);
            Set<Path> owned = new LinkedHashSet<>(previous);
            owned.addAll(current);
            // Record ownership before writing: a failed run can be reconciled on the next run.
            // This is an ownership inventory, not a promise of rollback or successful compilation.
            writeManifest(output, owned);
            for (Path relative : paths) {
                Path destination = output.resolve(relative);
                Files.createDirectories(destination.getParent());
                Path source = staging.path().resolve(relative);
                if (!Files.exists(destination) || Files.mismatch(source, destination) != -1)
                    Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            for (Path relative : previous) {
                if (current.contains(relative)) continue;
                Files.deleteIfExists(output.resolve(relative));
                removeEmptyParents(output, output.resolve(relative).getParent());
            }
            writeManifest(output, current);
        }
    }

    private static Set<Path> readManifest(Path output) throws IOException {
        Path manifest = output.resolve(MANIFEST);
        checkExistingSpelling(output, Path.of(MANIFEST));
        if (!Files.exists(manifest)) return new LinkedHashSet<>();
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !HEADER.equals(lines.get(0)))
            throw new IOException("Unsupported or malformed Vernac output manifest: " + manifest);
        Set<Path> paths = new LinkedHashSet<>();
        for (String line : lines.subList(1, lines.size())) {
            try {
                Path path = Path.of(line);
                if (line.isEmpty() || line.contains("\\") || line.contains(":") || path.isAbsolute()
                        || !path.normalize().equals(path) || path.startsWith("..")
                        || !line.endsWith(".java") || !paths.add(path))
                    throw new IOException("Invalid path in Vernac output manifest: " + line);
            } catch (InvalidPathException e) {
                throw new IOException("Invalid path in Vernac output manifest: " + line, e);
            }
        }
        return paths;
    }

    private static void writeManifest(Path output, Set<Path> paths) throws IOException {
        StringBuilder content = new StringBuilder(HEADER).append('\n');
        paths.stream().map(path -> path.toString().replace('\\', '/')).sorted()
                .forEach(path -> content.append(path).append('\n'));
        Path temporary = Files.createTempFile(output, ".vernac-manifest-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            Files.move(temporary, output.resolve(MANIFEST), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void removeEmptyParents(Path output, Path directory) throws IOException {
        while (directory != null && !directory.equals(output)) {
            try { Files.delete(directory); }
            catch (DirectoryNotEmptyException e) { return; }
            catch (NoSuchFileException ignored) { }
            directory = directory.getParent();
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
            if (candidate.equals(output.resolve(relative)) && !Files.isRegularFile(candidate))
                throw new IOException("Generated output is not a regular file: " + candidate);
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
