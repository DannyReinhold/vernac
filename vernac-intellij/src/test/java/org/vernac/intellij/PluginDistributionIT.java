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
                    "vernac-version.txt",
                    "org/vernac/intellij/VernacNewProjectWizard.class",
                    "org/vernac/intellij/VernacProjectTemplate.class",
                    "org/vernac/intellij/VernacProjectOpenActivity.class",
                    "org/vernac/intellij/VernacLspIntegrationProvider.class",
                    "org/vernac/intellij/VernacLspCustomization.class",
                    "org/vernac/intellij/VernacJavaDeclarationHandler.class",
                    "org/vernac/intellij/VernacTypeNavigationTarget.class",
                    "org/vernac/intellij/VernacNewNamespaceAction.class",
                    "org/vernac/intellij/VernacNewFileAction.class",
                    "org/vernac/intellij/VernacFileTemplateHandler.class",
                    "org/vernac/intellij/VernacNamespaces.class",
                    "org/vernac/intellij/VernacParserDefinition.class",
                    "org/vernac/intellij/VernacInjectionHost.class",
                    "org/vernac/intellij/VernacJavaInjector.class",
                    "org/vernac/intellij/VernacHostManipulator.class",
                    "fileTemplates/internal/Vernac File.vernac.ft",
                    "fileTemplates/internal/Vernac Getting Started.vernac.ft"
            )) {
                requireContent(plugin, entry);
            }

            String descriptor = new String(requireContent(plugin, "META-INF/plugin.xml"),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(descriptor.contains("<internalFileTemplate name=\"Vernac File\"/>"));
            assertTrue(descriptor.contains("<internalFileTemplate name=\"Vernac Getting Started\"/>"));
            assertTrue(descriptor.contains("implementation=\"org.vernac.intellij.VernacFileTemplateHandler\""));
            assertFalse(plugin.containsKey("fileTemplates/Vernac File.vernac.ft"));
            assertFalse(plugin.containsKey("fileTemplates/Vernac Getting Started.vernac.ft"));

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

            var compilerJar = zip.stream().filter(e -> e.getName().startsWith("vernac-intellij/lib/vernac-compiler-")
                    && e.getName().endsWith(".jar")).findFirst().orElseThrow();
            var compiler = readNestedJar(zip, compilerJar.getName());
            requireContent(compiler, "org/vernac/compiler/tooling/BehaviorJavaProjection.class");
            requireContent(compiler, "org/vernac/compiler/tooling/VernacTypeNavigation.class");
            requireContent(compiler, "org/vernac/compiler/pipeline/GeneratedSourceOwnership.class");
            requireContent(compiler, "org/vernac/compiler/parser/VernacParser.class");
            requireContent(compiler, "org/vernac/language/VernacNames.class");
            for (String artifact : List.of("antlr4-runtime", "vernac-runtime", "javapoet", "jspecify")) {
                assertTrue(zip.stream().anyMatch(e -> e.getName().startsWith("vernac-intellij/lib/" + artifact + "-")
                        && e.getName().endsWith(".jar")), "Missing projection dependency: " + artifact);
            }
            assertFalse(plugin.containsKey("org/vernac/language/VernacNames.class"),
                    "Shared naming classes belong to the compiler JAR only");
            assertTrue(descriptor.contains("org.vernac.intellij.VernacJavaDeclarationHandler"));
            assertTrue(descriptor.contains("org.vernac.intellij.VernacJavaInjector"));
            assertTrue(descriptor.contains("org.vernac.intellij.VernacParserDefinition"));
            assertTrue(descriptor.contains("org.vernac.intellij.VernacHostManipulator"));

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