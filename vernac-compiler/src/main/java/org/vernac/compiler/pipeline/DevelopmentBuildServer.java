// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.vernac.compiler.modules.ModuleArtifacts;
import org.vernac.runtime.DevelopmentRevisions;
import tools.jackson.databind.json.JsonMapper;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/** Opt-in local transport that keeps the development compiler warm alongside the application. */
public final class DevelopmentBuildServer implements AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int PROTOCOL = 1;
    private static final int MAX_REPLY = 4 * 1024 * 1024;
    private static final String DIRECTORY = ".vernac-dev";
    private static final String ENDPOINT = "endpoint";

    private final DevelopmentWorkspace baseline;
    private final DevelopmentBundleCompiler compiler;
    private final DevelopmentRevisions revisions;
    private final ClassLoader loader;
    private final Path endpoint;
    private final ServerSocket server;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final String token = UUID.randomUUID().toString();
    private final AtomicBoolean closed = new AtomicBoolean();
    private long sequence;

    private DevelopmentBuildServer(DevelopmentWorkspace baseline, DevelopmentRevisions revisions, ClassLoader loader,
                                   FileChannel lockChannel, FileLock lock, ServerSocket server) throws IOException {
        this.baseline = baseline;
        this.revisions = revisions;
        this.loader = loader;
        this.lockChannel = lockChannel;
        this.lock = lock;
        this.server = server;
        compiler = new DevelopmentBundleCompiler(baseline.sourceRoot(), baseline.configuration(), List.of(),
                baseline.snapshot(0));
        endpoint = baseline.root().resolve(DIRECTORY).resolve(ENDPOINT);
        publishEndpoint();
        Thread.ofVirtual().name("vernac-development-build").start(this::serve);
    }

    /** Opens the compiler using the verified production baseline packaged by the Maven build. */
    public static DevelopmentBuildServer open(Path workspace, DevelopmentRevisions revisions, ClassLoader loader)
            throws IOException {
        var baseline = DevelopmentWorkspace.capture(workspace);
        if (!ModuleArtifacts.editorModules(baseline.sourceRoot()).isEmpty()) {
            throw new IOException("Development builds do not support Vernac module dependencies");
        }
        try (var fingerprint = loader.getResourceAsStream(DevelopmentWorkspace.BASELINE_RESOURCE)) {
            baseline.verifyPackaged(fingerprint);
        }
        Path directory = baseline.root().resolve(DIRECTORY);
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) throw new IOException("Development workspace directory is not local");
        var channel = FileChannel.open(directory.resolve("session.lock"), CREATE, WRITE, NOFOLLOW_LINKS);
        try {
            var lock = channel.tryLock();
            if (lock == null) throw new IOException("A development application is already active for this workspace");
            var server = new ServerSocket();
            try {
                server.bind(new InetSocketAddress(loopback(), 0));
                return new DevelopmentBuildServer(baseline, revisions, loader, channel, lock, server);
            } catch (IOException | RuntimeException failure) {
                server.close();
                throw failure;
            }
        } catch (OverlappingFileLockException failure) {
            channel.close();
            throw new IOException("A development application is already active for this workspace", failure);
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    private static InetAddress loopback() throws IOException {
        return InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
    }

    private void publishEndpoint() throws IOException {
        var temporary = Files.createTempFile(endpoint.getParent(), "vernac-dev-", ".tmp");
        try {
            Files.writeString(temporary, server.getLocalPort() + "\n" + token + "\n");
            Files.move(temporary, endpoint, ATOMIC_MOVE, REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void serve() {
        while (!closed.get()) {
            try (var socket = server.accept()) {
                socket.setSoTimeout(2_000);
                var input = new DataInputStream(socket.getInputStream());
                if (input.readInt() != PROTOCOL || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                        input.readUTF().getBytes(StandardCharsets.UTF_8))) continue;
                var reply = new DataOutputStream(socket.getOutputStream());
                byte[] payload = JSON.writeValueAsBytes(build());
                if (payload.length > MAX_REPLY) payload = JSON.writeValueAsBytes(failure("Development reply too large"));
                reply.writeInt(payload.length);
                reply.write(payload);
                reply.flush();
            } catch (IOException ignored) {
                // A disconnected client does not end the development session.
            }
        }
    }

    private Map<String, ?> build() {
        long requested = ++sequence;
        long base = revisions.revision();
        try {
            var current = DevelopmentWorkspace.capture(baseline.root());
            if (!baseline.fixedInputs().equals(current.fixedInputs())) {
                return result("rebuild_required", requested, base, List.of());
            }
            var result = compiler.compile(current.snapshot(requested));
            if (result.status() == DevelopmentBundleCompiler.Status.INVALID) {
                return result("invalid", requested, base, result.diagnostics());
            }
            if (result.status() == DevelopmentBundleCompiler.Status.REBUILD_REQUIRED) {
                return result("rebuild_required", requested, base, result.diagnostics());
            }
            var bundle = result.bundle().orElseThrow();
            var updates = new HashMap<DevelopmentRevisions.BehaviorKey, DevelopmentRevisions.BehaviorUpdate>();
            for (var patch : bundle.behaviors()) {
                var key = new DevelopmentRevisions.BehaviorKey(Class.forName(patch.implementation(), true, loader),
                        patch.program());
                updates.put(key, new DevelopmentRevisions.BehaviorUpdate(patch.layout(), patch.sourceMap()));
            }
            return result("activated", requested, revisions.activate(base, updates, bundle.templates()), result.diagnostics());
        } catch (IOException | ClassNotFoundException | RuntimeException | LinkageError failure) {
            return failure(failure.toString());
        }
    }

    private static Map<String, ?> result(String status, long requested, long active, List<?> diagnostics) {
        return Map.of("backend", "vernac-dev", "status", status, "requestedRevision", requested,
                "activeRevision", active, "diagnostics", diagnostics);
    }

    private Map<String, ?> failure(String message) {
        return Map.of("backend", "vernac-dev", "status", "execution_error", "requestedRevision", sequence,
                "activeRevision", revisions.revision(), "message", message);
    }

    static byte[] build(Path workspace) throws IOException {
        var descriptor = workspace.resolve(DIRECTORY).resolve(ENDPOINT);
        if (!Files.isRegularFile(descriptor, NOFOLLOW_LINKS)) {
            throw new IOException("No development application is running for " + workspace);
        }
        var lines = Files.readAllLines(descriptor, StandardCharsets.UTF_8);
        if (lines.size() != 2) throw new IOException("Invalid development endpoint metadata");
        int port;
        try {
            port = Integer.parseInt(lines.getFirst());
            UUID.fromString(lines.getLast());
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid development endpoint metadata", failure);
        }
        if (port < 1 || port > 65535) throw new IOException("Invalid development endpoint port");
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(loopback(), port), 2_000);
            socket.setSoTimeout(30_000);
            var output = new DataOutputStream(socket.getOutputStream());
            output.writeInt(PROTOCOL);
            output.writeUTF(lines.getLast());
            output.flush();
            var input = new DataInputStream(socket.getInputStream());
            int length = input.readInt();
            if (length < 1 || length > MAX_REPLY) throw new IOException("Invalid development reply length");
            byte[] payload = input.readNBytes(length);
            if (payload.length != length) throw new IOException("Incomplete development reply");
            return payload;
        } catch (IOException failure) {
            throw new IOException("Could not reach the development application for " + workspace, failure);
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        try {
            server.close();
            Files.deleteIfExists(endpoint);
        } finally {
            try {
                lock.close();
            } finally {
                lockChannel.close();
            }
        }
    }
}
