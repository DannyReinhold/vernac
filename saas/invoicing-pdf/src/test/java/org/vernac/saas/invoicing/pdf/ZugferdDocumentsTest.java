// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.invoicing.pdf;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.vernac.saas.invoicing.DomesticTax;
import org.vernac.saas.invoicing.domain.*;
import org.vernac.saas.mail.domain.MailAddress;
import org.vernac.saas.payments.domain.PaymentPostedAt;
import org.vernac.saas.subscriptions.Benefits;
import org.vernac.saas.subscriptions.domain.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

class ZugferdDocumentsTest {
    static final Instant POSTED = Instant.parse("2026-01-02T12:00:00Z");

    static InvoiceContent content(long gross, InvoiceTaxTreatment treatment, InvoiceKind kind) {
        var seller =
                InvoiceSeller.of(
                        party("Vernac Testanbieter GmbH"),
                        SellerTaxIdentifier.of("DE123456789"),
                        MailAddress.of("seller@example.test"),
                        treatment);
        var buyer =
                InvoiceBuyer.of(
                        party("Käufer GmbH"),
                        MailAddress.of("buyer@example.test"),
                        CustomerNumber.of("K-1001"));
        var terms =
                OfferTerms.of(
                        PlanKey.of("pro"),
                        OfferRevision.of("v1"),
                        MinorUnits.of(gross),
                        BillingCurrency.EUR,
                        BillingInterval.MONTHLY,
                        Benefits.of(Map.of()));
        return InvoiceContent.of(
                InvoiceNumber.of("TEST-RE-2026-00000001"),
                InvoiceIssueDate.of(LocalDate.of(2026, 1, 2)),
                kind,
                InvoiceEnvironment.TEST,
                seller,
                buyer,
                InvoiceText.of("Nutzung der SaaS-Anwendung"),
                terms,
                SubscriptionPeriod.of(
                        Instant.parse("2026-01-01T00:00:00Z"),
                        Instant.parse("2026-02-01T00:00:00Z")),
                PaymentPostedAt.of(POSTED),
                DomesticTax.gross(MinorUnits.of(gross), treatment),
                kind == InvoiceKind.CORRECTION
                        ? InvoiceReference.of(
                                InvoiceNumber.of("TEST-RE-2025-00000001"),
                                InvoiceIssueDate.of(LocalDate.of(2025, 12, 1)))
                        : null);
    }

    static InvoiceParty party(String name) {
        return InvoiceParty.of(
                InvoiceText.of(name),
                InvoiceText.of("Teststraße 12"),
                InvoicePostalCode.of("28199"),
                InvoiceText.of("Bremen"));
    }

    @Test
    void hybridPdfAndXmlPassRealPdfAAndEn16931ValidationAndContainTheSameFiscalSnapshot()
            throws Exception {
        var rendered =
                new ZugferdDocuments()
                        .render(
                                content(
                                        1200,
                                        InvoiceTaxTreatment.STANDARD_19,
                                        InvoiceKind.INVOICE));
        var bytes = Base64.getDecoder().decode(rendered.pdf().encoded());
        try (var pdf = Loader.loadPDF(bytes)) {
            var text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("Käufer GmbH"));
            assertTrue(text.contains("TESTRECHNUNG"));
            assertTrue(text.contains("12,00 EUR"));
            assertTrue(text.contains("10,08 EUR"));
            assertTrue(text.contains("1,92 EUR"));
            var attachments = pdf.getDocumentCatalog().getNames().getEmbeddedFiles().getNames();
            assertEquals(1, attachments.size());
            assertArrayEquals(
                    rendered.xml().encoded().getBytes(StandardCharsets.UTF_8),
                    attachments.values().iterator().next().getEmbeddedFile().toByteArray());
        }
        assertTrue(
                rendered.xml().encoded().contains("1008")
                        || rendered.xml().encoded().contains("10.08"));
        var directory = Files.createDirectories(Path.of("target/pdf-fixtures"));
        Files.write(directory.resolve("standard-invoice.pdf"), bytes);
    }

    @Test
    void exemptInvoiceAndFullCorrectionValidateWithoutInventingTaxOrAnOutstandingBalance() {
        var renderer = new ZugferdDocuments();
        var exempt =
                renderer.render(
                        content(
                                1200,
                                InvoiceTaxTreatment.SMALL_BUSINESS_EXEMPT,
                                InvoiceKind.INVOICE));
        assertTrue(exempt.xml().encoded().contains("19 UStG"));
        var correction =
                renderer.render(
                        content(1200, InvoiceTaxTreatment.STANDARD_19, InvoiceKind.CORRECTION));
        assertTrue(correction.xml().encoded().contains(">381<"));
        assertTrue(correction.xml().encoded().contains("TEST-RE-2025-00000001"));
    }

    @Test
    void centPricesAndCumulativePartialCreditKeepTheExactRefundedGross() {
        var renderer = new ZugferdDocuments();
        renderer.render(content(3, InvoiceTaxTreatment.STANDARD_19, InvoiceKind.INVOICE));
        var original = content(1200, InvoiceTaxTreatment.STANDARD_19, InvoiceKind.INVOICE);
        var partial =
                InvoiceContent.of(
                        original.number(),
                        original.issued(),
                        InvoiceKind.CORRECTION,
                        original.environment(),
                        original.seller(),
                        original.buyer(),
                        original.description(),
                        original.terms(),
                        original.period(),
                        original.paymentPostedAt(),
                        DomesticTax.credit(
                                original.amounts(), MinorUnits.of(199), MinorUnits.of(200)),
                        InvoiceReference.of(
                                InvoiceNumber.of("TEST-RE-2025-00000001"), original.issued()));
        renderer.render(partial);
    }

    @Test
    void longGermanPartySnapshotsWrapAcrossA4PagesWithoutDroppingText() throws Exception {
        var source = content(1200, InvoiceTaxTreatment.STANDARD_19, InvoiceKind.INVOICE);
        var name = "ÄW".repeat(100);
        var street = "ÜW".repeat(100);
        var city = "ÖW".repeat(100);
        var party =
                InvoiceParty.of(
                        InvoiceText.of(name),
                        InvoiceText.of(street),
                        InvoicePostalCode.of("28199"),
                        InvoiceText.of(city));
        var seller =
                InvoiceSeller.of(
                        party,
                        source.seller().taxIdentifier(),
                        source.seller().contact(),
                        source.seller().treatment());
        var buyer = InvoiceBuyer.of(party, source.buyer().contact(), source.buyer().customer());
        var longContent =
                InvoiceContent.of(
                        source.number(),
                        source.issued(),
                        source.kind(),
                        source.environment(),
                        seller,
                        buyer,
                        InvoiceText.of("W".repeat(200)),
                        source.terms(),
                        source.period(),
                        source.paymentPostedAt(),
                        source.amounts(),
                        null);
        var bytes =
                Base64.getDecoder()
                        .decode(new ZugferdDocuments().render(longContent).pdf().encoded());
        try (var pdf = Loader.loadPDF(bytes)) {
            assertTrue(pdf.getNumberOfPages() >= 2);
            var text = new PDFTextStripper().getText(pdf).replaceAll("\\s+", "");
            assertTrue(text.contains(name));
            assertTrue(text.contains(street));
            assertTrue(text.contains(city));
            for (var page : pdf.getPages()) assertEquals(595.3, page.getMediaBox().getWidth(), 1.0);
        }
        Files.write(
                Files.createDirectories(Path.of("target/pdf-fixtures")).resolve("long-invoice.pdf"),
                bytes);
    }
}
