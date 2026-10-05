package org.vernac.intellij;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.List;

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

    public static void copyTo(Path projectDirectory) throws IOException {
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
                Files.copy(source, destination);
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
}