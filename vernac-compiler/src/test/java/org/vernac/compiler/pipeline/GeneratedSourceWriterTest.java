// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.TypeSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GeneratedSourceWriterTest {
    @TempDir Path output;
    private JavaFile file(String namespace, String name) {
        return JavaFile.builder(namespace, TypeSpec.classBuilder(name).build()).build();
    }

    @Test void duplicateOutputsAreRejectedBeforeReplacingExistingSources() throws Exception {
        Path target = output.resolve("org/test/Model.java");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "keep this source");
        var error = assertThrows(IOException.class, () -> GeneratedSourceWriter.write(
                List.of(file("org.test", "Model"), file("org.test", "Model")), output));
        assertTrue(error.getMessage().contains("collision"));
        assertEquals("keep this source", Files.readString(target));
        try (var paths = Files.list(output)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith(".vernac-stage-")));
        }
    }

    @Test void respectsActualFilesystemCaseAndNormalizationRules() throws Exception {
        Path probe = output.resolve("Größe");
        Files.createFile(probe);
        boolean caseAliased = Files.exists(output.resolve("größe"));
        boolean normalizedAlias = Files.exists(output.resolve("Gro\u0308ße"));
        Files.delete(probe);
        if (caseAliased) assertThrows(IOException.class, () -> GeneratedSourceWriter.write(
                List.of(file("p", "Größe"), file("p", "größe")), output));
        else assertDoesNotThrow(() -> GeneratedSourceWriter.write(List.of(file("p", "Größe"), file("p", "größe")), output));
        if (normalizedAlias) assertThrows(IOException.class, () -> GeneratedSourceWriter.write(
                List.of(file("q", "Größe"), file("q", "Gro\u0308ße")), output));
        else assertDoesNotThrow(() -> GeneratedSourceWriter.write(List.of(file("q", "Größe"), file("q", "Gro\u0308ße")), output));
    }

    @Test void removesRenamedAndMovedSourcesButPreservesUnrelatedFiles() throws Exception {
        GeneratedSourceWriter.write(List.of(file("old.namespace", "OldName")), output);
        Files.writeString(output.resolve("notes.txt"), "keep");
        GeneratedSourceWriter.write(List.of(file("newnamespace", "NewName")), output);
        assertFalse(Files.exists(output.resolve("old")));
        assertTrue(Files.exists(output.resolve("newnamespace/NewName.java")));
        assertEquals("keep", Files.readString(output.resolve("notes.txt")));
        GeneratedSourceWriter.write(List.of(), output);
        assertFalse(Files.exists(output.resolve("newnamespace")));
        assertTrue(Files.exists(output.resolve("notes.txt")));
    }

    @Test void unchangedGenerationPreservesSourceTimestamp() throws Exception {
        var sources = List.of(file("p", "Model"));
        GeneratedSourceWriter.write(sources, output);
        Path source = output.resolve("p/Model.java");
        var timestamp = java.nio.file.attribute.FileTime.fromMillis(100000);
        Files.setLastModifiedTime(source, timestamp);
        GeneratedSourceWriter.write(sources, output);
        assertEquals(timestamp, Files.getLastModifiedTime(source));
    }

    @Test void refusesUnownedFilesAndUnsafeManifests() throws Exception {
        Files.createDirectories(output.resolve("p"));
        Path source = output.resolve("p/Model.java");
        Files.writeString(source, "handwritten");
        assertThrows(IOException.class, () -> GeneratedSourceWriter.write(List.of(file("p", "Model")), output));
        assertEquals("handwritten", Files.readString(source));
        Files.writeString(output.resolve(".vernac-generated-sources"),
                "vernac-generated-sources-v1\n../outside.java\n");
        assertThrows(IOException.class, () -> GeneratedSourceWriter.write(List.of(), output));
        assertEquals("handwritten", Files.readString(source));
    }

    @Test void reconcilesOwnershipLeftByAnInterruptedRun() throws Exception {
        GeneratedSourceWriter.write(List.of(file("p", "Old")), output);
        // A run recorded old+new ownership, wrote one new source, then failed.
        Files.writeString(output.resolve(".vernac-generated-sources"),
                "vernac-generated-sources-v1\np/Old.java\np/Partial.java\np/NotWritten.java\n");
        Files.writeString(output.resolve("p/Partial.java"), "partial contents");
        GeneratedSourceWriter.write(List.of(file("p", "Current")), output);
        assertFalse(Files.exists(output.resolve("p/Old.java")));
        assertFalse(Files.exists(output.resolve("p/Partial.java")));
        assertTrue(Files.exists(output.resolve("p/Current.java")));
        assertEquals("vernac-generated-sources-v1\np/Current.java\n",
                Files.readString(output.resolve(".vernac-generated-sources")));
    }

    @Test void rejectsConcurrentWriters() throws Exception {
        GeneratedSourceWriter.write(List.of(file("p", "Model")), output);
        try (var channel = java.nio.channels.FileChannel.open(output.resolve(".vernac-output.lock"),
                StandardOpenOption.WRITE); var lock = channel.lock()) {
            assertThrows(IOException.class, () -> GeneratedSourceWriter.write(List.of(), output));
        }
        assertTrue(Files.exists(output.resolve("p/Model.java")));
    }
}
