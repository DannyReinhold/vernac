// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.invoicing.pdf;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.vernac.saas.identity.domain.AccountId;
import org.vernac.saas.invoicing.*;
import org.vernac.saas.invoicing.domain.*;
import org.vernac.saas.payments.domain.*;
import org.vernac.saas.payments.persistence.PaymentFlow;
import org.vernac.saas.payments.persistence.domain.*;
import org.vernac.saas.subscriptions.*;
import org.vernac.saas.subscriptions.domain.*;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@EnabledIfEnvironmentVariable(named = "VERNAC_PG_TEST_URL", matches = ".+")
class InvoiceFlowPostgresIT {
    @TempDir Path root;
    static final PaymentProfileKey LOCAL = PaymentProfileKey.of("local");
    static final PaymentUri URL = PaymentUri.of("https://example.test/payment");

    static final class Time extends Clock {
        final AtomicReference<Instant> value =
                new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

        void advance(long seconds) {
            value.updateAndGet(v -> v.plusSeconds(seconds));
        }

        public Instant instant() {
            return value.get();
        }

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    record Fixture(
            InvoiceLedger invoices,
            PaymentFlow payments,
            SubscriptionLedger subscriptions,
            NamedParameterJdbcTemplate jdbc) {}

    static OfferTerms terms(String key, long price) {
        return OfferTerms.of(
                PlanKey.of(key),
                OfferRevision.of("v1"),
                MinorUnits.of(price),
                BillingCurrency.EUR,
                BillingInterval.MONTHLY,
                Benefits.of(Map.of()));
    }

    static final OfferTerms FREE = terms("free", 0), PAID = terms("pro", 1200);

    static Fixture fixture(
            AssertableApplicationContext context, Time time, Set<AccountId> allowed) {
        assertNull(context.getStartupFailure());
        var jdbc = context.getBean(NamedParameterJdbcTemplate.class);
        var manager = context.getBean(PlatformTransactionManager.class);
        var catalog = new PlanCatalog(List.of(FREE, PAID), PlanCatalog.id(FREE));
        var subscriptions = new SubscriptionLedger(jdbc, manager, catalog, time);
        // Synthetic authorization fixtures isolate module/DB contracts; HTTP/Identity acceptance is
        // separate.
        var payments =
                new PaymentFlow(
                        jdbc,
                        manager,
                        Map.of(LOCAL, context.getBean(PaymentProvider.class)),
                        catalog,
                        subscriptions,
                        account -> {
                            if (!allowed.contains(account)) throw new IllegalArgumentException();
                        },
                        profile -> {},
                        time);
        var renderer = context.getBean(InvoiceRenderer.class);
        var seller =
                ZugferdDocumentsTest.content(
                                1200, InvoiceTaxTreatment.STANDARD_19, InvoiceKind.INVOICE)
                        .seller();
        var invoices =
                new InvoiceLedger(
                        jdbc,
                        manager,
                        payments,
                        seller,
                        InvoiceSeries.of("RE"),
                        InvoiceText.of("Nutzung der SaaS-Anwendung"),
                        renderer,
                        account -> {
                            if (!allowed.contains(account)) throw new IllegalArgumentException();
                        },
                        time);
        return new Fixture(invoices, payments, subscriptions, jdbc);
    }

    static InvoiceBuyer buyer(String name) {
        var base =
                ZugferdDocumentsTest.content(
                                1200, InvoiceTaxTreatment.STANDARD_19, InvoiceKind.INVOICE)
                        .buyer();
        return InvoiceBuyer.of(ZugferdDocumentsTest.party(name), base.contact(), base.customer());
    }

    static PaymentOrderId purchase(Fixture f, Time time, AccountId account) {
        var order =
                f.payments.checkout(
                        account, PaymentOperationId.create(), LOCAL, PlanCatalog.id(PAID));
        for (int n = 0; n < 3; n++) assertTrue(f.payments.processOne(URL, URL));
        time.advance(30);
        assertTrue(f.payments.processOne(URL, URL));
        assertTrue(f.payments.processOne(URL, URL));
        return order.id();
    }

    @Test
    void issuedHybridSurvivesRestartAndProfileChangesWhileForeignDownloadsRemainDenied()
            throws Exception {
        var account = AccountId.create();
        var other = AccountId.create();
        var time = new Time();
        var id = new AtomicReference<InvoiceId>();
        var bytes = new AtomicReference<RenderedInvoice>();
        new InvoiceTestEnvironment(root)
                .withDatabase(
                        db -> {
                            db.boot.run(
                                    context -> {
                                        var f = fixture(context, time, Set.of(account, other));
                                        f.invoices.billingProfile(
                                                account, buyer("Originaler Rechnungskunde"));
                                        var order = purchase(f, time, account);
                                        id.set(f.invoices.synchronize(order));
                                        assertEquals(id.get(), f.invoices.synchronize(order));
                                        assertTrue(f.invoices.renderOne());
                                        bytes.set(f.invoices.download(account, id.get()));
                                        assertEquals(
                                                InvoiceWorkStatus.READY,
                                                f.invoices.invoice(account, id.get()).status());
                                        assertEquals(
                                                InvoiceFailure.Code.ACCESS_DENIED,
                                                assertThrows(
                                                                InvoiceFailure.class,
                                                                () ->
                                                                        f.invoices.download(
                                                                                other, id.get()))
                                                        .code());
                                        f.invoices.billingProfile(
                                                account, buyer("Geänderter Rechnungskunde"));
                                        assertEquals(
                                                "Originaler Rechnungskunde",
                                                f.invoices
                                                        .invoice(account, id.get())
                                                        .content()
                                                        .buyer()
                                                        .party()
                                                        .name()
                                                        .text());
                                    });
                            db.boot.run(
                                    context -> {
                                        var f = fixture(context, time, Set.of(account, other));
                                        assertEquals(
                                                bytes.get(),
                                                f.invoices.download(account, id.get()));
                                        assertEquals(
                                                1,
                                                f.invoices
                                                        .invoices(account, Optional.empty(), 100)
                                                        .size());
                                        assertFalse(f.invoices.renderOne());
                                        assertEquals(
                                                1L,
                                                f.jdbc.queryForObject(
                                                        "SELECT count(*) FROM"
                                                            + " \"org.vernac.saas.invoicing\".\"IssuedInvoiceDocument\"",
                                                        Map.of(),
                                                        Long.class));
                                    });
                            assertEquals(1, db.migrationCount());
                        });
    }

    @Test
    void concurrentInstancesAllocateOneSourceOnceAndUniqueSequentialNumbersAcrossAccounts()
            throws Exception {
        var a = AccountId.create();
        var b = AccountId.create();
        var time = new Time();
        new InvoiceTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        first ->
                                                db.boot.run(
                                                        second -> {
                                                            var one =
                                                                    fixture(
                                                                            first,
                                                                            time,
                                                                            Set.of(a, b));
                                                            var two =
                                                                    fixture(
                                                                            second,
                                                                            time,
                                                                            Set.of(a, b));
                                                            one.invoices.billingProfile(
                                                                    a, buyer("Kunde A"));
                                                            one.invoices.billingProfile(
                                                                    b, buyer("Kunde B"));
                                                            var orderA = purchase(one, time, a);
                                                            var orderB = purchase(one, time, b);
                                                            var barrier = new CyclicBarrier(3);
                                                            var pool =
                                                                    Executors.newFixedThreadPool(3);
                                                            try {
                                                                var x =
                                                                        pool.submit(
                                                                                () -> {
                                                                                    barrier.await();
                                                                                    return one
                                                                                            .invoices
                                                                                            .synchronize(
                                                                                                    orderA);
                                                                                });
                                                                var y =
                                                                        pool.submit(
                                                                                () -> {
                                                                                    barrier.await();
                                                                                    return two
                                                                                            .invoices
                                                                                            .synchronize(
                                                                                                    orderA);
                                                                                });
                                                                var z =
                                                                        pool.submit(
                                                                                () -> {
                                                                                    barrier.await();
                                                                                    return two
                                                                                            .invoices
                                                                                            .synchronize(
                                                                                                    orderB);
                                                                                });
                                                                assertEquals(
                                                                        x.get(15, TimeUnit.SECONDS),
                                                                        y.get(
                                                                                15,
                                                                                TimeUnit.SECONDS));
                                                                assertNotEquals(
                                                                        x.get(),
                                                                        z.get(
                                                                                15,
                                                                                TimeUnit.SECONDS));
                                                                var numbers =
                                                                        one.jdbc.queryForList(
                                                                                "SELECT \"number\""
                                                                                    + " FROM"
                                                                                    + " \"org.vernac.saas.invoicing\".\"Invoice\""
                                                                                    + " ORDER BY"
                                                                                    + " \"number\"",
                                                                                Map.of(),
                                                                                String.class);
                                                                assertEquals(
                                                                        List.of(
                                                                                "TEST-RE-2026-00000001",
                                                                                "TEST-RE-2026-00000002"),
                                                                        numbers);
                                                            } finally {
                                                                pool.shutdownNow();
                                                            }
                                                        })));
    }

    @Test
    void verifiedPartialAndFullRefundsCreateImmutableReferencedCreditsWithExactTotals()
            throws Exception {
        var account = AccountId.create();
        var time = new Time();
        new InvoiceTestEnvironment(root)
                .withDatabase(
                        db ->
                                db.boot.run(
                                        context -> {
                                            var f = fixture(context, time, Set.of(account));
                                            f.invoices.billingProfile(
                                                    account, buyer("Rechnungskunde"));
                                            var order = purchase(f, time, account);
                                            var original = f.invoices.synchronize(order);
                                            assertTrue(f.invoices.renderOne());
                                            var unchanged = f.invoices.download(account, original);
                                            f.payments.refund(
                                                    account,
                                                    order,
                                                    PaymentOperationId.create(),
                                                    MinorUnits.of(200));
                                            assertTrue(f.payments.processRefundOne());
                                            assertTrue(f.payments.processRefundOne());
                                            f.invoices.synchronize(order);
                                            assertTrue(f.invoices.renderOne());
                                            f.payments.refund(
                                                    account,
                                                    order,
                                                    PaymentOperationId.create(),
                                                    MinorUnits.of(1000));
                                            assertTrue(f.payments.processRefundOne());
                                            assertTrue(f.payments.processRefundOne());
                                            f.invoices.synchronize(order);
                                            assertTrue(f.invoices.renderOne());
                                            f.invoices.synchronize(order);
                                            var all =
                                                    f.invoices.invoices(
                                                            account, Optional.empty(), 100);
                                            assertEquals(3, all.size());
                                            assertEquals(
                                                    unchanged,
                                                    f.invoices.download(account, original));
                                            var credits =
                                                    all.stream()
                                                            .filter(
                                                                    v ->
                                                                            v.content().kind()
                                                                                    == InvoiceKind
                                                                                            .CORRECTION)
                                                            .toList();
                                            assertEquals(
                                                    1200,
                                                    credits.stream()
                                                            .mapToLong(
                                                                    v ->
                                                                            v.content()
                                                                                    .amounts()
                                                                                    .gross()
                                                                                    .value())
                                                            .sum());
                                            assertEquals(
                                                    1008,
                                                    credits.stream()
                                                            .mapToLong(
                                                                    v ->
                                                                            v.content()
                                                                                    .amounts()
                                                                                    .net()
                                                                                    .value())
                                                            .sum());
                                            assertEquals(
                                                    192,
                                                    credits.stream()
                                                            .mapToLong(
                                                                    v ->
                                                                            v.content()
                                                                                    .amounts()
                                                                                    .tax()
                                                                                    .value())
                                                            .sum());
                                            for (var credit : credits) {
                                                assertEquals(
                                                        f.invoices
                                                                .invoice(account, original)
                                                                .content()
                                                                .number(),
                                                        credit.content()
                                                                .original()
                                                                .orElseThrow()
                                                                .number());
                                                assertEquals(
                                                        InvoiceWorkStatus.READY, credit.status());
                                            }
                                        }));
    }
}
