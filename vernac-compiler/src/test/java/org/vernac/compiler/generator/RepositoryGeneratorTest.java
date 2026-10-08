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
    @DisplayName("Generiert Interface, Custom-Fragment und Jdbc-Repository")
    void shouldGenerateCompleteRepositoryStructure() {
        String dsl = """
                namespace com.example.domain;
                
                
                id OrderId;
                id CustomerId;
                
                value Status(String);
                aggregate Order[OrderId](CustomerId customer, mut Status status);
                
                repository OrderRepository for Order {
                    find List<Order> findByStatus(String status);
                    custom List<Order> findTopOrders(BigDecimal threshold);
                };
                """;

        VernacCompilationResult result = compiler.compileSource(dsl);

        List<String> typeNames = result.generatedFiles().stream()
                .map(f -> f.typeSpec().name())
                .toList();

        assertThat(typeNames).contains(
                "OrderId", "CustomerId", "Order",
                "OrderRepository", "OrderRepositoryCustom", "JdbcOrderRepository"
        );

        JavaFile repoInterface = result.generatedFiles().stream()
                .filter(f -> f.typeSpec().name().equals("OrderRepository"))
                .findFirst().orElseThrow();

        String normalizedInterface = normalize(repoInterface.toString());
        assertThat(normalizedInterface)
                .contains("Order byId(OrderId id);")
                .contains("Order save(Order aggregate);")
                .contains("void delete(Order aggregate);")
                .contains("List<Order> findByStatus(String status);")
                .contains("interface OrderRepository extends OrderRepositoryCustom");

        JavaFile jdbcRepo = result.generatedFiles().stream()
                .filter(f -> f.typeSpec().name().equals("JdbcOrderRepository"))
                .findFirst().orElseThrow();

        String normalizedJdbc = normalize(jdbcRepo.toString());
        assertThat(normalizedJdbc)
                .contains("@Transactional(propagation = Propagation.MANDATORY)")
                .contains("this.eventDispatcher = Objects.requireNonNull(eventDispatcher, \"eventDispatcher must not be null\");")
                .contains("this.eventDispatcher.dispatch(\"Order\", aggregate.id().value().toString(), aggregate.pullDomainEvents());")
                .contains("throw new AggregateNotFoundException")
                .contains("throw new OptimisticLockingFailureException")
                .contains("long currentVersion = aggregate.persistenceState().version()")
                .contains("aggregate.persistenceState().version(nextVersion)")
                .doesNotContain("withVersion(");
    }

    @Test
    @DisplayName("Generiert 1:N Entity-Mapping Methoden (sync und fetch) in JDBC Repositories")
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
                .contains("private Tasks fetchTasks(ProjectId aggregateId)")
                .contains("private void syncTasks(ProjectId aggregateId, Tasks items)")
                .contains("private MapSqlParameterSource buildTaskParamSource(ProjectId aggregateId, Task item)");

        assertThat(code).contains("fetchTasks(id)");
    }

    @Test
    @DisplayName("Flacht Multi-Value-Objects und geschachtelte Value-Objects in SQL und Parametern rekursiv ab")
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
                .contains("INSERT INTO account (id, created_at, updated_at, version, owner, balance_amount, balance_currency) VALUES (:id, :createdAt, :updatedAt, :version, :owner, :balanceAmount, :balanceCurrency)")
                .contains("params.addValue(\"balanceAmount\", aggregate.balance().amount())")
                .contains("params.addValue(\"balanceCurrency\", aggregate.balance().currency().isoCode())")
                .contains("balance_amount = :balanceAmount")
                .contains("balance_currency = :balanceCurrency");
    }

    @Test
    @DisplayName("Liest primitive Attribute in Value Objects mit Boxed Types (Integer.class) und rekonstruiert verschachtelte VOs im RowMapper")
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
                .contains("WattHours capacity = WattHours.of(rs.getObject(\"capacity\", java.lang.Integer.class));")
                .contains("BatterySoc currentSoc = BatterySoc.of(rs.getObject(\"current_soc\", java.lang.Integer.class));")
                .contains("return EnergyStorage.reconstitute(id, capacity, currentSoc, createdAt, updatedAt, version);");
    }

    @Test
    @DisplayName("Flacht Value Objects auch in 1:N Child-Entity Sync-Statements sauber ab")
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
                .contains("params.addValue(\"duration\", item.duration().hours())")
                .contains("INSERT INTO task (id, project_id, title, duration) VALUES (:id, :parentId, :title, :duration)")
                .contains("TaskDuration duration = TaskDuration.of(rs.getObject(\"duration\", java.lang.Integer.class));");
    }

    private String normalize(String source) {
        return source.replaceAll("\\s+", " ")
                .replace("( ", "(")
                .replace(" )", ")")
                .trim();
    }
}