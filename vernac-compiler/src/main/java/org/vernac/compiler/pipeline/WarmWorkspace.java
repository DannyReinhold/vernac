// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.vernac.compiler.analyzer.SemanticValidationException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/** Owns the demand-started process for saved-source checks and scenario execution. */
final class WarmWorkspace {
    private static final int IDLE_MILLIS = 5 * 60 * 1_000;
    private static final int START_MILLIS = 10_000;
    private static final int REPLY_MILLIS = 60_000;
    private static final int MAX_REPLY = 16 * 1024 * 1024;
    private static final String PROTOCOL = "vernac-workspace-v2";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HexFormat HEX = HexFormat.of();

    private WarmWorkspace() { }

    record Endpoint(int port, String token, long pid) { }

    static JsonNode check(Path root, String profile) throws IOException {
        return request(root, profile, null);
    }

    static JsonNode testScenario(Path root, String profile, String scenario) throws IOException {
        if (scenario == null || scenario.isBlank()) throw new IllegalArgumentException("Expected a scenario ID");
        return request(root, profile, scenario);
    }

    private static JsonNode request(Path root, String profile, String scenario) throws IOException {
        root = root.toRealPath();
        Path directory = directory(root, profile);
        var reply = tryRequest(directory, scenario);
        if (reply != null) return reply;

        try (var startup = lockFile(directory, "startup.lock"); var lock = startup.lock()) {
            reply = tryRequest(directory, scenario);
            if (reply != null) return reply;
            Path java = Path.of(System.getProperty("java.home"), "bin", "java");
            if (!Files.isRegularFile(java)) java = java.resolveSibling("java.exe");
            var child = new ProcessBuilder(java.toString(), "-Duser.home=" + System.getProperty("user.home"),
                    "-cp", System.getProperty("java.class.path"), WarmWorkspace.class.getName(),
                    root.toString(), profile == null ? "" : profile)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            child.getOutputStream().close();
            long deadline = System.nanoTime() + START_MILLIS * 1_000_000L;
            while (System.nanoTime() < deadline) {
                reply = tryRequest(directory, scenario);
                if (reply != null) return reply;
                if (!child.isAlive() && child.exitValue() != 0)
                    throw new IOException("The warm Vernac workspace failed to start (exit " + child.exitValue() + ")");
                try {
                    Thread.sleep(25);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while starting the warm Vernac workspace", interrupted);
                }
            }
            throw new IOException("The warm Vernac workspace did not start");
        }
    }

    private static Path directory(Path root, String profile) throws IOException {
        var key = new StringBuilder(PROTOCOL).append('\n').append(root).append('\n').append(profile)
                .append('\n').append(System.getProperty("java.home")).append('\n').append(Runtime.version());
        for (Path entry : ScenarioDaemonLauncher.classpath()) {
            key.append('\n').append(entry.toAbsolutePath().normalize());
            if (Files.isRegularFile(entry))
                key.append('\n').append(Files.size(entry)).append('\n').append(Files.getLastModifiedTime(entry));
        }
        var home = Path.of(System.getProperty("user.home")).resolve(".vernac-workspaces");
        createPrivateDirectory(home);
        Path directory = home.resolve(HEX.formatHex(digest(key.toString())));
        createPrivateDirectory(directory);
        return directory;
    }

    private static void createPrivateDirectory(Path directory) throws IOException {
        var view = Files.getFileStore(directory.getParent()).supportsFileAttributeView("posix");
        try {
            if (view) Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
            else Files.createDirectory(directory);
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            // Existing working directories are shared by clients of the same user.
        }
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS) || (view &&
                !Files.getPosixFilePermissions(directory, NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rwx------"))))
            throw new IOException("Expected a private Vernac workspace directory: " + directory);
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }

    private static FileChannel lockFile(Path directory, String name) throws IOException {
        return FileChannel.open(directory.resolve(name), CREATE, WRITE, NOFOLLOW_LINKS);
    }

    private static JsonNode tryRequest(Path directory, String scenario) throws IOException {
        Path file = directory.resolve("endpoint");
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) return null;
        Endpoint endpoint;
        try {
            endpoint = JSON.readValue(Files.readString(file), Endpoint.class);
        } catch (NoSuchFileException idle) {
            return null;
        }
        try (var socket = new Socket()) {
            try {
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), endpoint.port()), 500);
                socket.setSoTimeout(REPLY_MILLIS);
                var request = new DataOutputStream(socket.getOutputStream());
                request.writeUTF(PROTOCOL);
                request.writeUTF(endpoint.token());
                request.writeBoolean(scenario != null);
                if (scenario != null) request.writeUTF(scenario);
                request.flush();
                var input = new DataInputStream(socket.getInputStream());
                if (!input.readBoolean()) return null;
                // Once a worker accepts the request, surface errors instead of replaying it.
                int size;
                try {
                    size = input.readInt();
                } catch (EOFException failure) {
                    throw new IOException("The warm Vernac workspace stopped during the request", failure);
                }
                if (size < 1 || size > MAX_REPLY) throw new IOException("Invalid warm Vernac workspace response");
                byte[] payload = input.readNBytes(size);
                if (payload.length != size) throw new IOException("Incomplete warm Vernac workspace response");
                return JSON.readTree(payload);
            } catch (java.net.ConnectException | EOFException failure) {
                return null;
            }
        }
    }

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args[0]);
        String profile = args[1].isEmpty() ? null : args[1];
        Path directory = directory(root, profile);
        Path endpointFile = directory.resolve("endpoint");
        try (var owner = lockFile(directory, "owner.lock"); var lock = owner.tryLock()) {
            if (lock == null) return;
            try (var server = new ServerSocket()) {
                server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
                server.setSoTimeout(IDLE_MILLIS);
                var endpoint = new Endpoint(server.getLocalPort(), UUID.randomUUID().toString(), ProcessHandle.current().pid());
                Path temporary = Files.createTempFile(directory, "endpoint-", ".tmp");
                try {
                    Files.writeString(temporary, JSON.writeValueAsString(endpoint));
                    Files.move(temporary, endpointFile, ATOMIC_MOVE, REPLACE_EXISTING);
                    serve(server, endpoint, root, profile);
                } finally {
                    Files.deleteIfExists(temporary);
                    Files.deleteIfExists(endpointFile);
                }
            }
        }
    }

    private static void serve(ServerSocket server, Endpoint endpoint, Path root, String profile) throws IOException {
        ScenarioWorkspace workspace = null;
        var executor = ScenarioDaemonLauncher.executor(root, ScenarioDaemonLauncher.classpath());
        Map<Path, ProjectOutput> previousOutputs = null;
        long revisionBase = 0;
        while (true) {
            Socket connection;
            try {
                connection = server.accept();
            } catch (SocketTimeoutException idle) {
                return;
            }
            try (var socket = connection) {
                socket.setSoTimeout(500);
                var input = new DataInputStream(socket.getInputStream());
                var output = new DataOutputStream(socket.getOutputStream());
                boolean valid = PROTOCOL.equals(input.readUTF()) && MessageDigest.isEqual(
                        endpoint.token().getBytes(StandardCharsets.UTF_8), input.readUTF().getBytes(StandardCharsets.UTF_8));
                String scenario = valid && input.readBoolean() ? input.readUTF() : null;
                output.writeBoolean(valid);
                output.flush();
                if (!valid) continue;
                Object reply;
                try {
                    if (workspace == null) {
                        workspace = ScenarioWorkspace.openSaved(root, executor, profile);
                    } else {
                        try {
                            workspace.refreshSavedSources(workspace.revision());
                        } catch (ScenarioWorkspace.ConfigurationChanged change) {
                            long revision = Math.addExact(revisionBase, workspace.revision());
                            workspace = ScenarioWorkspace.openSaved(root, executor, profile);
                            executor.invalidate();
                            revisionBase = Math.incrementExact(revision);
                        }
                    }
                    if (scenario == null) {
                        var check = workspace.check(workspace.revision());
                        reply = Map.of("revision", Math.addExact(revisionBase, check.revision()),
                                "valid", check.valid(), "diagnostics", check.diagnostics());
                    } else {
                        var outputs = projectOutputs(root);
                        if (!Objects.equals(outputs, previousOutputs)) executor.invalidate();
                        previousOutputs = outputs;
                        var run = workspace.runScenario(workspace.revision(), scenario);
                        reply = new ScenarioRunSession.Run(Math.addExact(revisionBase, run.revision()),
                                run.selected(), run.outcomes());
                    }
                } catch (SemanticValidationException failure) {
                    reply = Map.of("error", "diagnostics", "diagnostics", failure.diagnostics());
                } catch (IOException | RuntimeException failure) {
                    reply = Map.of("error", "execution_error", "message", String.valueOf(failure.getMessage()));
                }
                byte[] payload = JSON.writeValueAsBytes(reply);
                if (payload.length > MAX_REPLY) payload = JSON.writeValueAsBytes(
                        Map.of("error", "execution_error", "message", "Warm workspace response too large"));
                output.writeInt(payload.length);
                output.write(payload);
                output.flush();
            } catch (IOException gone) {
                // The next client can reuse the workspace after a disconnected request.
            }
        }
    }

    private record ProjectOutput(FileTime modified, long size, Object fileKey) { }

    private static Map<Path, ProjectOutput> projectOutputs(Path root) throws IOException {
        var outputs = new HashMap<Path, ProjectOutput>();
        for (var directory : List.of(root.resolve("target/classes"), root.resolve("target/test-classes"))) {
            if (!Files.isDirectory(directory)) continue;
            try (var files = Files.walk(directory)) {
                for (Path path : files.toList()) {
                    var attributes = Files.readAttributes(path, BasicFileAttributes.class);
                    if (attributes.isRegularFile()) outputs.put(path,
                            new ProjectOutput(attributes.lastModifiedTime(), attributes.size(), attributes.fileKey()));
                }
            }
        }
        return outputs;
    }
}
