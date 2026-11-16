// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.billing.web;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.vernac.saas.identity.security.*;

import java.util.*;

/** Opt-in composition with the existing Identity chain; no authentication or CSRF bypass. */
public final class BillingWebSecurity {
    private BillingWebSecurity() {}

    public static final Set<String> FORMS =
            Set.of(
                    "/billing/start",
                    "/billing/profile",
                    "/billing/checkout",
                    "/billing/subscription",
                    "/billing/payment/retry",
                    "/billing/payment/close",
                    "/billing/payment/reconcile",
                    "/billing/invoice/retry",
                    "/billing/mail/retry");

    public static SecurityFilterChain build(
            HttpSecurity http,
            AuthenticationManager manager,
            IdentitySessionTrust trust,
            IdentityLocalReturnTarget returns,
            IdentityRequestAdmission admission,
            IdentityInvitationActions journeys,
            IdentityAccountActions accounts,
            Set<String> productForms)
            throws Exception {
        var forms = new HashSet<>(FORMS);
        forms.addAll(productForms);
        return IdentityBrowserSecurity.build(
                http,
                manager,
                trust,
                returns,
                admission,
                journeys,
                accounts,
                new IdentityBrowserSecurity.Extensions(
                        request ->
                                request.getServletPath().equals("/billing")
                                        || request.getServletPath().startsWith("/billing/"),
                        forms));
    }
}
