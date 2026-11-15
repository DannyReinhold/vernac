// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.invoicing;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.vernac.saas.invoicing.domain.*;
import org.vernac.saas.subscriptions.domain.MinorUnits;

import java.util.Base64;

class DomesticTaxTest {
    @Test
    void grossPricesAndExplicitExemptionBalanceInCents() {
        var standard = DomesticTax.gross(MinorUnits.of(1200), InvoiceTaxTreatment.STANDARD_19);
        assertEquals(1008, standard.net().value());
        assertEquals(192, standard.tax().value());
        var exempt =
                DomesticTax.gross(MinorUnits.of(1200), InvoiceTaxTreatment.SMALL_BUSINESS_EXEMPT);
        assertEquals(1200, exempt.net().value());
        assertEquals(0, exempt.tax().value());
        assertThrows(
                org.vernac.runtime.DomainValidationException.class,
                () -> InvoiceAmounts.of(MinorUnits.of(100), MinorUnits.of(19), MinorUnits.of(120)));
    }

    @Test
    void everyPartitionOfRefundsReversesOriginalNetAndTaxExactly() {
        for (var treatment : InvoiceTaxTreatment.values()) {
            var original = DomesticTax.gross(MinorUnits.of(1200), treatment);
            long net = 0, tax = 0;
            for (int n = 1; n <= 1200; n++) {
                var credit = DomesticTax.credit(original, MinorUnits.of(n - 1), MinorUnits.of(n));
                net += credit.net().value();
                tax += credit.tax().value();
                assertEquals(1, credit.gross().value());
            }
            assertEquals(original.net().value(), net);
            assertEquals(original.tax().value(), tax);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> DomesticTax.credit(original, MinorUnits.of(1200), MinorUnits.of(1201)));
        }
    }

    @Test
    void boundedDocumentsRejectExternalEntitiesAndDoNotExposePartiesOrPayloadsInDiagnostics() {
        assertFalse(
                InvoiceRules.xml(
                        "<!DOCTYPE r [<!ENTITY secret SYSTEM"
                            + " 'file:///etc/passwd'>]><rsm:CrossIndustryInvoice"
                            + " xmlns:rsm='urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100'>&secret;</rsm:CrossIndustryInvoice>"));
        assertFalse(InvoiceRules.xml("<invoice/>"));
        assertFalse(InvoiceRules.text("Name\nInjected"));
        assertFalse(InvoiceRules.pdf(Base64.getEncoder().encodeToString("not a PDF".getBytes())));
        assertFalse(InvoiceText.of("Vertraulicher Kundenname").toString().contains("Kundenname"));
    }
}
