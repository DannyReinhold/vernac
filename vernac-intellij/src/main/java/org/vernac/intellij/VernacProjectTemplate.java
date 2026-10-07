package org.vernac.intellij;

import org.vernac.language.VernacNames;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;

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

    public static void copyTo(
            Path projectDirectory,
            String projectName,
            String groupId,
            String artifactId,
            String basePackage
    ) throws IOException {
        validateParameters(projectName, groupId, artifactId, basePackage);

        Path root = projectDirectory.toAbsolutePath().normalize();
        Map<Path, byte[]> preparedFiles = new LinkedHashMap<>();

        // Prepare all files before writing anything.
        for (String relativePath : FILES) {
            Path destination = root.resolve(
                    destinationPath(relativePath, basePackage)
            ).normalize();

            if (!destination.startsWith(root)) {
                throw new IOException(
                        "Template destination is outside the project: "
                                + destination
                );
            }

            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(
                        "Project file already exists: " + destination
                );
            }

            byte[] content;

            try (InputStream source =
                         VernacProjectTemplate.class.getResourceAsStream(
                                 RESOURCE_ROOT + relativePath
                         )) {
                if (source == null) {
                    throw new IOException(
                            "Bundled project template is incomplete: "
                                    + relativePath
                    );
                }

                content = source.readAllBytes();
            }

            if ("pom.xml".equals(relativePath)) {
                content = customizePom(
                        new String(content, StandardCharsets.UTF_8),
                        projectName,
                        groupId,
                        artifactId
                ).getBytes(StandardCharsets.UTF_8);
            } else if ("README.md".equals(relativePath)) {
                content = customizeReadme(
                        new String(content, StandardCharsets.UTF_8),
                        projectName,
                        groupId,
                        artifactId,
                        basePackage
                ).getBytes(StandardCharsets.UTF_8);
            } else if (relativePath.endsWith(".vernac")
                    || relativePath.endsWith(".java")) {
                content = customizeSource(
                        new String(content, StandardCharsets.UTF_8),
                        basePackage
                ).getBytes(StandardCharsets.UTF_8);
            }

            if (preparedFiles.putIfAbsent(destination, content) != null) {
                throw new IOException(
                        "Duplicate template destination: " + destination
                );
            }
        }

        // Check parent paths before starting to write.
        for (Path destination : preparedFiles.keySet()) {
            for (Path parent = destination.getParent();
                 parent != null;
                 parent = parent.getParent()) {
                if (Files.exists(parent) && !Files.isDirectory(parent)) {
                    throw new IOException(
                            "Expected a directory: " + parent
                    );
                }
            }
        }

        for (Map.Entry<Path, byte[]> entry : preparedFiles.entrySet()) {
            Files.createDirectories(entry.getKey().getParent());
            Files.write(
                    entry.getKey(),
                    entry.getValue(),
                    StandardOpenOption.CREATE_NEW
            );
        }

        makeExecutable(root.resolve("mvnw"));
    }

    private static String customizeReadme(
            String readme,
            String projectName,
            String groupId,
            String artifactId,
            String basePackage
    ) throws IOException {
        String version = readVernacVersion();

        String dependencyNote = version.endsWith("-SNAPSHOT")
                ? "This project uses Vernac `" + version + "`.\n\n"
                + "Install the matching SNAPSHOT artifacts in your local Maven "
                + "repository before building this project. Build the matching "
                + "Vernac source version with `mvn clean install`, using the JDK "
                + "required by the Vernac repository."
                : "This project uses Vernac `" + version + "`.\n\n"
                + "Maven downloads the published compiler and runtime artifacts "
                + "from Maven Central automatically. You do not need to build "
                + "the Vernac source repository first.";

        readme = replaceSection(
                readme,
                "<!-- vernac-dependency-note:start -->",
                "<!-- vernac-dependency-note:end -->",
                dependencyNote
        );
        String heading = "# " + escapeMarkdown(projectName)
                + "\n\n"
                + "- Maven coordinates: `" + groupId + ":" + artifactId + "`\n"
                + "- Java package: `" + basePackage + "`";

        readme = replaceExactlyOnce(
                readme,
                "# Vernac Basic Example",
                heading
        );

        return replaceExactlyOnce(
                readme,
                "src/test/java/org/example/tasks/TaskTest.java",
                "src/test/java/"
                        + basePackage.replace('.', '/')
                        + "/TaskTest.java"
        );
    }

    private static String replaceSection(
            String text,
            String startMarker,
            String endMarker,
            String replacement
    ) throws IOException {
        int start = text.indexOf(startMarker);
        int end = text.indexOf(endMarker);

        if (start < 0
                || end < start + startMarker.length()
                || text.indexOf(startMarker, start + startMarker.length()) >= 0
                || text.indexOf(endMarker, end + endMarker.length()) >= 0) {
            throw new IOException(
                    "Missing or ambiguous template section: " + startMarker
            );
        }

        return text.substring(0, start)
                + replacement
                + text.substring(end + endMarker.length());
    }

    private static String escapeMarkdown(String value) {
        String singleLine = value.replace('\r', ' ').replace('\n', ' ');
        StringBuilder escaped = new StringBuilder();

        for (char character : singleLine.toCharArray()) {
            if ("\\`*_{}[]<>()#+-.!|&".indexOf(character) >= 0) {
                escaped.append('\\');
            }
            escaped.append(character);
        }

        return escaped.toString();
    }

    private static void validateParameters(
            String projectName,
            String groupId,
            String artifactId,
            String basePackage
    ) throws IOException {
        if (projectName == null || projectName.isBlank()) {
            throw new IOException("Project name must not be blank.");
        }

        if (groupId == null || !groupId.matches(
                "[A-Za-z0-9_][A-Za-z0-9_-]*"
                        + "(\\.[A-Za-z0-9_][A-Za-z0-9_-]*)*"
        )) {
            throw new IOException("Invalid Maven groupId: " + groupId);
        }

        if (artifactId == null
                || !artifactId.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) {
            throw new IOException("Invalid Maven artifactId: " + artifactId);
        }

        if (!VernacNames.isNamespace(basePackage)) {
            throw new IOException("Invalid Java package: " + basePackage);
        }
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

    public static String suggestArtifactId(String projectName) {
        String artifactId = projectName.strip()
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1-$2")
                .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]+", "-")
                .replaceAll("^-+|-+$", "");

        return artifactId.isEmpty() ? "vernac-project" : artifactId;
    }

    private static String customizeSource(
            String content,
            String basePackage
    ) throws IOException {
        content = replaceExactlyOnce(
                content,
                "package org.example.tasks;",
                "package " + basePackage + ";"
        );

        return content.replace(
                "import org.example.tasks.",
                "import " + basePackage + "."
        );
    }

    private static String customizePom(
            String pom,
            String projectName,
            String groupId,
            String artifactId
    ) throws IOException {
        pom = replaceExactlyOnce(
                pom,
                "<groupId>org.example</groupId>",
                "<groupId>" + escapeXml(groupId) + "</groupId>"
        );

        pom = replaceExactlyOnce(
                pom,
                "<artifactId>vernac-basic</artifactId>",
                "<artifactId>" + escapeXml(artifactId) + "</artifactId>"
        );

        pom = replaceExactlyOnce(
                pom,
                "<vernac.version>0.1.0-SNAPSHOT</vernac.version>",
                "<vernac.version>"
                        + escapeXml(readVernacVersion())
                        + "</vernac.version>"
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

    private static String destinationPath(
            String templatePath,
            String basePackage
    ) {
        String testRoot = "src/test/java/org/example/tasks/";

        if (templatePath.startsWith(testRoot)) {
            return "src/test/java/"
                    + basePackage.replace('.', '/')
                    + "/"
                    + templatePath.substring(testRoot.length());
        }

        return templatePath;
    }

    private static String readVernacVersion() throws IOException {
        try (InputStream source =
                     VernacProjectTemplate.class.getResourceAsStream(
                             "/vernac-version.txt"
                     )) {
            if (source == null) {
                throw new IOException("Bundled Vernac version is missing.");
            }

            String version = new String(
                    source.readAllBytes(),
                    StandardCharsets.UTF_8
            ).strip();

            if (version.isEmpty()
                    || version.contains("@")
                    || version.contains("${")) {
                throw new IOException(
                        "Bundled Vernac version was not resolved: " + version
                );
            }

            return version;
        }
    }
}