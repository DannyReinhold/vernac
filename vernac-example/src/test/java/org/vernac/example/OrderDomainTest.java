// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.example;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vernac.example.domain.*;
import org.vernac.runtime.DomainEvent;
import org.vernac.runtime.DomainValidationException;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderDomainTest {

    private static final Currency EUR = Currency.getInstance("EUR");

    @Test
    @DisplayName("Value Object Operationen und Invarianten")
    void testValueObjectBehaviors() {
        Money price1 = Money.of(new BigDecimal("19.99"), EUR);
        Money price2 = Money.of(new BigDecimal("10.01"), EUR);

        Money sum = price1.add(price2);
        assertThat(sum.amount()).isEqualByComparingTo("30.00");

        assertThatThrownBy(() -> ItemSku.of("invalid_sku!"))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("SKU must be uppercase alphanumeric");
    }

    @Test
    @DisplayName("Entity kapselt interne Mutationen und Berechnungen")
    void testEntityLifecycle() {
        OrderLineId lineId = OrderLineId.create();
        ItemSku sku = ItemSku.of("BOOK-VERNAC-01");
        Money unitPrice = Money.of(new BigDecimal("49.50"), EUR);

        OrderLine line = OrderLine.create(lineId, sku, unitPrice, 1);
        assertThat(line.calculateSubtotal().amount()).isEqualByComparingTo("49.50");

        line.increaseQuantity(2);
        assertThat(line.quantity()).isEqualTo(3);
        assertThat(line.calculateSubtotal().amount()).isEqualByComparingTo("148.50");

        assertThatThrownBy(() -> line.increaseQuantity(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Aggregate registriert Domain Event und leert den Puffer beim Abholen")
    void testAggregateEventLifecycle() {
        OrderId orderId = OrderId.of(UUID.randomUUID());
        CustomerId customerId = CustomerId.of(UUID.randomUUID());
        Money total = Money.of(new BigDecimal("148.50"), EUR);

        Order order = Order.create(orderId, customerId, total, "PENDING", OrderLines.of());
        assertThat(order.status()).isEqualTo("PENDING");

        // Event buffer must initially be empty
        assertThat(order.pullDomainEvents()).isEmpty();

        // Change status and register event
        order.completeOrder();
        assertThat(order.status()).isEqualTo("PAID");

        // Verify event and clear buffer
        List<DomainEvent> events = order.pullDomainEvents();
        assertThat(events).hasSize(1);

        OrderPlaced event = (OrderPlaced) events.getFirst();
        assertThat(event.orderId()).isEqualTo(orderId);
        assertThat(event.customerId()).isEqualTo(customerId);
        assertThat(event.totalAmount()).isEqualTo(total);
        assertThat(event.occurredOn()).isNotNull();

        // Second call must be empty (pull semantics)
        assertThat(order.pullDomainEvents()).isEmpty();
    }
}