// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.generator;

import com.palantir.javapoet.JavaFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vernac.compiler.pipeline.VernacCompilationResult;
import org.vernac.compiler.pipeline.VernacCompiler;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryGeneratorTest {

    private final VernacCompiler compiler = new VernacCompiler();

    @Test
    @DisplayName("Generiert Domain-Interface und transaktionspflichtiges JDBC-Repository")
    void shouldGenerateCompleteRepositoryStructure() {
        String dsl = """
                namespace com.example.domain;
                
                
                id OrderId;
                id CustomerId;
                
                value Status(String);
                aggregate Order[OrderId](CustomerId customer, mut Status status);
                
                repository OrderRepository for Order {
                };
                """;

        VernacCompilationResult result = compiler.compileSource(dsl);

        List<String> typeNames = result.generatedFiles().stream()
                .map(f -> f.typeSpec().name())
                .toList();

        assertThat(typeNames).contains(
                "OrderId", "CustomerId", "Order",
                "OrderRepository", "JdbcOrderRepository"
        );

        JavaFile repoInterface = result.generatedFiles().stream()
                .filter(f -> f.typeSpec().name().equals("OrderRepository"))
                .findFirst().orElseThrow();

        String normalizedInterface = normalize(repoInterface.toString());
        assertThat(normalizedInterface)
                .contains("Order byId(OrderId id);")
                .contains("Order save(Order aggregate);")
                .contains("void delete(Order aggregate);");

        JavaFile jdbcRepo = result.generatedFiles().stream()
                .filter(f -> f.typeSpec().name().equals("JdbcOrderRepository"))
                .findFirst().orElseThrow();

        String normalizedJdbc = normalize(jdbcRepo.toString());
        assertThat(normalizedJdbc)
                .contains("@Transactional(propagation = Propagation.MANDATORY)")
                .contains("events.dispatch(\"com.example.domain.domain.Order\", aggregate.id().asString(), aggregate.pullDomainEvents());")
                .contains("JdbcAggregateStore<Order>")
                .contains("store.byId(id.value())")
                .contains("store.save(aggregate)")
                .contains("store.delete(aggregate)")
                .doesNotContain("withVersion(");
    }

    @Test
    @DisplayName("Generates shared entity state and collection bindings")
    void shouldGenerateJdbcRepositoryWithOneToManyEntityMapping() {
        String dsl = """
                namespace com.example.domain;
                
                id ProjectId;
                id TaskId;
                
                value Title(String); value ProjectName(String);
                entity Task[TaskId](Title title) list;
                aggregate Project[ProjectId](ProjectName name, mut Tasks tasks);
                
                repository for Project {
                };
                """;

        VernacCompilationResult result = compiler.compileSource(dsl);

        JavaFile jdbcRepo = result.generatedFiles().stream()
                .filter(f -> f.typeSpec().name().equals("JdbcProjectRepository"))
                .findFirst().orElseThrow();

        String code = jdbcRepo.toString().replaceAll("\\s+", " ");

        assertThat(code)
                .contains("JdbcMapping.entity(", "Project.@entity:com.example.domain.Task")
                .contains("JdbcMapping.collection(", "Project.tasks")
                .contains("Tasks.of(items.stream().map(Task.class::cast).toList())")
                .contains("Task.reconstitute(");
    }

    @Test
    @DisplayName("Recursively flattens nested value objects using the schema layout")
    void shouldFlattenNestedValueObjectsInSqlStatementsAndParams() {
        String dsl = """
                namespace com.example.domain;
                
                
                id AccountId;
                value MoneyCurrency(String isoCode);
                value Money(BigDecimal amount, MoneyCurrency currency);
                
                value Owner(String);
                aggregate Account[AccountId](Owner owner, mut Money balance);
                
                repository for Account {
                };
                """;

        VernacCompilationResult result = compiler.compileSource(dsl);

        JavaFile jdbcRepo = result.generatedFiles().stream()
                .filter(f -> f.typeSpec().name().equals("JdbcAccountRepository"))
                .findFirst().orElseThrow();

        String code = jdbcRepo.toString().replaceAll("\\s+", " ");

        assertThat(code)
                .contains("JdbcMapping.scalar(\"BigDecimal\", false, \"balance.amount\", \"@scale:balance.amount\")")
                .contains("JdbcMapping.scalar(\"String\", false, \"balance.currency\")")
                .contains("Money.of(", "MoneyCurrency.of(")
                .contains("((Money) value).amount()", "((Money) value).currency()");
    }

    @Test
    @DisplayName("Reconstructs primitive-backed value objects with typed factories")
    void shouldMapRowUsingBoxedTypesAndReconstructValueObjects() {
        String dsl = """
                namespace com.example.domain;
                
                id StorageId;
                value WattHours(int value);
                value BatterySoc(int percent);
                
                aggregate EnergyStorage[StorageId](WattHours capacity, BatterySoc currentSoc);
                
                repository for EnergyStorage {
                };
                """;

        VernacCompilationResult result = compiler.compileSource(dsl);

        JavaFile jdbcRepo = result.generatedFiles().stream()
                .filter(f -> f.typeSpec().name().equals("JdbcEnergyStorageRepository"))
                .findFirst().orElseThrow();

        String code = jdbcRepo.toString().replaceAll("\\s+", " ");

        assertThat(code)
                .contains("WattHours.of((Integer) values[0])")
                .contains("BatterySoc.of((Integer) values[0])")
                .contains("EnergyStorage.reconstitute(")
                .contains("JdbcMapping.scalar(\"int\", false, \"capacity\")")
                .contains("JdbcMapping.scalar(\"int\", false, \"currentSoc\")");
    }

    @Test
    @DisplayName("Flattens value objects inside entity state mappings")
    void shouldFlattenValueObjectsInChildEntitySync() {
        String dsl = """
                namespace com.example.domain;
                
                id ProjectId;
                id TaskId;
                value TaskDuration(int hours);
                
                value Title(String); value ProjectName(String);
                entity Task[TaskId](Title title, TaskDuration duration) list;
                aggregate Project[ProjectId](ProjectName name, mut Tasks tasks);
                
                repository for Project {
                };
                """;

        VernacCompilationResult result = compiler.compileSource(dsl);

        JavaFile jdbcRepo = result.generatedFiles().stream()
                .filter(f -> f.typeSpec().name().equals("JdbcProjectRepository"))
                .findFirst().orElseThrow();

        String code = jdbcRepo.toString().replaceAll("\\s+", " ");

        assertThat(code)
                .contains("((TaskDuration) value).hours()")
                .contains("TaskDuration.of((Integer) values[0])")
                .contains("JdbcMapping.scalar(\"int\", false, \"duration\")")
                .contains("Project.@entity:com.example.domain.Task");
    }

    private String normalize(String source) {
        return source.replaceAll("\\s+", " ")
                .replace("( ", "(")
                .replace(" )", ")")
                .trim();
    }
}