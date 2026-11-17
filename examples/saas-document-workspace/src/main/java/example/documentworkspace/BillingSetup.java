// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package example.documentworkspace;
import example.documentworkspace.commercial.domain.WorkspaceOffer;
import example.documentworkspace.commercial.domain.WorkspaceOffers;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.vernac.saas.billing.web.*;
import org.vernac.saas.invoicing.domain.*;
import org.vernac.saas.mail.domain.*;
import org.vernac.saas.payments.domain.*;
import org.vernac.saas.payments.persistence.domain.*;
import org.vernac.saas.subscriptions.*;
import org.vernac.saas.subscriptions.domain.*;

@Configuration(proxyBeanMethods = false)
class BillingSetup {
    @Bean
    PlanCatalog plans(WorkspaceOffers offers) {
        var free = offer(offers.entryPlan());
        var pro = offer(offers.paidPlan());
        return new PlanCatalog(List.of(free, pro), PlanCatalog.id(free));
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
    private static OfferTerms offer(WorkspaceOffer offer) {
        return OfferTerms.of(
                PlanKey.of(offer.plan()),
                OfferRevision.of("v1"),
                MinorUnits.of(offer.monthlyPrice()),
                BillingCurrency.EUR,
                BillingInterval.MONTHLY,
                Benefits.of(
                        Map.of(
                                BenefitKey.of("documents"),
                                Allowance.of(offer.documents()),
                                BenefitKey.of("document-characters"),
                                Allowance.of(offer.characters()),
                                BenefitKey.of("monthly-exports"),
                                Allowance.of(offer.exports()))));
    }

    @Bean
    org.vernac.saas.billing.web.BillingInvoicePolicy invoicePolicy() {
        return new org.vernac.saas.billing.web.BillingInvoicePolicy(InvoiceSeries.of("DOC"), InvoiceText.of("Document Workspace Abonnement"));
    }
}
