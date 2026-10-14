// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package example.shop.demo;

import example.billing.domain.CustomerName;
import example.shop.usecase.ReadCustomer;
import example.shop.usecase.RegisterPurchase;
import example.shop.usecase.RejectPurchase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.vernac.runtime.DomainValidationException;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** Real persistence, local-only demo data; no external payment is made. */
@SpringBootApplication(scanBasePackages = "example.shop")
public class ModuleLifecycleApplication {
    public static void main(String[] args) { SpringApplication.run(ModuleLifecycleApplication.class, args); }
    @Bean ApplicationRunner demonstrate(RegisterPurchase registerPurchase, ReadCustomer readCustomer,
                                      RejectPurchase rejectPurchase, JdbcTemplate jdbc) {
        return arguments -> {
            var customer = CustomerName.of("Module lifecycle customer");
            var accountId = registerPurchase.execute(customer);
            var restored = readCustomer.execute(accountId);
            if (!customer.equals(restored)) throw new IllegalStateException("External value round trip failed");
            var log = LoggerFactory.getLogger(ModuleLifecycleApplication.class);
            log.info("Startup completed: required Billing and dependent Receipts histories ran before the application started.");
            log.info("Completed: application -> Receipts -> Billing usecases using REQUIRED transaction propagation.");
            log.info("Verified: external CustomerName round trip: {}", restored);
            long before = storedRows(jdbc);
            boolean rejected = false;
            try { rejectPurchase.execute(customer); }
            catch (DomainValidationException expected) { rejected = true; }
            if (!rejected || before != storedRows(jdbc)) throw new IllegalStateException("Cross-module rollback failed");
            log.info("Verified: failed purchase rolled back Billing, Receipts and application writes together.");
            var invoiceId = jdbc.queryForObject("SELECT \"invoiceId\" FROM \"example.shop\".\"Account\" WHERE id=?", java.util.UUID.class, accountId.value());
            var eventId = jdbc.queryForObject("SELECT \"@eventId\" FROM \"example.billing\".\"@event:InvoiceOpened\" WHERE \"invoiceId\"=?", java.util.UUID.class, invoiceId);
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
            while (successfulDeliveries(jdbc, eventId) < 2 && System.nanoTime() < deadline) Thread.sleep(50);
            if (successfulDeliveries(jdbc, eventId) != 2) throw new IllegalStateException("Deferred delivery did not finish before the demo deadline");
            Long notifications = jdbc.queryForObject("SELECT count(*) FROM \"example.receipts\".\"Notification\" WHERE \"invoiceId\"=?", Long.class, invoiceId);
            if (!Long.valueOf(1).equals(notifications)) throw new IllegalStateException("Retry duplicated a committed notification");
            log.info("Verified: both deferred recipients confirmed independently; one notification survived the deliberate failure and retry.");
            log.info("Verified: the producer owns the recorded payload; the application owns the shared delivery table.");
            log.info("Account {} remains available for the version-upgrade walkthrough. No payment was made.", accountId);
        };
    }
    private static long successfulDeliveries(JdbcTemplate jdbc, java.util.UUID eventId) {
        return java.util.Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM \"vernac\".\"@delivery\" WHERE \"eventId\"=? AND status='SUCCEEDED'", Long.class, eventId));
    }
    private static long storedRows(JdbcTemplate jdbc) {
        Long count = jdbc.queryForObject("SELECT (SELECT count(*) FROM \"example.billing\".\"Invoice\") + "
      + "(SELECT count(*) FROM \"example.receipts\".\"Receipt\") + "
      + "(SELECT count(*) FROM \"example.shop\".\"Account\")", Long.class);
        return java.util.Objects.requireNonNull(count);
    }
}
