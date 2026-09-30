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
    @DisplayName("Kompiliert eine vollständige Domain- und Port-Spezifikation inklusive Dateisystem-Export")
    void shouldCompileCompleteDomainAndPortSpecification(@TempDir Path tempDir) throws IOException {
        String dsl = """
                package com.example;
                
                value ProjectId(UUID);
                value TaskId(UUID);
                value ProjectName(String value) validates {
                    require(value.length() <= 50, "Name too long");
                };
                value Money(BigDecimal amount, Currency currency);
                
                outbox event ProjectCreated(ProjectId projectId, ProjectName name);
                
                aggregate Project[ProjectId](ProjectName name, mut Money budget) validates {
                    require(budget.amount().compareTo(BigDecimal.ZERO) >= 0, "Budget cannot be negative");
                } {
                    public void assignBudget(Money newBudget) {
                        budget(newBudget);
                    }
                };
                
                entity Task[TaskId](String title, mut Boolean completed);
                
                port ProjectExternalService {
                    schema ExternalProjectDto {
                        String title;
                    }
                    Optional<String> fetchExternalData() {
                        adapter rest {
                            GET "/external/data";
                            on 404 return Optional.empty();
                        }
                    }
                }
                """;

        VernacCompilationResult result = compiler.compileSource(dsl);

        assertThat(result.packageName()).isEqualTo("com.example");
        List<String> generatedTypeNames = result.generatedFiles().stream()
                .map(f -> f.typeSpec.name)
                .toList();

        // 4 Value Objects + 1 Event + 1 Aggregate + 1 Entity + 1 Port + 1 Schema + 1 Adapter = 10 Klassen
        assertThat(generatedTypeNames).contains(
                "ProjectId",
                "TaskId",
                "ProjectName",
                "Money",
                "ProjectCreated",
                "Project",
                "Task",
                "ProjectExternalService",
                "ExternalProjectDto",
                "RestProjectExternalServiceFetchExternalDataAdapter"
        );

        // Teste das Schreiben auf das Dateisystem (Domänen- und Infrastruktur-Packages)
        result.writeTo(tempDir);

        Path domainDir = tempDir.resolve("com/example/domain");
        assertThat(Files.exists(domainDir.resolve("ProjectId.java"))).isTrue();
        assertThat(Files.exists(domainDir.resolve("Project.java"))).isTrue();
        assertThat(Files.exists(domainDir.resolve("ProjectExternalService.java"))).isTrue();

        Path outboundDir = tempDir.resolve("com/example/infrastructure/outbound/projectexternalservice");
        assertThat(Files.exists(outboundDir.resolve("ExternalProjectDto.java"))).isTrue();
        assertThat(Files.exists(outboundDir.resolve("RestProjectExternalServiceFetchExternalDataAdapter.java"))).isTrue();
    }
}