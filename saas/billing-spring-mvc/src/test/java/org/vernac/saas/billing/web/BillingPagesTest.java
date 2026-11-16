// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.billing.web;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.vernac.saas.identity.domain.AccountId;
import org.vernac.saas.invoicing.domain.*;
import org.vernac.saas.mail.domain.MailAddress;
import org.vernac.saas.payments.domain.PaymentProviderEnvironment;
import org.vernac.saas.payments.persistence.domain.PaymentProfileKey;

import java.util.*;

class BillingPagesTest {
    private final BillingPages pages =
            new BillingPages(
                    new BillingSettings(
                            "Demo <script>",
                            "/workspace",
                            List.of(
                                    new BillingSettings.PaymentChoice(
                                            PaymentProfileKey.of("local-v1"),
                                            "Local",
                                            PaymentProviderEnvironment.TEST))));

    @Test
    void untrustedPartyAndCsrfValuesRemainTextAndFreeRequiresExplicitPost() {
        var buyer =
                InvoiceBuyer.of(
                        InvoiceParty.of(
                                InvoiceText.of("<script>alert('party')</script>"),
                                InvoiceText.of("Street 1"),
                                InvoicePostalCode.of("28199"),
                                InvoiceText.of("Bremen")),
                        MailAddress.of("owner@example.test"),
                        CustomerNumber.of("C-1"));
        var model =
                new BillingSelfService.AccountPage(
                        AccountId.create(),
                        Optional.empty(),
                        List.of(),
                        Optional.of(buyer),
                        List.of(),
                        List.of());
        String html =
                pages.account(model, "/demo", new DefaultCsrfToken("X-CSRF", "_csrf", "token<&\""));
        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("name=\"_csrf\" value=\"token&lt;&amp;&quot;\""));
        assertTrue(html.contains("method=\"post\" action=\"/demo/billing/start\""));
        assertFalse(html.contains("action=\"/demo/billing/checkout\""));
        assertFalse(html.contains("localStorage"));
        assertFalse(html.contains("javascript:"));
    }

    @Test
    void failureTextCannotBecomeMarkup() {
        String html = pages.failure("<img src=x onerror=alert(1)>");
        assertFalse(html.contains("<img"));
        assertTrue(html.contains("&lt;img"));
    }
}
