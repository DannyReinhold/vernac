// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.billing.web;

import org.springframework.core.env.Environment;
import org.vernac.saas.invoicing.domain.*;
import org.vernac.saas.mail.domain.MailAddress;
import org.vernac.saas.payments.domain.PaymentProvider;
import org.vernac.saas.payments.persistence.domain.PaymentProfileKey;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.PlatformTransactionManager;
import org.vernac.runtime.authorization.*;
import org.vernac.saas.billing.web.*;
import org.vernac.saas.identity.persistence.*;
import org.vernac.saas.identity.roles.RoleCatalog;
import org.vernac.saas.identity.security.*;

@Configuration(proxyBeanMethods = false)
public class AccountWideBillingConfiguration {
    /** Explicit vocabulary for account-wide grants. Resource-bound checks always deny. */
    public record Access(List<PermissionKey> vocabulary, List<String> initialRoles, Class<?> browserPages) {
        public Access {
            vocabulary = List.copyOf(vocabulary);
            initialRoles = List.copyOf(initialRoles);
            Objects.requireNonNull(browserPages);
        }
    }

    @Bean
    AccountBrowserForms accountBrowserForms(Access access) {
        return new AccountBrowserForms(access.browserPages());
    }

    @Bean
    RoleCatalog roleCatalog(Access access) {
        return RoleCatalog.fromClasspath(
                getClass().getClassLoader(), "META-INF/vernac/roles.json", access.vocabulary());
    }
    @Bean
    IdentityAccounts identityAccounts(
            NamedParameterJdbcTemplate jdbc,
            @Qualifier("transactionManager") PlatformTransactionManager writer,
            Clock clock,
            IdentityJdbcAuthentication authentication,
            RoleCatalog catalog,
            Access access,
            org.vernac.saas.billing.lifecycle.BillingAccountLifecycle billingLifecycle) {
        var allowed = access.vocabulary().stream().map(PermissionKey::qualifiedName)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        PermissionEvaluator objects = request -> request.resource() == null
                && allowed.contains(request.permission().qualifiedName())
                ? PermissionDecision.ALLOW : PermissionDecision.DENY;
        return new IdentityAccounts(
                jdbc,
                writer,
                clock,
                authentication.sessionTrust(),
                catalog,
                access.initialRoles(),
                objects,
                billingLifecycle);
    }
    @Bean
    SecurityFilterChain identityBrowser(
            HttpSecurity http,
            IdentityJdbcAuthentication authentication,
            IdentityJourneys journeys,
            SubscriptionAccounts accounts, Access access, BillingSettings settings)
            throws Exception {
        return BillingWebSecurity.build(
                http,
                authentication.authenticationManager(),
                authentication.sessionTrust(),
                new IdentityLocalReturnTarget(settings.productPath()),
                authentication.requestAdmission(),
                journeys,
                accounts,
                AccountBrowserForms.postPaths(access.browserPages()));
    }

    @Bean
    BillingSettings billingSettings(PaymentProvider provider, Environment e) {
        return new BillingSettings(
                e.getRequiredProperty("billing.application-name"),
                e.getRequiredProperty("billing.product-path"),
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
    BillingInvoicePolicy invoicePolicy(Environment e) {
        return new BillingInvoicePolicy(InvoiceSeries.of(e.getRequiredProperty("billing.invoice-series")),
                InvoiceText.of(e.getRequiredProperty("billing.invoice-description")));
    }
}
