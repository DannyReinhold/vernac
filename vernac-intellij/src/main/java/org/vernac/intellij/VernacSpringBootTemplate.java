// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import org.vernac.compiler.persistence.*;
import org.vernac.compiler.pipeline.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * A complete small application. Preparation, including schema fingerprints, precedes every write.
 */
final class VernacSpringBootTemplate {
    private static final String ROOT = "/projectTemplates/vernac-spring-boot/";
    private static final List<String> FILES =
            List.of(
                    "pom.xml",
                    "README.md",
                    "compose.yaml",
                    "Dockerfile",
                    ".dockerignore",
                    ".gitignore",
                    "src/main/vernac/org/example/tasks/tasks.vernac",
                    "src/main/java/org/example/tasks/TasksApplication.java",
                    "src/main/java/org/example/tasks/adapter/inbound/web/WebSecurity.java",
                    "src/main/resources/application.yaml",
                    "src/main/resources/application-local.yaml",
                    "src/main/resources/templates/board.html",
                    "src/main/resources/templates/problem.html",
                    "src/main/resources/templates/error.html",
                    "src/main/resources/static/app.css",
                    "src/main/resources/static/favicon.svg",
                    "src/test/java/org/example/tasks/TaskTest.java",
                    "src/test/java/org/example/tasks/TaskConfigurationTest.java",
                    "src/test/java/org/example/tasks/TaskWebTest.java",
                    "src/test/java/org/example/tasks/TaskPersistenceIT.java");
    private static final List<String> COMMON =
            List.of("mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties", ".gitattributes");

    private VernacSpringBootTemplate() {}

    static void copyTo(
            Path directory,
            String projectName,
            String groupId,
            String artifactId,
            String namespace,
            String version)
            throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        Map<Path, byte[]> files = new LinkedHashMap<>();
        for (String file : FILES) {
            String text =
                    resource(ROOT + file)
                            .replace("org.example.tasks", namespace)
                            .replace("org/example/tasks", namespace.replace('.', '/'))
                            .replace("@ARTIFACT_ID@", artifactId)
                            .replace("@GROUP_ID@", groupId)
                            .replace("@PROJECT_NAME_XML@", xml(projectName))
                            .replace(
                                    "@PROJECT_NAME_MD@",
                                    VernacProjectTemplate.escapeMarkdown(projectName))
                            .replace("@VERNAC_VERSION@", version);
            files.put(
                    root.resolve(file.replace("org/example/tasks", namespace.replace('.', '/'))),
                    text.getBytes(StandardCharsets.UTF_8));
        }
        for (String file : COMMON)
            files.put(
                    root.resolve(file),
                    resource("/projectTemplates/vernac-basic/" + file)
                            .getBytes(StandardCharsets.UTF_8));
        files.put(
                root.resolve("vernac-application.vapplication"),
                ("application " + namespace + " {\n}\n").getBytes(StandardCharsets.UTF_8));

        // Generate the baseline for the actual namespace; textual replacement would invalidate
        // hashes.
        Path sourceRoot = root.resolve("src/main/vernac");
        Path source = sourceRoot.resolve(namespace.replace('.', '/')).resolve("tasks.vernac");
        String model = new String(files.get(source), StandardCharsets.UTF_8);
        try {
            var unit = new VernacSourceParser().parse(source.toString(), model);
            var indexed =
                    new VernacProjectLoader()
                            .index(sourceRoot, List.of(new VernacSourceFile(source, unit)));
            var resolved = new ProjectTypeResolver().resolve(indexed);
            var empty = SchemaModel.empty();
            var schema =
                    new SchemaBuilder(
                                    resolved,
                                    empty,
                                    Map.of(),
                                    new SchemaBuilder.Options(false, false))
                            .build();
            var plan = new MigrationPlanner().plan(empty, schema, MigrationPlanner.Options.safe());
            String migration = "V20261015090000000__initial_tasks";
            files.put(
                    root.resolve("src/main/resources/db/migration/" + migration + ".sql"),
                    plan.sql().getBytes(StandardCharsets.UTF_8));
            var snapshot =
                    new SchemaHistory.Snapshot(
                            1,
                            migration + ".sql",
                            empty.fingerprint(),
                            schema.fingerprint(),
                            schema);
            files.put(
                    root.resolve("src/main/vernac-schema/history/" + migration + ".json"),
                    (SchemaModel.JSON.writeValueAsString(snapshot) + "\n")
                            .getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException
                | org.vernac.compiler.analyzer.SemanticValidationException invalid) {
            throw new IOException(
                    "Could not prepare the starter's initial schema: " + invalid.getMessage(),
                    invalid);
        }
        // Detect every collision, including generated history, before any destination file is
        // written.
        for (Path file : files.keySet()) {
            if (!file.startsWith(root))
                throw new IOException("Template destination is outside the project: " + file);
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Project file already exists: " + file);
        }
        VernacProjectTemplate.writePrepared(root, files);
    }

    private static String resource(String name) throws IOException {
        try (var stream = VernacSpringBootTemplate.class.getResourceAsStream(name)) {
            if (stream == null)
                throw new IOException("Bundled project template is incomplete: " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
