// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.payments.local;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.vernac.saas.payments.*;
import org.vernac.saas.payments.domain.*;
import org.vernac.saas.subscriptions.domain.*;
@EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL", matches=".+")
class LocalPaymentsPostgresIT {
    @TempDir Path root;
    static final PaymentUri URL = PaymentUri.of("https://local.example.test/payment");
    static CheckoutRequest request(PaymentOperationId operation, RemoteCustomerId customer) { return CheckoutRequest.of(operation, customer, MinorUnits.of(1200), BillingCurrency.EUR, URL, URL); }
    @Test void normalGeneratedBindingStartsWithoutKeysAndPersistsFirstMandateRenewalAndIdempotentRefund() throws Exception {
        var operation = PaymentOperationId.create(); var customer = new AtomicReference<RemoteCustomerId>(); var payment = new AtomicReference<ProviderPayment>();
        new LocalPaymentTestEnvironment(root).withDatabase(db -> {
            db.boot.run(context -> {
                assertNull(context.getStartupFailure()); var provider = context.getBean(PaymentProvider.class);
                assertEquals(PaymentProviderMode.SIMULATED, provider.mode()); customer.set(provider.createCustomer(PaymentOperationId.create()));
                payment.set(provider.checkout(request(operation, customer.get()))); assertEquals(PaymentState.OPEN, payment.get().state());
                assertEquals(payment.get(), provider.checkout(request(operation, customer.get())));
                assertThrows(PaymentFailure.class, () -> provider.charge(RecurringChargeRequest.of(PaymentOperationId.create(), customer.get(), RemoteMandateId.of("localm_wrong"), MinorUnits.of(1200), BillingCurrency.EUR, URL)));
                var hint = provider.notification(PaymentNoticeBody.of("id=" + payment.get().id().encoded()), PaymentSignature.of(""));
                payment.set(provider.inspect(hint)); assertEquals(PaymentState.PAID, payment.get().state()); assertTrue(payment.get().mandate().isPresent());
            });
            db.boot.run(context -> {
                assertNull(context.getStartupFailure()); var provider = context.getBean(PaymentProvider.class);
                assertEquals(payment.get(), provider.inspect(payment.get().id()));
                var renewed = provider.charge(RecurringChargeRequest.of(PaymentOperationId.create(), customer.get(), payment.get().mandate().orElseThrow(), MinorUnits.of(1200), BillingCurrency.EUR, URL));
                assertEquals(PaymentState.PAID, provider.inspect(renewed.id()).state());
                var refund = RefundRequest.of(PaymentOperationId.create(), payment.get().id(), MinorUnits.of(200), BillingCurrency.EUR);
                assertEquals(provider.refund(refund), provider.refund(refund));
                assertEquals(200, provider.inspect(payment.get().id()).refunded().value());
                assertThrows(PaymentFailure.class, () -> provider.refund(RefundRequest.of(PaymentOperationId.create(), payment.get().id(), MinorUnits.of(1200), BillingCurrency.EUR)));
                assertEquals(200, provider.inspect(payment.get().id()).refunded().value());
            });
            assertEquals(1, db.migrationCount());
        });
    }
    @Test void paidResponseLossSurvivesRestartWithoutCreatingAnotherPayment() throws Exception {
        var payment = new AtomicReference<ProviderPayment>();
        new LocalPaymentTestEnvironment(root).withDatabase(db -> {
            db.boot.run(context -> {
                assertNull(context.getStartupFailure());
                var provider = new LocalPayments(context.getBean(NamedParameterJdbcTemplate.class), context.getBean(PlatformTransactionManager.class), List.of(LocalPayments.Step.TIMEOUT_PAID), Clock.systemUTC());
                var customer = provider.createCustomer(PaymentOperationId.create()); payment.set(provider.checkout(request(PaymentOperationId.create(), customer)));
                assertEquals(PaymentFailure.Code.OUTCOME_UNKNOWN, assertThrows(PaymentFailure.class, () -> provider.inspect(payment.get().id())).code());
            });
            db.boot.run(context -> { assertNull(context.getStartupFailure()); assertEquals(PaymentState.PAID, context.getBean(PaymentProvider.class).inspect(payment.get().id()).state()); });
        });
    }
    @Test void explicitSimulationOptInIsRequiredAndReusedOperationsCannotChangeAmount() throws Exception {
        new LocalPaymentTestEnvironment(root).withDatabase(db -> {
            db.boot.withPropertyValues("vernac.payments.local.allowed=false").run(context -> assertNotNull(context.getStartupFailure()));
            db.boot.run(context -> {
                assertNull(context.getStartupFailure()); var provider = context.getBean(PaymentProvider.class);
                var operation = PaymentOperationId.create(); var customer = provider.createCustomer(PaymentOperationId.create()); provider.checkout(request(operation, customer));
                assertEquals(PaymentFailure.Code.OPERATION_CONFLICT, assertThrows(PaymentFailure.class,
                        () -> provider.checkout(CheckoutRequest.of(operation, customer, MinorUnits.of(1300), BillingCurrency.EUR, URL, URL))).code());
            });
        });
    }
}
