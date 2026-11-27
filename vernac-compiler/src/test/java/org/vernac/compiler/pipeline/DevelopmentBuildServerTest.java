// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.runtime.DevelopmentRevisions;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DevelopmentBuildServerTest {
    @TempDir Path root;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String MODEL = """
            namespace proof;
            view Board() { page "Board" { heading "Original"; } }
            """;

    @Test
    void activatesValidSourceAndPreservesTheActiveRevisionAcrossRejectedRequests() throws Exception {
        var source = Files.createDirectories(root.resolve("src/main/vernac/proof")).resolve("board.vernac");
        Files.writeString(source, MODEL);
        var pom = Files.writeString(root.resolve("pom.xml"), "<project/>");
        var loader = baselineLoader();
        var revisions = new DevelopmentRevisions();
        try (var server = DevelopmentBuildServer.open(root, revisions, loader)) {
            assertThrows(IOException.class, () -> DevelopmentBuildServer.open(root, revisions, loader));
            Files.writeString(source, MODEL.replace("Original", "Updated"));
            var activated = JSON.readTree(DevelopmentBuildServer.build(root));
            assertEquals("activated", activated.path("status").asText());
            assertEquals(1, activated.path("activeRevision").asInt());
            try (var pin = revisions.pin()) {
                assertTrue(revisions.template("vernac-generated/proof/Board.html").orElseThrow().contains("Updated"));
            }

            Files.writeString(source, "namespace proof; view");
            var invalid = JSON.readTree(DevelopmentBuildServer.build(root));
            assertEquals("invalid", invalid.path("status").asText());
            assertEquals(2, invalid.path("requestedRevision").asInt());
            assertEquals(1, invalid.path("activeRevision").asInt());
            assertFalse(invalid.path("diagnostics").isEmpty());

            Files.writeString(source, MODEL);
            Files.writeString(pom, "<project><!-- fixed input changed --></project>");
            var incompatible = JSON.readTree(DevelopmentBuildServer.build(root));
            assertEquals("rebuild_required", incompatible.path("status").asText());
            assertEquals(1, incompatible.path("activeRevision").asInt());
        }
        assertThrows(IOException.class, () -> DevelopmentBuildServer.build(root));
        assertThrows(IOException.class, () -> DevelopmentBuildServer.open(root, revisions, loader));
    }

    private ClassLoader baselineLoader() throws IOException {
        var marker = (DevelopmentWorkspace.capture(root).fingerprint() + "\n").getBytes(StandardCharsets.UTF_8);
        return new ClassLoader(getClass().getClassLoader()) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return name.equals(DevelopmentWorkspace.BASELINE_RESOURCE)
                        ? new ByteArrayInputStream(marker) : super.getResourceAsStream(name);
            }
        };
    }
}
