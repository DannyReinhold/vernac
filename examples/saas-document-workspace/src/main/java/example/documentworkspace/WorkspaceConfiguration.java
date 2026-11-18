// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package example.documentworkspace;
import example.documentworkspace.commercial.domain.WorkspaceOffer;
import example.documentworkspace.commercial.domain.WorkspaceOffers;
import java.util.*;
import org.springframework.context.annotation.*;
import example.documentworkspace.web.adapter.inbound.http.WorkspacePages;
import example.documentworkspace.authorization.WorkspacePermissions;
import example.documentworkspace.domain.WorkspaceMembership;
import org.vernac.saas.billing.web.*;
import org.vernac.saas.subscriptions.*;
import org.vernac.saas.subscriptions.domain.*;

@Configuration(proxyBeanMethods = false)
@Import(AccountWideBillingConfiguration.class)
class WorkspaceConfiguration {
    @Bean
    @org.springframework.core.annotation.Order(0)
    org.springframework.security.web.SecurityFilterChain publicPages(
            org.springframework.security.config.annotation.web.builders.HttpSecurity http) throws Exception {
        return http.securityMatcher("/", "/index.html", "/impressum.html")
                .authorizeHttpRequests(routes -> routes
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/", "/index.html", "/impressum.html").permitAll()
                        .anyRequest().denyAll())
                .build();
    }

    @Bean
    PlanCatalog plans(WorkspaceOffers offers) {
        var free = offer(offers.entryPlan());
        var pro = offer(offers.paidPlan());
        return new PlanCatalog(List.of(free, pro), PlanCatalog.id(free));
    }
    private static OfferTerms offer(WorkspaceOffer offer) {
        var benefits = offer.benefits().stream().collect(java.util.stream.Collectors.toMap(
                included -> BenefitKey.of(included.benefit().key()), included -> Allowance.of(included.maximum())));
        return OfferTerms.of(PlanKey.of(offer.plan()), OfferRevision.of(offer.revision()),
                MinorUnits.of(offer.price().amount()), BillingCurrency.valueOf(offer.currency()),
                BillingInterval.valueOf(offer.interval()), Benefits.of(benefits));
    }

    @Bean
    AccountWideBillingConfiguration.Access workspaceAccess(WorkspaceMembership membership) {
        return new AccountWideBillingConfiguration.Access(List.of(WorkspacePermissions.values()),
                List.of(membership.initialRole()), WorkspacePages.class);
    }
}
