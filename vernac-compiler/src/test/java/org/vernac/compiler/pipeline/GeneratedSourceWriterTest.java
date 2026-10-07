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
}
