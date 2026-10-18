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
            "src/main/vernac/org/example/tasks/tasks.vernac",
            "src/test/java/org/example/tasks/TaskTest.java"
    );

    public enum ProjectKind {
        APPLICATION("Minimal Domain Project"), TYPE_MODULE("Module / Type Library"),
        SPRING_BOOT_APPLICATION("Spring Boot Web Application");
        private final String label;
        ProjectKind(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private VernacProjectTemplate() {
    }

    public static void copyTo(
            Path projectDirectory,
            String projectName,
            String groupId,
            String artifactId,
            String basePackage
    ) throws IOException {
        copyTo(projectDirectory, projectName, groupId, artifactId, basePackage, ProjectKind.APPLICATION);
    }

    public static void copyTo(Path projectDirectory, String projectName, String groupId,
                              String artifactId, String basePackage, ProjectKind kind) throws IOException {
        Objects.requireNonNull(kind, "Project kind");
        validateParameters(projectName, groupId, artifactId, basePackage);
        if (kind == ProjectKind.TYPE_MODULE && !basePackage.contains(".")) {
            throw new IOException("A type module requires a qualified namespace with at least two segments.");
        }

        if (kind == ProjectKind.SPRING_BOOT_APPLICATION) {
            VernacSpringBootTemplate.copyTo(projectDirectory, projectName, groupId, artifactId, basePackage, readVernacVersion());
            return;
        }

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

            if (kind == ProjectKind.TYPE_MODULE && relativePath.endsWith("TaskTest.java")) continue;

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
                        basePackage,
                        relativePath.endsWith(".vernac")
                ).getBytes(StandardCharsets.UTF_8);
            }

            if (kind == ProjectKind.TYPE_MODULE) {
                if (relativePath.endsWith("tasks.vernac")) {
                    content = ("namespace " + basePackage + ";\n\nid TaskId;\n"
                            + "value Title(String) validates {\n"
                            + "    require(!self.string().isBlank(), \"Title must not be blank\");\n"
                            + "} list Titles;\n").getBytes(StandardCharsets.UTF_8);
                } else if (relativePath.equals("pom.xml")) {
                    String pom = new String(content, StandardCharsets.UTF_8);
                    pom = pom.replace("        <plugins>", "        <plugins>\n"
                            + "            <plugin>\n"
                            + "                <groupId>org.apache.maven.plugins</groupId>\n"
                            + "                <artifactId>maven-source-plugin</artifactId>\n"
                            + "                <version>3.3.1</version>\n"
                            + "                <configuration><excludes><exclude>**/.vernac-*</exclude></excludes></configuration>\n"
                            + "                <executions><execution><phase>package</phase><goals><goal>jar-no-fork</goal></goals></execution></executions>\n"
                            + "            </plugin>");
                    content = pom.getBytes(StandardCharsets.UTF_8);
                } else if (relativePath.equals("README.md")) {
                    content = ("# " + escapeMarkdown(projectName) + "\n\n"
                            + "Reusable Vernac type module `" + basePackage + "`.\n\n"
                            + "Run `mvn clean install` to compile and publish into your local Maven repository.\n"
                            + "For SNAPSHOT tools, install the matching Vernac compiler/runtime first.\n"
                            + "Export local types explicitly in `vernac-module.vmodule`.\n"
                            + "The JAR contains compiled classes and `META-INF/vernac/module.json`;\n"
                            + "the sources JAR includes original Vernac sources for navigation.\n"
                            + "Consumers add the Maven dependency and import exported qualified type names.\n"
                            + "Type-only modules need no activation or database.\n"
                            + "For functional reuse, consumers activate this logical module in their .vapplication file.\n"
                            + "Keep implementation helpers internal; export every local type in a public signature.\n"
                            + "Persistent modules own reviewed migrations; consumers migrate their own flattened external values.\n"
                            + "See https://vernac.org/tutorials/type-modules.html for the lifecycle.\n")
                            .getBytes(StandardCharsets.UTF_8);
                }
            }

            if (preparedFiles.putIfAbsent(destination, content) != null) {
                throw new IOException(
                        "Duplicate template destination: " + destination
                );
            }
        }

        String definitionName = kind == ProjectKind.APPLICATION
                ? "vernac-application.vapplication" : "vernac-module.vmodule";
        Path definition = root.resolve(definitionName);
        if (Files.exists(definition, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Project file already exists: " + definition);
        }
        String definitionText = kind == ProjectKind.APPLICATION
                ? "application " + basePackage + " {\n}\n"
                : "module " + basePackage + " {\n"
                + "    export " + basePackage + ".TaskId;\n"
                + "    export " + basePackage + ".Title;\n"
                + "    export " + basePackage + ".Titles;\n}\n";
        preparedFiles.put(definition, definitionText.getBytes(StandardCharsets.UTF_8));

        writePrepared(root, preparedFiles);
    }

    static void writePrepared(Path root, Map<Path, byte[]> preparedFiles) throws IOException {
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
                + "- Vernac namespace: `" + basePackage + "`";

        readme = replaceExactlyOnce(
                readme,
                "# Vernac Basic Example",
                heading
        );

        readme = readme.replace("src/main/vernac/org/example/tasks/",
                "src/main/vernac/" + basePackage.replace('.', '/') + "/");
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

    static String escapeMarkdown(String value) {
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
            String basePackage,
            boolean vernac
    ) throws IOException {
        String keyword = vernac ? "namespace" : "package";
        content = replaceExactlyOnce(
                content,
                keyword + " org.example.tasks;",
                keyword + " " + basePackage + ";"
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

        pom = replaceSection(pom, "<vernac.version>", "</vernac.version>",
                "<vernac.version>" + escapeXml(readVernacVersion()) + "</vernac.version>");
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
        String modelRoot = "src/main/vernac/org/example/tasks/";
        if (templatePath.startsWith(modelRoot)) {
            return "src/main/vernac/" + basePackage.replace('.', '/')
                    + "/" + templatePath.substring(modelRoot.length());
        }
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