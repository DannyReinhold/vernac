// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

/** Process-recoverable status and results for runs against saved sources. */
final class ScenarioRunArchive {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int RETAINED = 32;
    // Some file systems release JVM-owned locks when another channel on the same file closes.
    private static final Set<Path> LOCAL_LEASES = ConcurrentHashMap.newKeySet();
    private final Path directory;

    ScenarioRunArchive(Path workspace) {
        directory = workspace.resolve("target/vernac-scenario-runs-v1");
    }

    record Lease(Path path, FileChannel channel) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try {
                channel.close();
            } finally {
                LOCAL_LEASES.remove(path);
            }
        }
    }

    Lease begin(ScenarioRunJobs.Status status) throws IOException {
        Files.createDirectories(directory.getParent());
        checkDirectory(directory.getParent());
        Files.createDirectories(directory);
        checkDirectory(directory);
        var path = lease(status.runId());
        if (!LOCAL_LEASES.add(path)) throw new IOException("Scenario run is already owned: " + status.runId());
        FileChannel channel = null;
        try {
            channel = FileChannel.open(path, CREATE_NEW, WRITE, NOFOLLOW_LINKS);
            channel.lock();
            write(status);
            return new Lease(path, channel);
        } catch (IOException | RuntimeException failure) {
            try {
                if (channel != null) {
                    channel.close();
                    Files.deleteIfExists(path);
                }
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            } finally {
                LOCAL_LEASES.remove(path);
            }
            throw failure;
        }
    }

    void write(ScenarioRunJobs.Status status) throws IOException {
        var temporary = Files.createTempFile(directory, "run-", ".tmp");
        try {
            byte[] json = JSON.writeValueAsBytes(status);
            try (var output = FileChannel.open(temporary, WRITE)) {
                var bytes = ByteBuffer.wrap(json);
                while (bytes.hasRemaining()) output.write(bytes);
                output.force(true);
            }
            Files.move(temporary, record(status.runId()), ATOMIC_MOVE, REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    ScenarioRunJobs.Status lookup(String id) throws IOException {
        if (!validId(id) || Files.notExists(directory, NOFOLLOW_LINKS)) return null;
        checkDirectory(directory.getParent());
        checkDirectory(directory);
        var status = read(id);
        if (status == null || status.state() != ScenarioRunJobs.State.RUNNING) return status;
        var path = lease(id);
        if (!LOCAL_LEASES.add(path)) return status;
        // The OS releases this lock on process exit, including abrupt termination.
        try (var channel = FileChannel.open(path, WRITE, NOFOLLOW_LINKS)) {
            try (var owner = channel.tryLock()) {
                if (owner == null) return status;
                var latest = read(id);
                if (latest == null || latest.state() != ScenarioRunJobs.State.RUNNING) return latest;
                var interrupted = new ScenarioRunJobs.Status(id, latest.revision(), ScenarioRunJobs.State.INTERRUPTED,
                        null, new ScenarioDaemon.Problem("run_interrupted", "The daemon exited before publishing a result", null));
                write(interrupted);
                return interrupted;
            }
        } catch (NoSuchFileException expired) {
            return read(id);
        } finally {
            LOCAL_LEASES.remove(path);
        }
    }

    void prune() {
        // Retention is housekeeping. A completed result remains usable if cleanup fails.
        try (var paths = Files.list(directory)) {
            var candidates = paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> Files.isRegularFile(path, NOFOLLOW_LINKS))
                    .sorted(Comparator.comparingLong(ScenarioRunArchive::modified).reversed()).toList();
            if (candidates.size() <= RETAINED) return;
            int terminal = 0;
            for (var path : candidates) {
                String name = path.getFileName().toString();
                String id = name.substring(0, name.length() - 5);
                try {
                    var status = lookup(id);
                    if (status == null || status.state() == ScenarioRunJobs.State.RUNNING) continue;
                    if (++terminal > RETAINED) {
                        Files.deleteIfExists(path);
                        Files.deleteIfExists(lease(id));
                    }
                } catch (IOException ignored) {
                    // A concurrently removed or unreadable entry does not prevent cleanup of other entries.
                }
            }
        } catch (IOException ignored) {
            // A later completion can retry cleanup.
        }
    }

    private ScenarioRunJobs.Status read(String id) throws IOException {
        var file = record(id);
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) return null;
        try (var input = Files.newInputStream(file, NOFOLLOW_LINKS)) {
            var status = JSON.readValue(input, ScenarioRunJobs.Status.class);
            if (status == null || !id.equals(status.runId()) || status.state() == null) {
                throw new IOException("Invalid archived scenario run: " + id);
            }
            return status;
        } catch (NoSuchFileException expired) {
            return null;
        } catch (JacksonException malformed) {
            throw new IOException("Invalid archived scenario run: " + id, malformed);
        }
    }

    private static void checkDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path, NOFOLLOW_LINKS)) {
            throw new IOException("Scenario run archive must be a local directory: " + path);
        }
    }

    private static boolean validId(String id) {
        try {
            return UUID.fromString(id).toString().equals(id);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private Path record(String id) { return directory.resolve(id + ".json"); }
    private Path lease(String id) { return directory.resolve(id + ".lock"); }

    private static long modified(Path file) {
        try {
            return Files.getLastModifiedTime(file, NOFOLLOW_LINKS).toMillis();
        } catch (IOException ignored) {
            return Long.MIN_VALUE;
        }
    }
}
