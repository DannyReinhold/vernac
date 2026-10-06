package org.vernac.intellij;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class PluginDistributionIT {

    private static Map<String, byte[]> readNestedJar(
            ZipFile distribution,
            String path
    ) throws IOException {
        var entry = distribution.getEntry(path);

        assertNotNull(entry, "Missing JAR in plugin ZIP: " + path);
        assertFalse(entry.isDirectory(), "Expected a JAR file: " + path);

        Map<String, byte[]> contents = new HashMap<>();

        try (var input = distribution.getInputStream(entry);
             var jar = new ZipInputStream(input)) {
            java.util.zip.ZipEntry nestedEntry;

            while ((nestedEntry = jar.getNextEntry()) != null) {
                if (!nestedEntry.isDirectory()) {
                    byte[] previous = contents.putIfAbsent(
                            nestedEntry.getName(),
                            jar.readAllBytes()
                    );

                    assertNull(
                            previous,
                            "Duplicate JAR entry: " + nestedEntry.getName()
                    );
                }
            }
        }

        assertFalse(contents.isEmpty(), "JAR has no file entries: " + path);
        return contents;
    }

    private static byte[] requireContent(
            Map<String, byte[]> entries,
            String path
    ) {
        byte[] content = entries.get(path);

        assertNotNull(content, "Missing packaged resource: " + path);
        assertTrue(content.length > 0, "Empty packaged resource: " + path);

        return content;
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);

        assertNotNull(
                value,
                "Missing system property " + name
                        + "; run this test through Maven verify."
        );

        return value;
    }

    @Test
    void includesPluginTemplateAndExecutableLanguageServer()
            throws IOException {
        Path distribution = Path.of(requiredProperty("vernac.plugin.zip"));
        String pluginJarName = requiredProperty("vernac.plugin.jar.name");

        assertTrue(
                Files.isRegularFile(distribution),
                "Plugin ZIP is missing: " + distribution
        );

        try (ZipFile zip = new ZipFile(distribution.toFile())) {
            Map<String, byte[]> plugin = readNestedJar(
                    zip,
                    "vernac-intellij/lib/" + pluginJarName
            );

            for (String entry : List.of(
                    "META-INF/plugin.xml",
                    "org/vernac/intellij/VernacNewProjectWizard.class",
                    "org/vernac/intellij/VernacProjectTemplate.class",
                    "org/vernac/intellij/VernacProjectOpenActivity.class",
                    "org/vernac/intellij/VernacLspIntegrationProvider.class"
            )) {
                requireContent(plugin, entry);
            }

            for (String templateFile : List.of(
                    "pom.xml",
                    "README.md",
                    "mvnw",
                    "mvnw.cmd",
                    ".mvn/wrapper/maven-wrapper.properties",
                    ".gitignore",
                    ".gitattributes",
                    "src/main/vernac/tasks.vernac",
                    "src/test/java/org/example/tasks/TaskTest.java"
            )) {
                requireContent(
                        plugin,
                        "projectTemplates/vernac-basic/" + templateFile
                );
            }

            Map<String, byte[]> server = readNestedJar(
                    zip,
                    "vernac-intellij/server/vernac-lsp.jar"
            );

            Manifest manifest = new Manifest(new ByteArrayInputStream(
                    requireContent(server, "META-INF/MANIFEST.MF")
            ));

            String mainClass = manifest.getMainAttributes()
                    .getValue("Main-Class");

            assertEquals(
                    "org.vernac.lsp.VernacLanguageServerLauncher",
                    mainClass,
                    "Language server must declare its launcher"
            );

            requireContent(
                    server,
                    mainClass.replace('.', '/') + ".class"
            );
        }
    }
}