// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.billing.web;
import java.util.Objects;
import org.vernac.saas.invoicing.domain.*;
/** Product-owned invoice numbering series and service description. */
public record BillingInvoicePolicy(InvoiceSeries series, InvoiceText description) {
    public BillingInvoicePolicy { Objects.requireNonNull(series); Objects.requireNonNull(description); }
}
