// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Conventional Maven source inputs used to verify the packaged development baseline. */
public record DevelopmentWorkspace(Path root, Map<Path, String> sources, Map<Path, String> configuration,
                                   String fixedInputs, String fingerprint) {
    public static final String BASELINE_RESOURCE = "META-INF/vernac/development-baseline-v1.txt";

    public DevelopmentWorkspace {
        sources = Map.copyOf(sources);
        configuration = Map.copyOf(configuration);
    }

    public static DevelopmentWorkspace capture(Path workspace) throws IOException {
        var root = workspace.toRealPath();
        if (!Files.isRegularFile(root.resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Development builds need a conventional Maven workspace with pom.xml");
        }
        var files = new TreeMap<String, Path>();
        add(files, root, root.resolve("pom.xml"));
        for (String name : new String[]{"vernac-application.vapplication", "vernac-module.vmodule"}) {
            var file = root.resolve(name);
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) add(files, root, file);
        }
        for (String directory : new String[]{"src/main", ".mvn"}) {
            var tree = root.resolve(directory);
            if (Files.notExists(tree, LinkOption.NOFOLLOW_LINKS)) continue;
            try (var paths = Files.walk(tree)) {
                for (var path : paths.filter(file -> !Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)).toList()) {
                    add(files, root, path);
                }
            }
        }
        var sources = new TreeMap<Path, String>();
        var configuration = new TreeMap<Path, String>();
        var fixed = digest();
        var vernac = digest();
        for (var entry : files.entrySet()) {
            var file = entry.getValue();
            var name = entry.getKey();
            if (name.startsWith("src/main/vernac/") && name.endsWith(".vernac")) {
                var source = Files.readString(file);
                sources.put(file, source);
                hash(vernac, name, source.getBytes(StandardCharsets.UTF_8));
            } else {
                byte[] contents = Files.readAllBytes(file);
                hash(fixed, name, contents);
                if (name.endsWith(".vmodule") || name.equals("vernac-application.vapplication")) {
                    configuration.put(file, new String(contents, StandardCharsets.UTF_8));
                }
            }
        }
        var fixedHash = HexFormat.of().formatHex(fixed.digest());
        hash(vernac, "fixed-inputs", HexFormat.of().parseHex(fixedHash));
        return new DevelopmentWorkspace(root, sources, configuration, fixedHash,
                HexFormat.of().formatHex(vernac.digest()));
    }

    private static void add(Map<String, Path> files, Path root, Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Development build input is not a regular file: " + file);
        }
        files.put(root.relativize(file).toString().replace('\\', '/'), file);
    }

    public Path sourceRoot() {
        return root.resolve("src/main/vernac");
    }

    public VersionedSources.Snapshot snapshot(long revision) {
        return new VersionedSources.Snapshot(revision, sources);
    }

    public void verifyPackaged(InputStream packaged) throws IOException {
        if (packaged == null || !(fingerprint + "\n").equals(new String(packaged.readNBytes(66), StandardCharsets.UTF_8))) {
            throw new IOException("Development baseline does not match the saved project. Rebuild and restart using -Dvernac.development=true.");
        }
    }

    private static void hash(MessageDigest digest, String name, byte[] contents) {
        var encoded = name.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(8).putInt(encoded.length).putInt(contents.length).array());
        digest.update(encoded);
        digest.update(contents);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
