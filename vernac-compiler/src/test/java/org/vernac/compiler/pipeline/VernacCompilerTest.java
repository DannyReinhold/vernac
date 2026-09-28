package org.vernac.compiler.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VernacCompilerTest {

    private final VernacCompiler compiler = new VernacCompiler();

    @Test
    @DisplayName("Kompiliert eine vollständige Domain-Spezifikation mit Value Objects, Events, Aggregates und Entities")
    void shouldCompileCompleteDomainSpecification(@TempDir Path tempDir) throws IOException {
        String dsl = """
                package com.example.domain;
                
                value ProjectId(UUID value);
                value TaskId(UUID value);
                value ProjectName(String value) validates {
                    require(value.length() <= 50, "Name too long");
                };
                value Money(BigDecimal amount, Currency currency);
                
                @Dispatch(mode = "OUTBOX")
                event ProjectCreated(ProjectId projectId, ProjectName name);
                
                aggregate Project[ProjectId](ProjectName name, mut Money budget) validates {
                    require(budget.amount().compareTo(BigDecimal.ZERO) >= 0, "Budget cannot be negative");
                } {
                    public void assignBudget(Money newBudget) {
                        setBudget(newBudget);
                    }
                };
                
                entity Task[TaskId](String title, mut Boolean completed);
                """;

        VernacCompilationResult result = compiler.compileSource(dsl);

        assertThat(result.packageName()).isEqualTo("com.example.domain");
        List<String> generatedTypeNames = result.generatedFiles().stream()
                .map(f -> f.typeSpec.name)
                .toList();

        // 4 Value Objects + 1 Event + 1 Aggregate + 1 Entity = 7 Klassen
        assertThat(generatedTypeNames).containsExactlyInAnyOrder(
                "ProjectId",
                "TaskId",
                "ProjectName",
                "Money",
                "ProjectCreated",
                "Project",
                "Task"
        );

        // Teste das Schreiben auf das Dateisystem
        result.writeTo(tempDir);

        Path packageDir = tempDir.resolve("com/example/domain");
        assertThat(Files.exists(packageDir.resolve("ProjectId.java"))).isTrue();
        assertThat(Files.exists(packageDir.resolve("ProjectName.java"))).isTrue();
        assertThat(Files.exists(packageDir.resolve("Money.java"))).isTrue();
        assertThat(Files.exists(packageDir.resolve("ProjectCreated.java"))).isTrue();
        assertThat(Files.exists(packageDir.resolve("Project.java"))).isTrue();
        assertThat(Files.exists(packageDir.resolve("Task.java"))).isTrue();
    }
}