// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.billing.web;

import org.springframework.context.annotation.*;

/** Explicit opt-in. Application supplies the ordinary ledgers, authority and UI settings. */
@Configuration(proxyBeanMethods = false)
public class BillingMvcConfiguration {
    @Bean
    BillingPages billingPages(BillingSettings settings) {
        return new BillingPages(settings);
    }

    @Bean
    BillingController billingController(BillingSelfService service, BillingPages pages) {
        return new BillingController(service, pages);
    }
}
