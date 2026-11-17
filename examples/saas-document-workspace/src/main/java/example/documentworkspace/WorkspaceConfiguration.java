// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package example.documentworkspace;
import example.documentworkspace.commercial.domain.WorkspaceOffer;
import example.documentworkspace.commercial.domain.WorkspaceOffers;
import java.util.*;
import org.springframework.context.annotation.*;
import example.documentworkspace.authorization.WorkspacePermissions;
import example.documentworkspace.domain.WorkspaceMembership;
import org.vernac.saas.billing.web.*;
import org.vernac.saas.subscriptions.*;
import org.vernac.saas.subscriptions.domain.*;

@Configuration(proxyBeanMethods = false)
@Import(AccountWideBillingConfiguration.class)
class WorkspaceConfiguration {
    @Bean
    PlanCatalog plans(WorkspaceOffers offers) {
        var free = offer(offers.entryPlan());
        var pro = offer(offers.paidPlan());
        return new PlanCatalog(List.of(free, pro), PlanCatalog.id(free));
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
    AccountWideBillingConfiguration.Access workspaceAccess(WorkspaceMembership membership) {
        return new AccountWideBillingConfiguration.Access(List.of(WorkspacePermissions.values()),
                List.of(membership.initialRole()), Set.of("/documents/create", "/documents/delete", "/documents/export"));
    }
}
