// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SchemaFingerprintPortabilityTest {
    @TempDir Path directory;

    @Test void emptySchemaMatchesThePublishedInitialFingerprint() {
        assertEquals("80a947793d1802db05dd3d1246d9b9534a6c51dd00f17d796277da3e0441da66",
                SchemaModel.empty().fingerprint());
    }

    @Test void historyCanBeReadWithBothJvmLineSeparators() throws Exception {
        Path history = directory.resolve("history");
        Path migrations = directory.resolve("migration");
        var model = new SchemaModel(1, 1, List.of(new SchemaModel.Table(
                "org.example", "Tour", "org.example.Tour",
                List.of(new SchemaModel.Column("id", "UUID", false, "UUID", "id")),
                List.of("id"), List.of())), Map.of());
        new SchemaHistory(history, migrations).append("20261009090000000", "initial",
                SchemaModel.empty(), model,
                new MigrationPlanner().plan(SchemaModel.empty(), model, MigrationPlanner.Options.safe()));

        // Git may check out a source-controlled JSON snapshot with CRLF on Windows.
        try (var files = Files.list(history)) {
            for (Path file : files.toList()) {
                Files.writeString(file, Files.readString(file).replace("\r\n", "\n").replace("\n", "\r\n"));
            }
        }
        for (String separator : List.of("\n", "\r\n")) {
            String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            Path java = Path.of(System.getProperty("java.home"), "bin", executable);
            Path output = directory.resolve(separator.length() + ".log");
            Process process = new ProcessBuilder(java.toString(), "-Dline.separator=" + separator,
                    "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    ReadHistory.class.getName(), history.toString(), migrations.toString())
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                fail("Schema history portability subprocess timed out");
            }
            String result = Files.readString(output);
            assertEquals(0, process.exitValue(), result);
            assertEquals(model.fingerprint(), result.strip());
        }
    }

    public static class ReadHistory {
        public static void main(String[] args) throws Exception {
            var model = new SchemaHistory(Path.of(args[0]), Path.of(args[1])).read();
            if (model.json().contains("\r")) throw new AssertionError("Schema JSON must use LF");
            System.out.print(model.fingerprint());
        }
    }
}
