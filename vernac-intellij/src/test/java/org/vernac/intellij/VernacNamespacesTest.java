// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class VernacNamespacesTest {
    @TempDir Path project;

    @Test
    void findsNearestConventionalRootAndDerivesNamespace() {
        Path root = project.resolve("module/src/main/vernac");
        Path directory = root.resolve("org/example/custom");
        assertEquals(root, VernacNamespaces.sourceRoot(directory).orElseThrow());
        assertEquals("org.example.custom", VernacNamespaces.namespace(root, directory));
        assertEquals(directory, VernacNamespaces.destination(root, "org.example.custom"));
        assertEquals(root, VernacNamespaces.sourceRoot(root).orElseThrow());
        assertTrue(VernacNamespaces.sourceRoot(project.resolve("src/main/java")).isEmpty());
    }

    @Test
    void enforcesJavaCompatibleNamesButAllowsVernacKeywords() {
        for (String name : new String[]{"org.example.custom", "org.example.value", "org.example.record", "tasks", "org.ümlaut", "org.$name", "例.注文", "org.𐐨"}) {
            assertDoesNotThrow(() -> VernacNamespaces.requireNamespace(name));
        }
        for (String name : new String[]{"", "org..tasks", "org.class", "org.example.", "../tasks", "org.tasks ", "1tasks", "org.\u200Bhidden", "org._"}) {
            assertThrows(IllegalArgumentException.class, () -> VernacNamespaces.requireNamespace(name), name);
        }
    }

    @Test
    void unicodeNamespaceRoundTripPreservesExactSpelling() {
        Path root = project.resolve("src/main/vernac");
        for (String namespace : new String[]{"de.aufträge", "例.注文", "org.𐐨", "org.ö", "org.o\u0308"}) {
            Path path = VernacNamespaces.destination(root, namespace);
            assertEquals(namespace, VernacNamespaces.namespace(root, path));
        }
    }

    @Test
    void refusesEmptyOutsideAndDottedDirectoryNamespaces() {
        Path root = project.resolve("src/main/vernac");
        assertThrows(IllegalArgumentException.class, () -> VernacNamespaces.namespace(root, root));
        assertThrows(IllegalArgumentException.class, () -> VernacNamespaces.namespace(root, project));
        assertThrows(IllegalArgumentException.class, () -> VernacNamespaces.namespace(root, root.resolve("org.example")));
        assertThrows(IllegalArgumentException.class, () -> VernacNamespaces.namespace(root, root.resolve("org/class")));
    }

    @Test
    void fileNamesCannotChangeTheTargetDirectory() {
        assertEquals("model", VernacNamespaces.fileStem("model"));
        assertEquals("model", VernacNamespaces.fileStem("model.vernac"));
        assertEquals("my-model", VernacNamespaces.fileStem("my-model"));
        for (String name : new String[]{"", ".vernac", "../model", "sub/model", "sub\\model", "C:model", "..", "model."}) {
            assertThrows(IllegalArgumentException.class, () -> VernacNamespaces.fileStem(name), name);
        }
    }

    @Test
    void bothInternalTemplatesUseNamespaceInsteadOfJavaPackage() throws Exception {
        for (String name : new String[]{"Vernac File", "Vernac Getting Started"}) {
            try (var source = getClass().getResourceAsStream("/fileTemplates/internal/" + name + ".vernac.ft")) {
                assertNotNull(source);
                String template = new String(source.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(template.startsWith("namespace ${NAMESPACE};"));
                assertFalse(template.contains("BASE_PACKAGE"));
                assertFalse(template.contains("aggregate "));
                assertEquals("namespace org.example.tasks;", template.replace("${NAMESPACE}", "org.example.tasks").lines().findFirst().orElseThrow());
            }
        }
    }
}
