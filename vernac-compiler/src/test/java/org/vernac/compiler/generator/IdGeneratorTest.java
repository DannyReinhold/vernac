package org.vernac.compiler.generator;

import com.palantir.javapoet.JavaFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vernac.compiler.ast.IdDeclarationNode;
import org.vernac.compiler.ast.SourceLocation;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class IdGeneratorTest {

    private final IdGenerator generator = new IdGenerator();

    @Test
    @DisplayName("Generiert typsicheres ID-Value-Object mit UUID-Kapselung, Factory-Methoden und Standardverträgen")
    void shouldGenerateCompleteIdClass() {
        IdDeclarationNode node = new IdDeclarationNode(new SourceLocation(1, 1), "ProjectId", Optional.empty());
        JavaFile file = generator.generate(node, "com.example");
        String code = file.toString().replaceAll("\\s+", " ");

        assertThat(code)
                .contains("package com.example.domain;")
                .contains("public final class ProjectId")
                .contains("private final UUID value;")
                .contains("private ProjectId(UUID value)")
                .contains("public static ProjectId create() { return new ProjectId(UUID.randomUUID()); }")
                .contains("public static ProjectId of(UUID value) { return new ProjectId(value); }")
                .contains("public static ProjectId of(String value)")
                .contains("UUID.fromString(value)")
                .contains("public UUID value() { return this.value; }")
                .contains("public boolean equals(Object o)")
                .contains("public int hashCode()")
                .contains("public String toString() { return this.value.toString(); }");
    }

    @Test
    @DisplayName("Berücksichtigt benutzerdefiniertes Package-Override via { package ...; }")
    void shouldRespectCustomPackageOverride() {
        IdDeclarationNode node = new IdDeclarationNode(
                new SourceLocation(1, 1),
                "SharedId",
                Optional.of("com.example.shared.kernel")
        );
        JavaFile file = generator.generate(node, "com.example");

        assertThat(file.toString()).contains("package com.example.shared.kernel;");
        assertThat(file.typeSpec().name()).isEqualTo("SharedId");
    }
}