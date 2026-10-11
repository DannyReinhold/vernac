// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

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
                namespace com.example;
                
                
                id ProjectId;
                id TaskId;
                value ProjectName(String value) validates {
                    require(self.value().length() <= 50, "Name too long");
                };
                value Money(BigDecimal amount, Currency currency);
                
                event ProjectCreated(ProjectId projectId, ProjectName name);
                
                aggregate Project[ProjectId](ProjectName name, mut Money budget) validates {
                    require(self.budget().amount().compareTo(BigDecimal.ZERO) >= 0, "Budget cannot be negative");
                } behavior {
                    modify void assignBudget(Money newBudget) {
                        self.budget(newBudget);
                    }
                };
                
                value Title(String); value Completed(Boolean value);
                entity Task[TaskId](Title title, mut Completed completed);
                
                port ProjectExternalService {
                    ProjectName? fetchExternalData();
                }
                adapter LocalProjectService implements ProjectExternalService {
                    behavior {
                        public ProjectName? fetchExternalData() { return Optional.empty(); }
                    }
                }
                """;

        VernacCompilationResult result = compiler.compileSource(dsl);

        assertThat(result.packageName()).isEqualTo("com.example");
        List<String> generatedTypeNames = result.generatedFiles().stream()
                .map(f -> f.typeSpec().name())
                .toList();

        // Domain types, a port, and its separate adapter are generated together.
        assertThat(generatedTypeNames).contains(
                "ProjectId",
                "TaskId",
                "ProjectName",
                "Money",
                "ProjectCreated",
                "Project",
                "Task",
                "ProjectExternalService",
                "LocalProjectService"
        );

        // Teste das Schreiben auf das Dateisystem (Domänen- und Infrastruktur-Packages)
        result.writeTo(tempDir);

        Path domainDir = tempDir.resolve("com/example/domain");
        assertThat(Files.exists(domainDir.resolve("ProjectId.java"))).isTrue();
        assertThat(Files.exists(domainDir.resolve("Project.java"))).isTrue();
        assertThat(Files.exists(domainDir.resolve("ProjectExternalService.java"))).isTrue();

        Path outboundDir = tempDir.resolve("com/example/adapter/outbound");
        assertThat(Files.exists(outboundDir.resolve("LocalProjectService.java"))).isTrue();
    }
}