package org.vernac.intellij;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class VernacProjectTemplateTest {

    @TempDir
    Path temporaryDirectory;

    private static String directChildText(
            org.w3c.dom.Element parent,
            String name
    ) {
        var children = parent.getChildNodes();

        for (int index = 0; index < children.getLength(); index++) {
            var child = children.item(index);

            if (child instanceof org.w3c.dom.Element element
                    && name.equals(element.getLocalName())) {
                return element.getTextContent().strip();
            }
        }

        throw new AssertionError("Missing POM element: " + name);
    }

    @Test
    void createsProjectWithCustomCoordinatesAndPackage() throws Exception {
        Path project = temporaryDirectory.resolve("project");

        VernacProjectTemplate.copyTo(
                project,
                "TaskManager & Friends",
                "de.rsas.demo",
                "task-service",
                "de.rsas.taskmanager"
        );

        // Parse XML to verify both values and correct XML escaping.
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl",
                true
        );

        var document = factory.newDocumentBuilder()
                .parse(project.resolve("pom.xml").toFile());

        var root = document.getDocumentElement();
        String expectedVersion =
                System.getProperty("vernac.expected.version");

        assertNotNull(
                expectedVersion,
                "Run this test through Maven to provide the expected build version."
        );

        var properties = (org.w3c.dom.Element) root
                .getElementsByTagNameNS(
                        "http://maven.apache.org/POM/4.0.0",
                        "properties"
                )
                .item(0);

        assertNotNull(properties, "Missing POM properties");

        assertEquals(
                expectedVersion,
                directChildText(properties, "vernac.version")
        );

        // The user's project version must stay independent of Vernac's version.
        assertEquals(
                "0.1.0-SNAPSHOT",
                directChildText(root, "version")
        );

        String generatedPom = Files.readString(project.resolve("pom.xml"));

        assertTrue(
                generatedPom.contains("<version>${vernac.version}</version>"),
                "The generated project must retain its Maven property references."
        );
        assertEquals("de.rsas.demo", directChildText(root, "groupId"));
        assertEquals("task-service", directChildText(root, "artifactId"));
        assertEquals(
                "TaskManager & Friends",
                directChildText(root, "name")
        );

        String model = Files.readString(
                project.resolve("src/main/vernac/tasks.vernac")
        );
        assertTrue(model.contains("package de.rsas.taskmanager;"));
        assertFalse(model.contains("org.example.tasks"));

        Path testPath = project.resolve(
                "src/test/java/de/rsas/taskmanager/TaskTest.java"
        );
        String test = Files.readString(testPath);

        assertTrue(test.contains("package de.rsas.taskmanager;"));
        assertTrue(test.contains(
                "import de.rsas.taskmanager.domain.Task;"
        ));
        assertFalse(test.contains("org.example.tasks"));

        assertFalse(Files.exists(project.resolve(
                "src/test/java/org/example/tasks/TaskTest.java"
        )));

        String readme = Files.readString(project.resolve("README.md"));
        assertTrue(readme.contains("de.rsas.demo:task-service"));
        assertTrue(readme.contains(
                "src/test/java/de/rsas/taskmanager/TaskTest.java"
        ));
        assertFalse(readme.contains("org/example/tasks"));

        for (String resource : new String[]{
                "mvnw",
                "mvnw.cmd",
                ".mvn/wrapper/maven-wrapper.properties",
                ".gitignore",
                ".gitattributes"
        }) {
            assertTrue(
                    Files.isRegularFile(project.resolve(resource)),
                    "Missing resource: " + resource
            );
        }
    }

    @Test
    void detectsExistingCustomTestBeforeWritingOtherFiles() throws Exception {
        Path project = temporaryDirectory.resolve("existing");
        Path existingTest = project.resolve(
                "src/test/java/de/rsas/taskmanager/TaskTest.java"
        );

        Files.createDirectories(existingTest.getParent());
        Files.writeString(existingTest, "Keep this file.");

        assertThrows(java.io.IOException.class, () ->
                VernacProjectTemplate.copyTo(
                        project,
                        "TaskManager",
                        "de.rsas.demo",
                        "task-service",
                        "de.rsas.taskmanager"
                )
        );

        assertEquals("Keep this file.", Files.readString(existingTest));
        assertFalse(Files.exists(project.resolve("pom.xml")));
        assertFalse(Files.exists(project.resolve("mvnw")));
    }

    @Test
    void rejectsInvalidPackageBeforeCreatingProject() {
        Path project = temporaryDirectory.resolve("invalid");

        assertThrows(java.io.IOException.class, () ->
                VernacProjectTemplate.copyTo(
                        project,
                        "TaskManager",
                        "de.rsas.demo",
                        "task-service",
                        "de.class.tasks"
                )
        );

        assertFalse(Files.exists(project));
    }

    @Test
    void derivesArtifactIdFromCamelCase() {
        assertEquals(
                "task-manager",
                VernacProjectTemplate.suggestArtifactId("TaskManager")
        );
        assertEquals(
                "http-client",
                VernacProjectTemplate.suggestArtifactId("HTTPClient")
        );
    }
}