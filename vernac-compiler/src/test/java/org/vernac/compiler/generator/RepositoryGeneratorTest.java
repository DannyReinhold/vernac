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

    private String normalize(String source) {
        return source.replaceAll("\\s+", " ")
                .replace("( ", "(")
                .replace(" )", ")")
                .trim();
    }
}