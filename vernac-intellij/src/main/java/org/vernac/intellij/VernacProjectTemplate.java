package org.vernac.intellij;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

public final class VernacProjectTemplate {

    private static final String RESOURCE_ROOT =
            "/projectTemplates/vernac-basic/";

    private static final List<String> FILES = List.of(
            "pom.xml",
            "README.md",
            "mvnw",
            "mvnw.cmd",
            ".mvn/wrapper/maven-wrapper.properties",
            ".gitignore",
            ".gitattributes",
            "src/main/vernac/tasks.vernac",
            "src/test/java/org/example/tasks/TaskTest.java"
    );

    private VernacProjectTemplate() {
    }

    public static void copyTo(Path projectDirectory, String projectName) throws IOException {
        // Check everything before starting to copy.
        for (String relativePath : FILES) {
            Path destination = projectDirectory.resolve(relativePath);

            if (Files.exists(destination)) {
                throw new IOException(
                        "Project file already exists: " + destination
                );
            }

            if (VernacProjectTemplate.class.getResource(
                    RESOURCE_ROOT + relativePath
            ) == null) {
                throw new IOException(
                        "Bundled project template is incomplete: "
                                + relativePath
                );
            }
        }

        for (String relativePath : FILES) {
            Path destination = projectDirectory.resolve(relativePath);
            Files.createDirectories(destination.getParent());

            try (InputStream source =
                         VernacProjectTemplate.class.getResourceAsStream(
                                 RESOURCE_ROOT + relativePath
                         )) {
                if (source == null) {
                    throw new IOException(
                            "Project template resource not found: "
                                    + relativePath
                    );
                }

                // Existing files are deliberately not overwritten.
                if ("pom.xml".equals(relativePath)) {
                    String pom = new String(
                            source.readAllBytes(),
                            StandardCharsets.UTF_8
                    );

                    pom = customizePom(pom, projectName);

                    Files.writeString(
                            destination,
                            pom,
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW
                    );
                } else {
                    Files.copy(source, destination);
                }
            }
        }

        makeExecutable(projectDirectory.resolve("mvnw"));
    }

    private static void makeExecutable(Path script) throws IOException {
        if (!Files.getFileStore(script)
                .supportsFileAttributeView("posix")) {
            return;
        }

        var permissions = new HashSet<>(
                Files.getPosixFilePermissions(script)
        );
        permissions.add(PosixFilePermission.OWNER_EXECUTE);
        Files.setPosixFilePermissions(script, permissions);
    }

    private static String customizePom(
            String pom,
            String projectName
    ) throws IOException {
        String artifactId = projectName.strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]+", "-")
                .replaceAll("^-+|-+$", "");

        if (artifactId.isEmpty()) {
            artifactId = "vernac-project";
        }

        pom = replaceExactlyOnce(
                pom,
                "<artifactId>vernac-basic</artifactId>",
                "<artifactId>" + artifactId + "</artifactId>"
        );

        return replaceExactlyOnce(
                pom,
                "<name>Vernac Basic Example</name>",
                "<name>" + escapeXml(projectName) + "</name>"
        );
    }

    private static String replaceExactlyOnce(
            String text,
            String expected,
            String replacement
    ) throws IOException {
        int position = text.indexOf(expected);

        if (position < 0
                || text.indexOf(expected, position + expected.length()) >= 0) {
            throw new IOException(
                    "Expected exactly one template entry: " + expected
            );
        }

        return text.substring(0, position)
                + replacement
                + text.substring(position + expected.length());
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}