package org.vernac.example.domain;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.vernac.example.adapter.db.JdbcOrderRepository;
import org.vernac.runtime.AggregateNotFoundException;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class JdbcOrderRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("vernac_test")
            .withUsername("vernac")
            .withPassword("vernac");

    private static HikariDataSource dataSource;
    private static NamedParameterJdbcTemplate jdbcTemplate;
    private static TransactionTemplate txTemplate;

    private JdbcOrderRepository repository;

    @BeforeAll
    static void initDatabase() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        dataSource = new HikariDataSource(config);

        jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        PlatformTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        txTemplate = new TransactionTemplate(txManager);

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("schema.sql"));
        populator.execute(dataSource);
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @BeforeEach
    void setUp() {
        repository = new JdbcOrderRepository(jdbcTemplate);
        txTemplate.executeWithoutResult(status -> {
            jdbcTemplate.getJdbcTemplate().execute("TRUNCATE TABLE order_lines, orders CASCADE");
        });
    }

    @Test
    @DisplayName("Speichert ein neues Aggregat mit Lines (INSERT) und rekonstituiert es mit Version 1")
    void shouldInsertAndLoadAggregate() {
        OrderId orderId = OrderId.of(UUID.randomUUID());
        CustomerId customerId = CustomerId.of(UUID.randomUUID());
        Currency eur = Currency.getInstance("EUR");

        OrderLine line1 = OrderLine.create(UUID.randomUUID(), ItemSku.of("SKU-1"), Money.of(new BigDecimal("19.99"), eur), 2);
        OrderLine line2 = OrderLine.create(UUID.randomUUID(), ItemSku.of("SKU-2"), Money.of(new BigDecimal("49.00"), eur), 1);

        Order initialOrder = Order.create(
                orderId,
                customerId,
                Money.of(new BigDecimal("88.98"), eur),
                "NEW",
                new ArrayList<>(List.of(line1, line2))
        );

        // 1. Speichern im Use-Case-Transaktionskontext
        Order saved = txTemplate.execute(status -> repository.save(initialOrder));

        assertThat(saved).isNotNull();
        assertThat(saved.version()).isEqualTo(1L);

        // 2. Laden via byId
        Order loaded = txTemplate.execute(status -> repository.byId(orderId));

        assertThat(loaded).isNotNull();
        assertThat(loaded.id()).isEqualTo(orderId);
        assertThat(loaded.customer()).isEqualTo(customerId);
        assertThat(loaded.status()).isEqualTo("NEW");
        assertThat(loaded.total().amount()).isEqualByComparingTo("88.98");
        assertThat(loaded.total().currency()).isEqualTo(eur);
        assertThat(loaded.version()).isEqualTo(1L);
        assertThat(loaded.lines()).hasSize(2);
    }

    @Test
    @DisplayName("Führt 3-Wege-Diff auf Child-Entities aus (Insert neu, Update bestehend, Delete entfernt)")
    void shouldSynchronizeChildEntitiesViaDiff() {
        OrderId orderId = OrderId.of(UUID.randomUUID());
        CustomerId customerId = CustomerId.of(UUID.randomUUID());
        Currency eur = Currency.getInstance("EUR");

        UUID line1Id = UUID.randomUUID();
        UUID line2Id = UUID.randomUUID();
        OrderLine line1 = OrderLine.create(line1Id, ItemSku.of("SKU-A"), Money.of(new BigDecimal("10.00"), eur), 1);
        OrderLine line2 = OrderLine.create(line2Id, ItemSku.of("SKU-B"), Money.of(new BigDecimal("20.00"), eur), 1);

        Order order = Order.create(
                orderId,
                customerId,
                Money.of(new BigDecimal("30.00"), eur),
                "NEW",
                new ArrayList<>(List.of(line1, line2))
        );

        Order savedV1 = txTemplate.execute(status -> repository.save(order));

        // Modifikation:
        // line1 Menge geändert (Update)
        // line2 entfernt (Delete)
        // line3 hinzugefügt (Insert)
        UUID line3Id = UUID.randomUUID();
        OrderLine line3 = OrderLine.create(line3Id, ItemSku.of("SKU-C"), Money.of(new BigDecimal("15.00"), eur), 3);
        OrderLine line1Modified = OrderLine.create(line1Id, ItemSku.of("SKU-A"), Money.of(new BigDecimal("10.00"), eur), 5);

        savedV1.completeOrder();
        List<OrderLine> newLines = new ArrayList<>(List.of(line1Modified, line3));

        Order orderToUpdate = Order.reconstitute(
                savedV1.id(),
                savedV1.customer(),
                savedV1.total(),
                savedV1.status(),
                newLines,
                savedV1.createdAt(),
                savedV1.updatedAt(),
                savedV1.version()
        );

        Order savedV2 = txTemplate.execute(status -> repository.save(orderToUpdate));

        assertThat(savedV2.version()).isEqualTo(2L);
        assertThat(savedV2.status()).isEqualTo("PAID");

        // Zustand aus der Datenbank verifizieren
        Order reloaded = txTemplate.execute(status -> repository.byId(orderId));
        assertThat(reloaded.lines()).hasSize(2);

        Map<UUID, OrderLine> linesById = new HashMap<>();
        reloaded.lines().forEach(l -> linesById.put(l.id(), l));

        assertThat(linesById.containsKey(line2Id)).as("Line 2 muss gelöscht worden sein").isFalse();
        assertThat(linesById.get(line1Id).quantity()).as("Line 1 muss auf Menge 5 aktualisiert sein").isEqualTo(5);
        assertThat(linesById.get(line3Id).sku().value()).isEqualTo("SKU-C");
    }

    @Test
    @DisplayName("Wirft OptimisticLockingFailureException bei veralteter Version")
    void shouldEnforceOptimisticLockingOnConcurrentModification() {
        OrderId orderId = OrderId.of(UUID.randomUUID());
        CustomerId customerId = CustomerId.of(UUID.randomUUID());
        Currency eur = Currency.getInstance("EUR");

        Order order = Order.create(orderId, customerId, Money.of(new BigDecimal("10.00"), eur), "NEW", new ArrayList<>());
        Order v1 = txTemplate.execute(status -> repository.save(order));

        // Erster Request aktualisiert erfolgreich auf V2
        v1.completeOrder();
        Order v2 = txTemplate.execute(status -> repository.save(v1));
        assertThat(v2.version()).isEqualTo(2L);

        // Zweiter Request versucht noch immer, auf Basis von V1 zu speichern
        Order concurrentAttempt = Order.reconstitute(
                v1.id(), v1.customer(), v1.total(), "CANCELLED",
                new ArrayList<>(), v1.createdAt(), v1.updatedAt(), 1L
        );

        assertThatThrownBy(() -> txTemplate.execute(status -> repository.save(concurrentAttempt)))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("Wirft AggregateNotFoundException wenn ID nicht in der DB existiert")
    void shouldThrowWhenNotFound() {
        OrderId nonExistent = OrderId.of(UUID.randomUUID());

        assertThatThrownBy(() -> txTemplate.execute(status -> repository.byId(nonExistent)))
                .isInstanceOf(AggregateNotFoundException.class)
                .hasMessageContaining("Order")
                .hasMessageContaining(nonExistent.value().toString());
    }

    @Test
    @DisplayName("Find-Methode liefert Datensätze gefiltert nach Kriterium")
    void shouldFindOrdersByStatus() {
        Currency eur = Currency.getInstance("EUR");
        Order o1 = Order.create(OrderId.of(UUID.randomUUID()), CustomerId.of(UUID.randomUUID()), Money.of(BigDecimal.TEN, eur), "PENDING", new ArrayList<>());
        Order o2 = Order.create(OrderId.of(UUID.randomUUID()), CustomerId.of(UUID.randomUUID()), Money.of(BigDecimal.ONE, eur), "SHIPPED", new ArrayList<>());

        txTemplate.executeWithoutResult(status -> {
            repository.save(o1);
            repository.save(o2);
        });

        List<Order> pending = txTemplate.execute(status -> repository.findByStatus("PENDING"));
        assertThat(pending).hasSize(1);
        assertThat(pending.getFirst().id()).isEqualTo(o1.id());
    }
}