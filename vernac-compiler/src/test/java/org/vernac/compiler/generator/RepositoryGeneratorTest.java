package org.vernac.compiler.generator;

import com.squareup.javapoet.JavaFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vernac.compiler.pipeline.VernacCompilationResult;
import org.vernac.compiler.pipeline.VernacCompiler;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryGeneratorTest {

    private final VernacCompiler compiler = new VernacCompiler();

    @Test
    @DisplayName("Generiert Interface, Custom-Fragment und Jdbc-Repository")
    void shouldGenerateCompleteRepositoryStructure() {
        String dsl = """
                package com.example.domain;
                
                import java.math.BigDecimal;
                
                value OrderId(UUID value);
                value CustomerId(UUID value);
                
                aggregate Order[OrderId](CustomerId customer, mut String status);
                
                repository OrderRepository for Order {
                    table: "orders";
                    find List<Order> findByStatus(String status);
                    custom List<Order> findTopOrders(BigDecimal threshold);
                };
                """;

        VernacCompilationResult result = compiler.compileSource(dsl);

        List<String> typeNames = result.generatedFiles().stream()
                .map(f -> f.typeSpec.name)
                .toList();

        assertThat(typeNames).contains(
                "OrderId", "CustomerId", "Order",
                "OrderRepository", "OrderRepositoryCustom", "JdbcOrderRepository"
        );

        JavaFile repoInterface = result.generatedFiles().stream()
                .filter(f -> f.typeSpec.name.equals("OrderRepository"))
                .findFirst().orElseThrow();

        String normalizedInterface = normalize(repoInterface.toString());
        assertThat(normalizedInterface)
                .contains("Order byId(OrderId id);")
                .contains("Order save(Order aggregate);")
                .contains("void delete(Order aggregate);")
                .contains("List<Order> findByStatus(String status);")
                .contains("interface OrderRepository extends OrderRepositoryCustom");

        JavaFile jdbcRepo = result.generatedFiles().stream()
                .filter(f -> f.typeSpec.name.equals("JdbcOrderRepository"))
                .findFirst().orElseThrow();

        String normalizedJdbc = normalize(jdbcRepo.toString());
        assertThat(normalizedJdbc)
                .contains("@Transactional(propagation = Propagation.MANDATORY)")
                .contains("this.customDelegate = Objects.requireNonNull(customDelegate")
                .contains("throw new AggregateNotFoundException")
                .contains("throw new OptimisticLockingFailureException");
    }

    @Test
    @DisplayName("Generiert 1:N Entity-Mapping Methoden (sync und fetch) in JDBC Repositories")
    void shouldGenerateJdbcRepositoryWithOneToManyEntityMapping() {
        String dsl = """
                package com.example.domain;
                
                value ProjectId(UUID value);
                value TaskId(UUID value);
                
                entity Task[TaskId](String title);
                aggregate Project[ProjectId](String name, mut List<Task> tasks);
                
                repository for Project {
                    table: "projects";
                };
                """;

        VernacCompilationResult result = compiler.compileSource(dsl);

        JavaFile jdbcRepo = result.generatedFiles().stream()
                .filter(f -> f.typeSpec.name.equals("JdbcProjectRepository"))
                .findFirst().orElseThrow();

        String code = jdbcRepo.toString().replaceAll("\\s+", " ");

        // Prüft, ob die Sub-Methoden für die Liste generiert wurden
        assertThat(code)
                .contains("private List<Task> fetchTasks(ProjectId aggregateId)")
                .contains("private void syncTasks(ProjectId aggregateId, List<Task> items)")
                .contains("private MapSqlParameterSource buildTaskParamSource(ProjectId aggregateId, Task item)");

        // Prüft, ob der Haupt-Mapper die Unterabfrage aufruft
        assertThat(code).contains("fetchTasks(id)");
    }

    private String normalize(String source) {
        return source.replaceAll("\\s+", " ")
                .replace("( ", "(")
                .replace(" )", ")")
                .trim();
    }
}