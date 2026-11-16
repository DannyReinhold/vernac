// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.invoicing;

import org.vernac.saas.invoicing.domain.*;
import org.vernac.saas.subscriptions.domain.MinorUnits;

import java.math.*;
import java.util.Objects;

/** Explicit gross-price policy for domestic EUR SaaS, rounded in cents with HALF_UP. */
public final class DomesticTax {
    private DomesticTax() {}

    public static InvoiceAmounts gross(MinorUnits gross, InvoiceTaxTreatment treatment) {
        Objects.requireNonNull(gross);
        Objects.requireNonNull(treatment);
        long net =
                treatment == InvoiceTaxTreatment.SMALL_BUSINESS_EXEMPT
                        ? gross.value()
                        : BigDecimal.valueOf(gross.value())
                                .divide(new BigDecimal("1.19"), 0, RoundingMode.HALF_UP)
                                .longValueExact();
        return InvoiceAmounts.of(MinorUnits.of(net), MinorUnits.of(gross.value() - net), gross);
    }

    /**
     * Cumulative attribution makes any partition of refunds exactly reverse the original totals.
     */
    public static InvoiceAmounts credit(
            InvoiceAmounts original, MinorUnits previouslyCredited, MinorUnits nowCredited) {
        Objects.requireNonNull(original);
        Objects.requireNonNull(previouslyCredited);
        Objects.requireNonNull(nowCredited);
        if (original.gross().value() <= 0
                || previouslyCredited.value() >= nowCredited.value()
                || nowCredited.value() > original.gross().value())
            throw new IllegalArgumentException("Invalid cumulative invoice correction");
        long before = attributableNet(original, previouslyCredited.value()),
                after = attributableNet(original, nowCredited.value());
        long gross = nowCredited.value() - previouslyCredited.value(), net = after - before;
        return InvoiceAmounts.of(
                MinorUnits.of(net), MinorUnits.of(gross - net), MinorUnits.of(gross));
    }

    private static long attributableNet(InvoiceAmounts original, long gross) {
        return BigDecimal.valueOf(original.net().value())
                .multiply(BigDecimal.valueOf(gross))
                .divide(BigDecimal.valueOf(original.gross().value()), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }
}
