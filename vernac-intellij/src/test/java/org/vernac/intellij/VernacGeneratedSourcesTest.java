// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

class VernacGeneratedSourcesTest {
    @TempDir Path directory;

    @Test
    void recognizesFirstBuildInAnExistingMavenProject() throws Exception {
        Files.writeString(directory.resolve("pom.xml"), "<project/>");
        Files.createDirectories(directory.resolve("src/main/vernac"));
        assertNull(VernacGeneratedSourcesActivity.candidate(directory, Set.of()));
        Path generated =
                Files.createDirectories(directory.resolve("target/generated-sources/vernac"));
        assertEquals(generated, VernacGeneratedSourcesActivity.candidate(directory, Set.of()));
        // Source declarations need not have compiled: this applies before javac succeeds.
        Files.writeString(generated.resolve("Name.java"), "public class Name {}");
        assertEquals(generated, VernacGeneratedSourcesActivity.candidate(directory, Set.of()));
    }

    @Test
    void doesNotDuplicateAnImportedRoot() throws Exception {
        Files.writeString(directory.resolve("pom.xml"), "<project/>");
        Files.createDirectories(directory.resolve("src/main/vernac"));
        Path generated =
                Files.createDirectories(directory.resolve("target/generated-sources/vernac"));
        String url = "file://" + generated.toAbsolutePath().toString().replace('\\', '/');
        assertNull(VernacGeneratedSourcesActivity.candidate(directory, Set.of(url)));
        assertNull(VernacGeneratedSourcesActivity.candidate(directory, Set.of(url + "/")));
    }

    @Test
    void ignoresUnrelatedProjectsAndDoesNotAttachChildrenToAnAggregator() throws Exception {
        Files.createDirectories(directory.resolve("target/generated-sources/vernac"));
        assertNull(VernacGeneratedSourcesActivity.candidate(directory, Set.of()));
        Files.writeString(directory.resolve("pom.xml"), "<project/>");
        assertNull(VernacGeneratedSourcesActivity.candidate(directory, Set.of()));
        Path child = directory.resolve("application");
        Files.createDirectories(child.resolve("src/main/vernac"));
        Files.createDirectories(child.resolve("target/generated-sources/vernac"));
        Files.writeString(child.resolve("pom.xml"), "<project/>");
        assertNull(VernacGeneratedSourcesActivity.candidate(directory, Set.of()));
        assertNotNull(VernacGeneratedSourcesActivity.candidate(child, Set.of()));
    }

    @Test
    void respondsToCreationCleanRecreationAndVernacEdits() {
        assertTrue(VernacGeneratedSourcesActivity.relevant("/project/target"));
        assertTrue(VernacGeneratedSourcesActivity.relevant("/project/target/generated-sources"));
        assertTrue(
                VernacGeneratedSourcesActivity.relevant(
                        "C:\\work\\target\\generated-sources\\vernac\\Name.java"));
        assertTrue(
                VernacGeneratedSourcesActivity.relevant("/project/src/main/vernac/model.vernac"));
        assertFalse(VernacGeneratedSourcesActivity.relevant("/project/target/classes/Name.class"));
        assertFalse(VernacGeneratedSourcesActivity.relevant("/other/src/main/java/Name.java"));
    }

    @Test
    void pluginRegistersTheStartupRepair() throws Exception {
        var xml =
                javax.xml.parsers.DocumentBuilderFactory.newInstance()
                        .newDocumentBuilder()
                        .parse(Path.of("src/main/resources/META-INF/plugin.xml").toFile());
        var activities = xml.getElementsByTagName("postStartupActivity");
        boolean found = false;
        for (int i = 0; i < activities.getLength(); i++) {
            found |=
                    activities
                            .item(i)
                            .getAttributes()
                            .getNamedItem("implementation")
                            .getNodeValue()
                            .equals(VernacGeneratedSourcesActivity.class.getName());
        }
        assertTrue(found);
    }
}
