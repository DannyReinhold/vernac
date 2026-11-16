// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package example.documentworkspace;

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

@Configuration(proxyBeanMethods = false)
@Import(BillingMvcConfiguration.class)
class BillingSetup {
    @Bean
    PlanCatalog plans() {
        var free = offer("free", 0, 3, 1000, 2);
        var pro = offer("pro", 1200, 100, 10000, 100);
        return new PlanCatalog(List.of(free, pro), PlanCatalog.id(free));
    }

    private static OfferTerms offer(
            String plan, long price, long documents, long characters, long exports) {
        return OfferTerms.of(
                PlanKey.of(plan),
                OfferRevision.of("v1"),
                MinorUnits.of(price),
                BillingCurrency.EUR,
                BillingInterval.MONTHLY,
                Benefits.of(
                        Map.of(
                                BenefitKey.of("documents"),
                                Allowance.of(documents),
                                BenefitKey.of("document-characters"),
                                Allowance.of(characters),
                                BenefitKey.of("monthly-exports"),
                                Allowance.of(exports))));
    }

    @Bean
    @DependsOn(AdapterBindings.INITIALIZATION)
    SubscriptionLedger subscriptions(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager writer,
            PlanCatalog plans,
            Clock clock) {
        return new SubscriptionLedger(jdbc, writer, plans, clock, new PaymentLifecyclePolicy(jdbc));
    }

    @Bean
    SubscriptionAccounts subscriptionAccounts(
            IdentityAccounts accounts, SubscriptionLedger subscriptions) {
        return new SubscriptionAccounts(accounts, subscriptions);
    }

    @Bean
    BillingAccess billingAccess(IdentityAccounts accounts) {
        return new BillingAccess(accounts);
    }

    @Bean
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
    @DependsOn(AdapterBindings.INITIALIZATION)
    PaymentFlow payments(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager writer,
            PaymentProvider provider,
            PlanCatalog plans,
            SubscriptionLedger subscriptions,
            BillingAccess access,
            Environment e,
            Clock clock) {
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
                clock);
    }

    @Bean
    BillingSettings billingSettings(PaymentProvider provider, Environment e) {
        return new BillingSettings(
                "Document Workspace",
                "/workspace",
                List.of(
                        new BillingSettings.PaymentChoice(
                                PaymentProfileKey.of(e.getRequiredProperty("billing.profile")),
                                provider.kind().name(),
                                provider.environment())));
    }

    @Bean
    InvoiceSeller seller(Environment e) {
        return InvoiceSeller.of(
                InvoiceParty.of(
                        InvoiceText.of(e.getRequiredProperty("billing.seller.name")),
                        InvoiceText.of(e.getRequiredProperty("billing.seller.street")),
                        InvoicePostalCode.of(e.getRequiredProperty("billing.seller.postal-code")),
                        InvoiceText.of(e.getRequiredProperty("billing.seller.city"))),
                SellerTaxIdentifier.of(e.getRequiredProperty("billing.seller.tax-identifier")),
                MailAddress.of(e.getRequiredProperty("billing.seller.email")),
                InvoiceTaxTreatment.valueOf(e.getRequiredProperty("billing.seller.tax-treatment")));
    }

    @Bean
    @DependsOn(AdapterBindings.INITIALIZATION)
    InvoiceLedger invoices(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager writer,
            PaymentFlow payments,
            InvoiceSeller seller,
            InvoiceRenderer renderer,
            BillingAccess access,
            Clock clock) {
        return new InvoiceLedger(
                jdbc,
                writer,
                payments,
                seller,
                InvoiceSeries.of("DOC"),
                InvoiceText.of("Document Workspace Abonnement"),
                renderer,
                access::requireOwner,
                clock);
    }

    @Bean
    InvoiceMail invoiceMail(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager writer,
            MailProvider provider,
            BillingAccess access,
            Environment e,
            Clock clock) {
        URI origin = origin(e);
        return new InvoiceMail(
                jdbc,
                writer,
                provider,
                id -> origin.resolve("/billing/invoices/" + id.asString()),
                access::requireOwner,
                clock);
    }

    @Bean
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
    SmartLifecycle billingWorker(
            PaymentFlow payments, InvoiceLedger invoices, InvoiceMail mail, Environment e) {
        return new SmartLifecycle() {
            final AtomicBoolean running = new AtomicBoolean();
            ScheduledExecutorService executor;
            Optional<PaymentOrderId> after = Optional.empty();

            public synchronized void start() {
                if (!running.compareAndSet(false, true)) return;
                executor =
                        Executors.newSingleThreadScheduledExecutor(
                                r -> {
                                    var t = new Thread(r, "document-workspace-billing");
                                    t.setDaemon(true);
                                    return t;
                                });
                executor.scheduleWithFixedDelay(
                        () -> {
                            if (!running.get()) return;
                            try {
                                payments.scheduleRenewalOne();
                                payments.processOne(
                                        PaymentUri.of(origin(e).resolve("/billing").toString()),
                                        PaymentUri.of(
                                                origin(e)
                                                        .resolve(PaymentNotices.PATH)
                                                        .toString()));
                                payments.processRefundOne();
                                var rows = payments.verifiedPayments(after, 10);
                                for (var row : rows) {
                                    invoices.synchronize(row.order());
                                    after = Optional.of(row.order());
                                }
                                if (rows.size() < 10) after = Optional.empty();
                                invoices.renderOne();
                                mail.processOne();
                            } catch (RuntimeException unavailable) {
                                /* Persisted work remains recoverable; no untrusted exception/log payload. */
                            }
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
}
