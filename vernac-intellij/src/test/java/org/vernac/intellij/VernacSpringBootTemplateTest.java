// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.persistence.*;
import org.vernac.compiler.pipeline.VernacCompiler;

import java.nio.file.*;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

class VernacSpringBootTemplateTest {
    @TempDir Path temporary;

    private void create(Path directory, String namespace) throws Exception {
        VernacProjectTemplate.copyTo(
                directory,
                "Small wins & friends",
                "de.rsas",
                "small-wins",
                namespace,
                VernacProjectTemplate.ProjectKind.SPRING_BOOT_APPLICATION);
    }

    @Test
    void createsAnExecutableWebApplicationWithAConsistentCustomSchema() throws Exception {
        Path root = temporary.resolve("web");
        String namespace = "de.rsas.aufträge.workspace";
        create(root, namespace);
        var compiler = new VernacCompiler();
        var resolved = compiler.analyzeProject(root.resolve("src/main/vernac"));
        var history =
                new SchemaHistory(
                        root.resolve("src/main/vernac-schema/history"),
                        root.resolve("src/main/resources/db/migration"));
        var schema = history.read();
        var expected = new SchemaBuilder(resolved, schema, Map.of()).build();
        assertFalse(
                new MigrationPlanner()
                        .plan(schema, expected, MigrationPlanner.Options.safe())
                        .changed(),
                "A newly created project must not immediately need a migration");
        assertTrue(
                schema.tables().stream()
                        .anyMatch(
                                table ->
                                        table.namespace().equals(namespace)
                                                && table.name().equals("Task")));
        assertFalse(compiler.generate(resolved, schema, Map.of()).generatedFiles().isEmpty());
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var pom = factory.newDocumentBuilder().parse(root.resolve("pom.xml").toFile());
        assertEquals(
                "Small wins & friends", pom.getElementsByTagName("name").item(0).getTextContent());
        String pomText = Files.readString(root.resolve("pom.xml"));
        assertTrue(pomText.contains("spring-boot-starter-thymeleaf"));
        assertTrue(
                pomText.contains(
                        "<vernac.version>"
                                + System.getProperty("vernac.expected.version")
                                + "</vernac.version>"));
        for (String name :
                new String[] {
                    "TasksApplication.java",
                    "adapter/inbound/web/WebSecurity.java"
                }) {
            String java =
                    Files.readString(
                            root.resolve(
                                    "src/main/java/" + namespace.replace('.', '/') + "/" + name));
            assertTrue(java.contains(namespace));
            assertFalse(java.contains("org.example.tasks"));
        }
        assertFalse(Files.exists(root.resolve("src/main/java/" + namespace.replace('.', '/') + "/adapter/inbound/web/TaskController.java")));
        assertTrue(compiler.generate(resolved, schema, Map.of()).generatedFiles().stream().anyMatch(f -> f.typeSpec().name().equals("TaskWeb")));
        String sql =
                Files.readString(
                        root.resolve(
                                "src/main/resources/db/migration/V20261015090000000__initial_tasks.sql"));
        assertTrue(sql.contains("You are solely responsible"));
        assertFalse(sql.contains("org.example.tasks"));
        assertEquals(
                "application " + namespace + " {\n}\n",
                Files.readString(root.resolve("vernac-application.vapplication")));
        assertFalse(Files.exists(root.resolve("vernac-module.vmodule")));
        assertTrue(Files.isRegularFile(root.resolve("mvnw.cmd")));
        assertTrue(Files.isRegularFile(root.resolve(".mvn/wrapper/maven-wrapper.properties")));
        assertTrue(Files.readString(root.resolve(".gitignore")).contains(".env"));
    }

    @Test
    void separatesLocalInfrastructureFromEnvironmentConfiguredDeployment() throws Exception {
        Path root = temporary.resolve("config");
        create(root, "example.tasks");
        String main = Files.readString(root.resolve("src/main/resources/application.yaml"));
        String local = Files.readString(root.resolve("src/main/resources/application-local.yaml"));
        assertTrue(main.contains("default: local"));
        assertTrue(main.contains("url: ${DB_URL}"));
        assertTrue(main.contains("password: ${DB_PASSWORD}"));
        assertTrue(main.contains("enabled: false"));
        assertFalse(main.contains("local-development-only"));
        assertTrue(local.contains("enabled: true"));
        assertTrue(local.contains("secure: false"));
        String docker = Files.readString(root.resolve("Dockerfile"));
        assertTrue(docker.contains("ENV SPRING_PROFILES_ACTIVE=production"));
        assertTrue(docker.contains("ENV SERVER_ADDRESS=0.0.0.0"));
        assertTrue(Files.readString(root.resolve("src/test/java/example/tasks/TaskPersistenceIT.java"))
                .contains("@ActiveProfiles(\"persistence-test\")"));
        String compose = Files.readString(root.resolve("compose.yaml"));
        assertTrue(compose.contains("127.0.0.1:${LOCAL_DB_PORT:-55439}:5432"));
        assertTrue(compose.contains("healthcheck:"));
        assertFalse(compose.contains("container_name:"));
        String board = Files.readString(root.resolve("src/main/resources/templates/board.html"));
        assertTrue(board.contains("th:action="));
        assertTrue(board.contains("th:text=\"${task.taskTitle().string()}\""));
        assertFalse(board.contains("th:utext"));
        assertFalse(board.contains("https://"));
        assertTrue(
                Files.readString(
                                root.resolve(
                                        "src/main/java/example/tasks/adapter/inbound/web/WebSecurity.java"))
                        .contains("CSRF protection remains enabled"));
    }

    @Test
    void refusesToOverwriteEvenTheGeneratedMigrationBeforeWritingOtherFiles() throws Exception {
        Path root = temporary.resolve("occupied");
        Path migration =
                root.resolve(
                        "src/main/vernac-schema/history/V20261015090000000__initial_tasks.json");
        Files.createDirectories(migration.getParent());
        Files.writeString(migration, "keep me");
        assertThrows(java.io.IOException.class, () -> create(root, "example.tasks"));
        assertEquals("keep me", Files.readString(migration));
        assertFalse(Files.exists(root.resolve("pom.xml")));
        assertFalse(Files.exists(root.resolve("src/main/java")));
    }
}
