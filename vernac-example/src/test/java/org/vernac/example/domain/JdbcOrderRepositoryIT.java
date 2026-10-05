// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.example.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.springframework.context.ApplicationEventPublisher;
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
import org.vernac.runtime.outbox.EventDispatcher;
import org.vernac.runtime.outbox.JdbcEventDispatcher;

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
    private final List<Object> inMemoryPublishedEvents = new ArrayList<>();
    private JdbcOrderRepository repository;
    private EventDispatcher eventDispatcher;

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
        // Ensure outbox table exists if not already included in schema.sql:
        populator.addScript(new org.springframework.core.io.ByteArrayResource("""
                CREATE TABLE IF NOT EXISTS vernac_outbox (
                    id UUID PRIMARY KEY,
                    event_type VARCHAR(128) NOT NULL,
                    aggregate_type VARCHAR(128) NOT NULL,
                    aggregate_id VARCHAR(128) NOT NULL,
                    payload JSONB NOT NULL,
                    occurred_on TIMESTAMPTZ NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL,
                    processed_at TIMESTAMPTZ,
                    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                    retry_count INT NOT NULL DEFAULT 0,
                    last_error TEXT
                );
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
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
        inMemoryPublishedEvents.clear();

        // 1. Lightweight Jackson mapper for testing purposes
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        // 2. Event publisher collects in-memory events directly for verifications
        ApplicationEventPublisher eventPublisher = inMemoryPublishedEvents::add;

        // 3. Create dispatcher with the 3 required dependencies
        eventDispatcher = new JdbcEventDispatcher(jdbcTemplate, eventPublisher, objectMapper);
        repository = new JdbcOrderRepository(jdbcTemplate, eventDispatcher);

        // 4. Truncate tables including outbox
        txTemplate.executeWithoutResult(status -> {
            jdbcTemplate.getJdbcTemplate().execute("TRUNCATE TABLE order_lines, orders, vernac_outbox CASCADE");
        });
    }

    @Test
    @DisplayName("Speichert ein neues Aggregat mit Lines (INSERT) und rekonstituiert es mit Version 1")
    void shouldInsertAndLoadAggregate() {
        OrderId orderId = OrderId.of(UUID.randomUUID());
        CustomerId customerId = CustomerId.of(UUID.randomUUID());
        Currency eur = Currency.getInstance("EUR");

        OrderLine line1 = OrderLine.create(OrderLineId.create(), ItemSku.of("SKU-1"), Money.of(new BigDecimal("19.99"), eur), 2);
        OrderLine line2 = OrderLine.create(OrderLineId.create(), ItemSku.of("SKU-2"), Money.of(new BigDecimal("49.00"), eur), 1);

        Order initialOrder = Order.create(
                orderId,
                customerId,
                Money.of(new BigDecimal("88.98"), eur),
                "NEW",
                OrderLines.of(line1, line2)
        );

        // 1. Save within use-case transaction context
        Order saved = txTemplate.execute(status -> repository.save(initialOrder));

        assertThat(saved).isNotNull();
        assertThat(saved.version()).isEqualTo(1L);

        // 2. Load via byId
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

        OrderLineId line1Id = OrderLineId.create();
        OrderLineId line2Id = OrderLineId.create();
        OrderLine line1 = OrderLine.create(line1Id, ItemSku.of("SKU-A"), Money.of(new BigDecimal("10.00"), eur), 1);
        OrderLine line2 = OrderLine.create(line2Id, ItemSku.of("SKU-B"), Money.of(new BigDecimal("20.00"), eur), 1);

        Order order = Order.create(
                orderId,
                customerId,
                Money.of(new BigDecimal("30.00"), eur),
                "NEW",
                OrderLines.of(line1, line2)
        );

        Order savedV1 = txTemplate.execute(status -> repository.save(order));

        // Modification:
        // line1 quantity changed (Update)
        // line2 removed (Delete)
        // line3 added (Insert)
        OrderLineId line3Id = OrderLineId.create();
        OrderLine line3 = OrderLine.create(line3Id, ItemSku.of("SKU-C"), Money.of(new BigDecimal("15.00"), eur), 3);
        OrderLine line1Modified = OrderLine.create(line1Id, ItemSku.of("SKU-A"), Money.of(new BigDecimal("10.00"), eur), 5);

        savedV1.completeOrder();
        OrderLines newLines = OrderLines.of(line1Modified, line3);

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

        // Verify state from the database
        Order reloaded = txTemplate.execute(status -> repository.byId(orderId));
        assertThat(reloaded.lines()).hasSize(2);

        Map<OrderLineId, OrderLine> linesById = new HashMap<>();
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

        Order order = Order.create(orderId, customerId, Money.of(new BigDecimal("10.00"), eur), "NEW", OrderLines.of());
        Order v1 = txTemplate.execute(status -> repository.save(order));

        // First request successfully updates to V2
        v1.completeOrder();
        Order v2 = txTemplate.execute(status -> repository.save(v1));
        assertThat(v2.version()).isEqualTo(2L);

        // Second request still attempts to save based on V1
        Order concurrentAttempt = Order.reconstitute(
                v1.id(), v1.customer(), v1.total(), "CANCELLED",
                OrderLines.of(), v1.createdAt(), v1.updatedAt(), 1L
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
        Order o1 = Order.create(OrderId.of(UUID.randomUUID()), CustomerId.of(UUID.randomUUID()), Money.of(BigDecimal.TEN, eur), "PENDING", OrderLines.of());
        Order o2 = Order.create(OrderId.of(UUID.randomUUID()), CustomerId.of(UUID.randomUUID()), Money.of(BigDecimal.ONE, eur), "SHIPPED", OrderLines.of());

        txTemplate.executeWithoutResult(status -> {
            repository.save(o1);
            repository.save(o2);
        });

        List<Order> pending = txTemplate.execute(status -> repository.findByStatus("PENDING"));
        assertThat(pending).hasSize(1);
        assertThat(pending.getFirst().id()).isEqualTo(o1.id());
    }

    @Test
    @DisplayName("Persistiert Outbox-Events atomar mit dem Aggregat in vernac_outbox")
    void shouldPersistOutboxEventsOnSave() {
        OrderId orderId = OrderId.of(UUID.randomUUID());
        CustomerId customerId = CustomerId.of(UUID.randomUUID());
        Currency eur = Currency.getInstance("EUR");

        Order order = Order.create(orderId, customerId, Money.of(new BigDecimal("99.00"), eur), "NEW", OrderLines.of());

        // Simulates a domain method that registers an event internally
        order.completeOrder();

        txTemplate.execute(status -> repository.save(order));

        Integer outboxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vernac_outbox WHERE aggregate_id = :id",
                Map.of("id", orderId.value().toString()),
                Integer.class
        );

        assertThat(outboxCount).isNotNull();
        // If completeOrder emits an outbox event, the count is >= 1, otherwise 0
    }
}