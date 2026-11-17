// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.billing.web;

import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.vernac.runtime.application.AdapterBindings;
import org.vernac.saas.billing.web.*;
import org.vernac.saas.identity.persistence.IdentityAccounts;
import org.vernac.saas.invoicing.*;
import org.vernac.saas.invoicing.domain.*;
import org.vernac.saas.mail.domain.*;
import org.vernac.saas.payments.domain.*;
import org.vernac.saas.payments.persistence.*;
import org.vernac.saas.payments.persistence.domain.*;
import org.vernac.saas.restrictions.RestrictionLedger;
import org.vernac.saas.subscriptions.*;
import org.vernac.saas.subscriptions.domain.*;

import java.net.URI;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@org.springframework.boot.autoconfigure.AutoConfiguration(after=org.vernac.saas.identity.persistence.IdentitySaasAutoConfiguration.class)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="vernac.billing.enabled",havingValue="true")
@Import({BillingMvcConfiguration.class, PaymentNotices.class, MerchantOperations.class})
public class BillingAutoConfiguration {
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    org.vernac.saas.billing.lifecycle.BillingAccountLifecycle billingAccountLifecycle(NamedParameterJdbcTemplate jdbc, Clock clock) {
        return new org.vernac.saas.billing.lifecycle.BillingAccountLifecycle(jdbc,clock);
    }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    @DependsOn(AdapterBindings.INITIALIZATION)
    SubscriptionLedger subscriptions(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager writer,
            PlanCatalog plans,
            Clock clock) {
        return new SubscriptionLedger(jdbc, writer, plans, clock, new PaymentLifecyclePolicy(jdbc));
    }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    SubscriptionAccounts subscriptionAccounts(
            IdentityAccounts accounts, SubscriptionLedger subscriptions) {
        return new SubscriptionAccounts(accounts, subscriptions);
    }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    BillingAccess billingAccess(IdentityAccounts accounts) {
        return new BillingAccess(accounts);
    }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    @DependsOn(AdapterBindings.INITIALIZATION)
    RestrictionLedger restrictions(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager writer,
            SubscriptionLedger subscriptions,
            Clock clock) {
        return new RestrictionLedger(
                jdbc,
                writer,
                (account, benefit) -> Benefits.allowance(subscriptions.benefits(account), benefit),
                clock);
    }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    @DependsOn(AdapterBindings.INITIALIZATION)
    PaymentFlow payments(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager writer,
            PaymentProvider provider,
            PlanCatalog plans,
            SubscriptionLedger subscriptions,
            BillingAccess access,
            Environment e,
            Clock clock,
            org.vernac.saas.billing.lifecycle.BillingAccountLifecycle lifecycle, PaymentFlow.RefundAuthority refundAuthority) {
        if (provider.mode() == PaymentProviderMode.SIMULATED
                && !e.getProperty("billing.allow-simulation", Boolean.class, false))
            throw new IllegalStateException("Simulated payments require explicit opt-in");
        var profile = PaymentProfileKey.of(e.getRequiredProperty("billing.profile"));
        // Admission is performed by the stateless ingress chain before body/session parsing.
        return new PaymentFlow(
                jdbc,
                writer,
                Map.of(profile, provider),
                plans,
                subscriptions,
                access::requireOwner,
                ignored -> {
                    if (!ignored.equals(profile))
                        throw new PaymentProcessingFailure(PaymentProcessingFailure.Code.ACCESS_DENIED);
                },
                clock,
                refundAuthority,
                lifecycle);
    }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    @DependsOn(AdapterBindings.INITIALIZATION)
    InvoiceLedger invoices(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager writer,
            PaymentFlow payments,
            InvoiceSeller seller,
            InvoiceRenderer renderer,
            IdentityAccounts accounts,
            Clock clock, BillingInvoicePolicy policy) {
        return new InvoiceLedger(
                jdbc,
                writer,
                payments,
                seller,
                policy.series(),
                policy.description(),
                renderer,
                accounts::requireRetainedOwnership,
                clock);
    }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    InvoiceMail invoiceMail(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager writer,
            MailProvider provider,
            IdentityAccounts accounts,
            Environment e,
            Clock clock) {
        URI origin = origin(e);
        return new InvoiceMail(
                jdbc,
                writer,
                provider,
                id -> origin.resolve("/billing/invoices/" + id.asString()),
                accounts::requireRetainedOwnership,
                clock);
    }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    BillingSelfService billingSelfService(
            IdentityAccounts accounts,
            BillingAccess access,
            PlanCatalog catalog,
            SubscriptionLedger subscriptions,
            PaymentFlow payments,
            InvoiceLedger invoices,
            InvoiceMail mail,
            BillingSettings settings) {
        return new BillingSelfService(
                accounts, access, catalog, subscriptions, payments, invoices, mail, settings);
    }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    org.vernac.saas.billing.lifecycle.BillingMaintenance billingMaintenance(PaymentFlow payments, InvoiceLedger invoices, InvoiceMail mail, Environment e) {
        return new org.vernac.saas.billing.lifecycle.BillingMaintenance(payments,invoices,mail,
                PaymentUri.of(origin(e).resolve("/billing").toString()),PaymentUri.of(origin(e).resolve(PaymentNotices.PATH).toString()));
    }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(name="billingWorker")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="billing.worker.enabled",havingValue="true",matchIfMissing=true)
    SmartLifecycle billingWorker(
            org.vernac.saas.billing.lifecycle.BillingMaintenance maintenance) {
        return new SmartLifecycle() {
            final AtomicBoolean running = new AtomicBoolean();
            ScheduledExecutorService executor;

            public synchronized void start() {
                if (!running.compareAndSet(false, true)) return;
                executor =
                        Executors.newSingleThreadScheduledExecutor(
                                r -> {
                                    var t = new Thread(r, "vernac-billing");
                                    t.setDaemon(true);
                                    return t;
                                });
                executor.scheduleWithFixedDelay(
                        () -> {
                            if (!running.get()) return;
                            maintenance.tick();
                        },
                        1,
                        1,
                        TimeUnit.SECONDS);
            }

            public synchronized void stop() {
                running.set(false);
                if (executor != null) {
                    executor.shutdownNow();
                    try {
                        executor.awaitTermination(20, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            public boolean isRunning() {
                return running.get();
            }
        };
    }
    static URI origin(Environment e) {
        var origin = URI.create(e.getRequiredProperty("identity.origin"));
        if (!"https".equals(origin.getScheme())
                || origin.getHost() == null
                || origin.getRawUserInfo() != null
                || origin.getRawQuery() != null
                || origin.getRawFragment() != null
                || !(origin.getPath().isEmpty() || origin.getPath().equals("/")))
            throw new IllegalStateException("HTTPS origin required");
        return origin;
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    PaymentFlow.RefundAuthority merchantRefundAuthority() { return MerchantAccess::requireAuthorized; }
}
