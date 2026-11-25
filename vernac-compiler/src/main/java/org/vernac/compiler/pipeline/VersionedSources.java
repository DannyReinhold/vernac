// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Owns one client's source snapshot and publishes complete edit batches by revision. */
public final class VersionedSources {
    public sealed interface Edit permits Put, Delete, Move { }

    public record Put(Path path, String text) implements Edit {
        public Put { Objects.requireNonNull(text); }
    }

    public record Delete(Path path) implements Edit { }

    public record Move(Path from, Path to) implements Edit { }

    public record Snapshot(long revision, Map<Path, String> sources) {
        public Snapshot { sources = Map.copyOf(sources); }
    }

    public static final class Conflict extends IllegalStateException {
        private final long currentRevision;

        private Conflict(long currentRevision) {
            super("Stale source revision; current revision is " + currentRevision);
            this.currentRevision = currentRevision;
        }

        public long currentRevision() { return currentRevision; }
    }

    private final Path root;
    private Snapshot current;

    public VersionedSources(Path root, Map<Path, String> sources) {
        this.root = root.toAbsolutePath().normalize();
        Map<Path, String> normalized = new HashMap<>();
        sources.forEach((path, text) -> {
            if (normalized.putIfAbsent(normalize(path), Objects.requireNonNull(text)) != null)
                throw new IllegalArgumentException("Duplicate source path: " + path);
        });
        current = new Snapshot(0, normalized);
    }

    public synchronized Snapshot snapshot() {
        return current;
    }

    public synchronized Snapshot apply(long baseRevision, List<Edit> edits) {
        if (baseRevision != current.revision()) throw new Conflict(current.revision());
        var next = new HashMap<>(current.sources());
        for (var edit : List.copyOf(edits)) {
            switch (edit) {
                case Put put -> next.put(normalize(put.path()), put.text());
                case Delete delete -> remove(next, normalize(delete.path()));
                case Move move -> {
                    Path destination = normalize(move.to());
                    if (next.containsKey(destination))
                        throw new IllegalArgumentException("Source already exists: " + destination);
                    next.put(destination, remove(next, normalize(move.from())));
                }
            }
        }
        if (!next.equals(current.sources())) current = new Snapshot(Math.incrementExact(baseRevision), next);
        return current;
    }

    private Path normalize(Path path) {
        Path absolute = root.resolve(path).normalize();
        if (!absolute.startsWith(root) || absolute.equals(root) || !absolute.getFileName().toString().endsWith(".vernac"))
            throw new IllegalArgumentException("Expected a Vernac source below " + root + ": " + path);
        return absolute;
    }

    private static String remove(Map<Path, String> sources, Path path) {
        String text = sources.remove(path);
        if (text == null) throw new IllegalArgumentException("Source does not exist: " + path);
        return text;
    }
}
