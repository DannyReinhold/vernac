// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.persistence;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static java.nio.file.StandardOpenOption.CREATE_NEW;

/** A source-controlled linear chain. SQL may be reviewed/edited; replay verifies its meaning. */
public final class SchemaHistory {
    public record Snapshot(int formatVersion, String migration, String previousFingerprint,
                           String modelFingerprint, SchemaModel model) { }
    private final Path history, migrations;
    public SchemaHistory(Path history, Path migrations) { this.history = history; this.migrations = migrations; }
    public SchemaModel read() throws IOException {
        SchemaModel current = SchemaModel.empty();
        if (!Files.exists(history)) return current;
        List<Path> files;
        try (var stream = Files.list(history)) { files = stream.filter(p -> p.toString().endsWith(".json")).sorted().toList(); }
        for (Path file : files) {
            Snapshot s = SchemaModel.JSON.readValue(Files.readString(file), Snapshot.class);
            if (s.formatVersion() != 1 || !s.migration().matches("V[0-9]{17}__[a-z0-9_]+\\.sql")) throw new IOException("Invalid schema history entry: " + file.getFileName());
            if (!file.getFileName().toString().equals(s.migration().replace(".sql", ".json"))) throw new IOException("History filename mismatch");
            if (!s.previousFingerprint().equals(current.fingerprint())) throw new IOException("Schema history diverges at " + s.migration() + ". Rebase and regenerate unpublished candidates; do not merge snapshots blindly.");
            if (!s.modelFingerprint().equals(s.model().fingerprint())) throw new IOException("Schema snapshot fingerprint mismatch: " + s.migration());
            if (!Files.isRegularFile(migrations.resolve(s.migration()))) throw new IOException("Missing migration: " + s.migration());
            current = s.model();
        }
        return current;
    }
    public Path append(String version, String description, SchemaModel expectedBase, SchemaModel model, MigrationPlanner.Plan plan) throws IOException {
        if (!version.matches("[0-9]{17}") || !description.matches("[a-z0-9]+(?:_[a-z0-9]+)*"))
            throw new IllegalArgumentException("Version must contain 17 UTC timestamp digits; description must use lowercase words separated by underscores.");
        if (!plan.changed()) throw new IllegalArgumentException("No schema changes; no migration created.");
        Files.createDirectories(history); Files.createDirectories(migrations);
        Path lock = history.resolve(".generation.lock");
        Files.writeString(lock, "Migration generation in progress", CREATE_NEW);
        try {
            if (!read().equals(expectedBase)) throw new IOException("Schema history changed during generation; retry against the new head.");
            String filename = "V" + version + "__" + description + ".sql";
            // UTC timestamps reduce collisions but do not replace branch/ordering checks.
            try (var stream = Files.list(migrations)) {
                for (Path p : stream.toList()) {
                    String n = p.getFileName().toString();
                    if (n.matches("V[0-9]{17}__.*\\.sql") && n.substring(1,18).compareTo(version) >= 0)
                        throw new IOException("Migration version must be greater than existing timestamp versions: " + n);
                }
            }
            Path sql = migrations.resolve(filename), json = history.resolve(filename.replace(".sql", ".json"));
            if (Files.exists(sql) || Files.exists(json)) throw new IOException("Migration already exists: " + filename);
            Snapshot snapshot = new Snapshot(1, filename, expectedBase.fingerprint(), model.fingerprint(), model);
            String text = SchemaModel.JSON.writeValueAsString(snapshot) + "\n";
            Files.writeString(sql, plan.sql(), CREATE_NEW);
            try { Files.writeString(json, text, CREATE_NEW); }
            catch (IOException e) { Files.deleteIfExists(sql); throw e; }
            return sql;
        } finally { Files.deleteIfExists(lock); }
    }
}
