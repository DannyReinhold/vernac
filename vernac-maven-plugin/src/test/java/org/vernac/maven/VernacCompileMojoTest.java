package org.vernac.maven;

import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VernacCompileMojoTest {

    @Test
    @DisplayName("Kompiliert gefundene .vernac-Dateien und legt Java-Klassen im Zielordner ab")
    void shouldCompileVernacFiles(@TempDir Path tempDir) throws Exception {
        Path srcDir = tempDir.resolve("src/main/vernac");
        Path outDir = tempDir.resolve("target/generated-sources/vernac");
        Files.createDirectories(srcDir);

        String sampleDsl = """
                package com.example.demo;
                
                id OrderId;
                event OrderCreated(OrderId id);
                aggregate Order[OrderId](mut String status);
                """;

        Files.writeString(srcDir.resolve("orders.vernac"), sampleDsl);

        VernacCompileMojo mojo = new VernacCompileMojo();
        mojo.setSourceDirectory(srcDir.toFile());
        mojo.setOutputDirectory(outDir.toFile());
        mojo.setProject(new MavenProject());

        mojo.execute();

        Path packageDir = outDir.resolve("com/example/demo/domain");
        assertThat(Files.exists(packageDir.resolve("OrderId.java"))).isTrue();
        assertThat(Files.exists(packageDir.resolve("OrderCreated.java"))).isTrue();
        assertThat(Files.exists(packageDir.resolve("Order.java"))).isTrue();
    }

    @Test
    @DisplayName("Wirft MojoFailureException bei Syntaxfehlern in der DSL")
    void shouldFailOnSyntaxError(@TempDir Path tempDir) throws IOException {
        Path srcDir = tempDir.resolve("src/main/vernac");
        Path outDir = tempDir.resolve("target/generated-sources/vernac");
        Files.createDirectories(srcDir);

        Files.writeString(srcDir.resolve("broken.vernac"), "this is not valid vernac syntax !!!");

        VernacCompileMojo mojo = new VernacCompileMojo();
        mojo.setSourceDirectory(srcDir.toFile());
        mojo.setOutputDirectory(outDir.toFile());
        mojo.setProject(new MavenProject());

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("Failed to compile Vernac file");
    }
}