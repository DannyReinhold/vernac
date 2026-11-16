// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.invoicing.pdf;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;

@AutoConfiguration
public class InvoicePdfAutoConfiguration {
    @Bean
    @Lazy
    @ConditionalOnMissingBean
    public ZugferdDocuments vernacInvoiceDocuments() {
        return new ZugferdDocuments();
    }
}
