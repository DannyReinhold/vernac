// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.maven;

import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.MojoExecutionException;
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
        Files.createDirectories(srcDir.resolve("com/example/demo"));

        String sampleDsl = """
                namespace com.example.demo;
                
                id OrderId;
                value OrderReference(OrderId id, Title title);
                """;

        Files.writeString(srcDir.resolve("com/example/demo/orders.vernac"), sampleDsl);

        Files.writeString(srcDir.resolve("com/example/demo/title.vernac"),
                "namespace com.example.demo; value Title(String value);");

        VernacCompileMojo mojo = new VernacCompileMojo();
        mojo.setSourceDirectory(srcDir.toFile());
        mojo.setOutputDirectory(outDir.toFile());
        MavenProject project = new MavenProject();
        mojo.setProject(project);

        mojo.execute();

        assertThat(project.getCompileSourceRoots()).contains(outDir.toString());

        Path packageDir = outDir.resolve("com/example/demo/domain");
        assertThat(Files.exists(packageDir.resolve("OrderId.java"))).isTrue();
        assertThat(Files.exists(packageDir.resolve("Title.java"))).isTrue();
        assertThat(Files.exists(packageDir.resolve("OrderReference.java"))).isTrue();
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
                .hasMessageContaining("Failed to compile Vernac project");
    }

    @Test
    void rejectsInvalidProjectBeforeWritingAnySources(@TempDir Path root) throws Exception {
        Path source = root.resolve("src");
        Files.createDirectories(source.resolve("example"));
        Files.writeString(source.resolve("example/good.vernac"),
                "namespace example; id TaskId;");
        Files.writeString(source.resolve("example/bad.vernac"),
                "namespace example; value Broken(Missing value);");
        Path output = root.resolve("generated");
        VernacCompileMojo mojo = new VernacCompileMojo();
        mojo.setSourceDirectory(source.toFile());
        mojo.setOutputDirectory(output.toFile());
        MavenProject project = new MavenProject();
        mojo.setProject(project);
        assertThatThrownBy(mojo::execute).isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("bad.vernac").hasMessageContaining("Missing");
        assertThat(output).doesNotExist();
        assertThat(project.getCompileSourceRoots()).isEmpty();
    }

    @Test
    void distinguishesFilesystemFailureFromInvalidDsl(@TempDir Path root) throws Exception {
        Path source = root.resolve("not-a-directory");
        Files.writeString(source, "placeholder");
        VernacCompileMojo mojo = new VernacCompileMojo();
        mojo.setSourceDirectory(source.toFile());
        mojo.setOutputDirectory(root.resolve("generated").toFile());
        assertThatThrownBy(mojo::execute).isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("not a directory");
    }
}
