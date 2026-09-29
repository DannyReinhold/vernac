# Vernac

> **Pragmatic, Tactical Domain-Driven Design for Java.**  
> Compile expressive domain models into pure, encapsulated Java classes—bypassing JPA pitfalls, enforcing invariants,
> and delivering first-class IDE tooling via LSP.

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen.svg)]()
[![Java Version](https://img.shields.io/badge/Java-21%2B%20%7C%2025-blue.svg)]()
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

Most Java architectures stumble when translating DDD to code: JPA entities leak ORM proxies into business logic, anemic
data holders replace encapsulated aggregates, and database rehydration triggers domain invariants by accident.

**Vernac solves this at the language level.** You model your Ubiquitous Language in clean `.vernac` files. The compiler
outputs battle-tested, rich domain classes and persistence code tailored strictly to tactical DDD principles.

---

## Why Vernac?

* **No Anemic Models, No Records**: Aggregates and Entities are generated as fully encapsulated classes with explicit
  mutators, invariant guarantees, and hidden internal state.
* **Separation of Creation & Rehydration**:
    - `create(...)`: Validates business invariants, initializes lifecycle state, and records domain events.
    - `reconstitute(...)`: Restores state directly from persistence without re-triggering creation rules or publishing
      spurious events.
* **JPA-Free Persistence**: Repositories generate explicit, predictable SQL/JDBC mapping with strict aggregate
  transaction boundaries. No lazy loading surprises, no detached entity gymnastics, no dirty-checking leaks.
* **Optimistic Locking Built-In**: Every aggregate handles version tracking natively for conflict-free concurrent
  modifications.
* **Native Anti-Corruption Layer (ACL)**: Define `service` contracts with `external schema` and bi-directional `mapping`
  to insulate your core domain from upstream API changes.
* **First-Class IDE Experience**: Backed by a full Language Server Protocol (LSP) implementation supporting real-time
  semantic diagnostics, grammar-aware completion, type navigation, and hover documentation in IntelliJ IDEA.

---

## At a Glance

```vernac
package com.example.shop;

value OrderId(UUID value);
value CustomerId(UUID value);
value Money(BigDecimal amount, String currency);

/**
 * Core order aggregate handling checkout state and payment.
 */
aggregate Order[OrderId id](
    CustomerId buyerId,
    Money total,
    mut OrderStatus status
) validates {
    require(total.amount().compareTo(BigDecimal.ZERO) >= 0, "Total cannot be negative");
} {
    public void markPaid() {
        if (this.status == OrderStatus.PAID) {
            throw new IllegalStateException("Order is already paid");
        }
        this.status = OrderStatus.PAID;
    }
}

repository OrderRepository for Order {
    table: "customer_orders";
    find Order findByBuyerId(CustomerId buyerId);
};

service PaymentGateway {
    external schema RemoteChargeRequest {
        charge_id: String;
        amount_cents: Long;
    }

    @Post("/v1/charges")
    ChargeResult charge(Money amount) mapping {
        amount.amount() -> amount_cents;
    };
}