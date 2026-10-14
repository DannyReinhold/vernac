// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

class DiagramSelectionTest {
    @TempDir Path root;

    @Test
    void selectionUsesTheNearestMavenSourceRootAndAcceptsUnicodeNamespaces() throws Exception {
        Path module = root.resolve("module");
        Files.createDirectories(module);
        Files.writeString(module.resolve("pom.xml"), "<project/>");
        Path namespace = module.resolve("src/main/vernac/example/aufträge");
        Files.createDirectories(namespace);
        Path source = namespace.resolve("model.vernac");
        Files.writeString(source, "namespace example.aufträge;");
        var selected = DiagramSelection.from(source).orElseThrow();
        assertEquals("example.aufträge", selected.namespace());
        assertEquals(module, selected.projectRoot());
        assertEquals(selected, DiagramSelection.from(namespace).orElseThrow());
        assertTrue(DiagramSelection.from(root).isEmpty());
        assertTrue(DiagramSelection.from(module.resolve("src/main/vernac")).isEmpty());
    }
}
