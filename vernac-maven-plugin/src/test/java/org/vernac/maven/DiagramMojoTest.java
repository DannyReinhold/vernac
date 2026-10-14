// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.maven;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

class DiagramMojoTest {
    @TempDir Path root;

    @Test
    void goalExportsAllNamespacesOrOneWithoutGeneratingJavaOrHistory() throws Exception {
        Path source = root.resolve("src/main/vernac");
        Files.createDirectories(source.resolve("sales"));
        Files.createDirectories(source.resolve("billing"));
        Files.writeString(
                source.resolve("sales/model.vernac"), "namespace sales; value Name(String);");
        Files.writeString(
                source.resolve("billing/model.vernac"), "namespace billing; id InvoiceId;");
        var mojo = new DiagramMojo();
        mojo.sourceDirectory = source.toFile();
        mojo.outputDirectory = root.resolve("target/vernac-diagrams").toFile();
        mojo.execute();
        assertTrue(Files.exists(root.resolve("target/vernac-diagrams/sales.md")));
        assertTrue(Files.exists(root.resolve("target/vernac-diagrams/billing.puml")));
        assertFalse(Files.exists(root.resolve("target/generated-sources")));
        assertFalse(Files.exists(root.resolve("src/main/vernac-schema")));
        mojo.namespace = "sales";
        mojo.outputDirectory = root.resolve("single").toFile();
        mojo.execute();
        assertTrue(Files.exists(root.resolve("single/sales.md")));
        assertFalse(Files.exists(root.resolve("single/billing.md")));
        mojo.namespace = "missing";
        assertThrows(org.apache.maven.plugin.MojoFailureException.class, mojo::execute);
    }
}
