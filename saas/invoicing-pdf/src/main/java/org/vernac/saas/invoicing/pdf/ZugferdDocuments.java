// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.invoicing.pdf;

import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.apache.xmpbox.XMPMetadata;
import org.apache.xmpbox.xml.XmpSerializer;
import org.mustangproject.*;
import org.mustangproject.ZUGFeRD.*;
import org.mustangproject.validator.*;
import org.vernac.saas.invoicing.domain.*;

import java.awt.color.*;
import java.io.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * A bounded German PDF/A-3b Factur-X/EN16931 renderer. Validation failure never yields an issued
 * document.
 */
public final class ZugferdDocuments {
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm", Locale.GERMANY).withZone(BERLIN);
    private static final String EXEMPTION = "Steuerbefreiung für Kleinunternehmer gemäß § 19 UStG.";

    public RenderedInvoice render(InvoiceContent content) {
        Objects.requireNonNull(content);
        try {
            var invoice = structured(content);
            byte[] ordinary = visual(content);
            byte[] hybrid, xml;
            try (var exporter = new ZUGFeRDExporterFromA1()) {
                exporter.setConformanceLevel(PDFAConformanceLevel.BASIC);
                exporter.setZUGFeRDVersion(2);
                exporter.setProfile(Profiles.getByName("EN16931"));
                exporter.setCreator("Vernac");
                exporter.setProducer("Vernac");
                exporter.load(ordinary);
                exporter.setTransaction(invoice);
                xml = exporter.getProvider().getXML();
                var output = new ByteArrayOutputStream();
                exporter.export(output);
                hybrid = output.toByteArray();
            }
            var validator = new QuietValidator();
            validator.validate(hybrid, "invoice.pdf");
            if (!validator.wasCompletelyValid())
                throw new IllegalStateException("Invoice validation failed");
            return RenderedInvoice.of(
                    InvoicePdf.of(Base64.getEncoder().encodeToString(hybrid)),
                    InvoiceXml.of(new String(xml, StandardCharsets.UTF_8)));
        } catch (Exception invalid) {
            throw new IllegalStateException("Invoice rendering or validation failed", null);
        }
    }

    /**
     * Suppress result-item logging: invoice data and validation reports are not operational logs.
     */
    private static final class QuietValidator extends ZUGFeRDValidator {
        QuietValidator() {
            context = new ValidationContext(null);
        }
    }

    private static org.mustangproject.Invoice structured(InvoiceContent content) {
        var seller = party(content.seller().party()).setEmail(content.seller().contact().encoded());
        var taxId = content.seller().taxIdentifier().text();
        if (taxId.startsWith("DE")) seller.setVATID(taxId);
        else seller.setTaxID(taxId);
        var buyer =
                party(content.buyer().party())
                        .setEmail(content.buyer().contact().encoded())
                        .setID(content.buyer().customer().text());
        var invoice =
                new org.mustangproject.Invoice()
                        .setNumber(content.number().text())
                        .setIssueDate(date(content.issued().value()))
                        .setSender(seller)
                        .setRecipient(buyer)
                        .setCurrency("EUR")
                        .setDueDate(date(content.issued().value()))
                        .setDetailedDeliveryPeriod(
                                date(content.period().start().atZone(BERLIN).toLocalDate()),
                                date(
                                        content.period()
                                                .end()
                                                .minusNanos(1)
                                                .atZone(BERLIN)
                                                .toLocalDate()))
                        .setTotalPrepaidAmount(money(content.amounts().gross().value()))
                        .setPaymentTermDescription(
                                content.kind() == InvoiceKind.INVOICE
                                        ? "Bereits bezahlt."
                                        : "Bereits erstattet.");
        invoice.addNote(
                "Ursprüngliche Zahlung beim Zahlungsanbieter verbucht am "
                        + TIME.format(content.paymentPostedAt().value())
                        + " (Europe/Berlin).");
        if (content.environment() == InvoiceEnvironment.TEST) invoice.setTestIndicator();
        if (content.kind() == InvoiceKind.CORRECTION) {
            var original = content.original().orElseThrow();
            invoice.setCreditNote()
                    .addInvoiceReferencedDocument(
                            new ReferencedDocument(
                                    original.number().text(), date(original.issued().value())));
        }
        var product =
                new Product(
                        content.description().text(),
                        "SaaS-Abonnement " + content.terms().plan().value(),
                        "C62",
                        content.seller().treatment() == InvoiceTaxTreatment.STANDARD_19
                                ? new BigDecimal("19")
                                : BigDecimal.ZERO);
        if (content.seller().treatment() == InvoiceTaxTreatment.SMALL_BUSINESS_EXEMPT)
            product.setTaxCategoryCode("E").setTaxExemptionReason(EXEMPTION);
        // Retain the explicit gross/cumulative-credit cent attribution from the fiscal snapshot.
        // The complete resulting document still has to pass the unmodified arithmetic validator.
        invoice.addItem(
                new Item(product, money(content.amounts().net().value()), BigDecimal.ONE) {
                    @Override
                    public LineCalculator getCalculation() {
                        return new LineCalculator(this) {
                            @Override
                            public BigDecimal getItemTotalVATAmount() {
                                return money(content.amounts().tax().value());
                            }
                        };
                    }
                });
        return invoice;
    }

    private static TradeParty party(InvoiceParty party) {
        return new TradeParty(
                party.name().text(),
                party.street().text(),
                party.postalCode().text(),
                party.city().text(),
                "DE");
    }

    private static java.util.Date date(LocalDate value) {
        return java.sql.Date.valueOf(value);
    }

    private static BigDecimal money(long value) {
        return BigDecimal.valueOf(value, 2);
    }

    private static String euros(long value) {
        return money(value).toPlainString().replace('.', ',') + " EUR";
    }

    private static byte[] visual(InvoiceContent content) throws Exception {
        try (var document = new PDDocument();
                var font =
                        PDDocument.class.getResourceAsStream(
                                "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            document.setVersion(1.4f);
            var typeface = PDType0Font.load(document, Objects.requireNonNull(font));
            var info = document.getDocumentInformation();
            info.setTitle(content.number().text());
            info.setCreator("Vernac");
            info.setProducer("Vernac");
            var xmp = XMPMetadata.createXMPMetadata();
            var dc = xmp.createAndAddDublinCoreSchema();
            dc.setTitle(content.number().text());
            dc.setFormat("application/pdf");
            xmp.createAndAddXMPBasicSchema().setCreatorTool("Vernac");
            xmp.createAndAddAdobePDFSchema().setProducer("Vernac");
            var identification = xmp.createAndAddPDFAIdentificationSchema();
            identification.setPart(1);
            identification.setConformance("B");
            var metadata = new PDMetadata(document);
            var bytes = new ByteArrayOutputStream();
            new XmpSerializer().serialize(xmp, bytes, true);
            metadata.importXMPMetadata(bytes.toByteArray());
            document.getDocumentCatalog().setMetadata(metadata);
            var intent =
                    new PDOutputIntent(
                            document,
                            new ByteArrayInputStream(
                                    ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData()));
            intent.setInfo("sRGB IEC61966-2.1");
            intent.setOutputConditionIdentifier("sRGB IEC61966-2.1");
            intent.setRegistryName("http://www.color.org");
            document.getDocumentCatalog().addOutputIntent(intent);
            try (var page = new TextPages(document, typeface, content.number().text())) {
                if (content.environment() == InvoiceEnvironment.TEST)
                    page.line("TESTRECHNUNG - keine steuerliche Rechnung", 15);
                page.line(
                        content.kind() == InvoiceKind.INVOICE ? "Rechnung" : "Rechnungskorrektur",
                        22);
                page.line(content.number().text(), 14);
                page.line(
                        "Ausgestellt am "
                                + content.issued()
                                        .value()
                                        .format(DateTimeFormatter.ofPattern("dd.MM.uuuu")),
                        10);
                page.blank();
                page.line("Aussteller", 12);
                page.party(content.seller().party());
                page.line(
                        (content.seller().taxIdentifier().text().startsWith("DE")
                                        ? "USt-IdNr.: "
                                        : "Steuernummer: ")
                                + content.seller().taxIdentifier().text(),
                        10);
                page.line(content.seller().contact().encoded(), 10);
                page.blank();
                page.line("Rechnungsempfänger", 12);
                page.party(content.buyer().party());
                page.line("Kundennummer: " + content.buyer().customer().text(), 10);
                page.blank();
                if (content.original().isPresent()) {
                    var original = content.original().get();
                    page.line(
                            "Korrektur zu "
                                    + original.number().text()
                                    + " vom "
                                    + original.issued().value(),
                            10);
                }
                page.line(content.description().text(), 12);
                page.line(
                        "Abonnement: "
                                + content.terms().plan().value()
                                + " ("
                                + content.terms().revision().value()
                                + ")",
                        10);
                page.line(
                        "Leistungszeitraum: "
                                + TIME.format(content.period().start())
                                + " bis "
                                + TIME.format(content.period().end())
                                + " (Europe/Berlin)",
                        10);
                page.blank();
                page.keep(130);
                page.line("Nettobetrag: " + euros(content.amounts().net().value()), 11);
                page.line(
                        (content.seller().treatment() == InvoiceTaxTreatment.STANDARD_19
                                        ? "Umsatzsteuer 19 %: "
                                        : "Umsatzsteuer: ")
                                + euros(content.amounts().tax().value()),
                        11);
                page.line("Gesamtbetrag: " + euros(content.amounts().gross().value()), 14);
                if (content.seller().treatment() == InvoiceTaxTreatment.SMALL_BUSINESS_EXEMPT)
                    page.line(EXEMPTION, 10);
                page.blank();
                page.line(
                        content.kind() == InvoiceKind.INVOICE
                                ? "Bereits bezahlt. Offener Betrag: 0,00 EUR."
                                : "Bereits erstattet. Offener Betrag: 0,00 EUR.",
                        11);
                page.line(
                        "Ursprüngliche Zahlung beim Zahlungsanbieter verbucht am "
                                + TIME.format(content.paymentPostedAt().value())
                                + " (Europe/Berlin).",
                        10);
            }
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                try (var footer =
                        new PDPageContentStream(
                                document,
                                document.getPage(index),
                                PDPageContentStream.AppendMode.APPEND,
                                true,
                                true)) {
                    footer.beginText();
                    footer.setFont(typeface, 9);
                    footer.newLineAtOffset(54, 28);
                    footer.showText(
                            content.number().text()
                                    + " | Seite "
                                    + (index + 1)
                                    + " von "
                                    + document.getNumberOfPages());
                    footer.endText();
                }
            }
            var output = new ByteArrayOutputStream();
            document.save(output, CompressParameters.NO_COMPRESSION);
            return output.toByteArray();
        }
    }

    private static final class TextPages implements AutoCloseable {
        private final PDDocument document;
        private final PDType0Font font;
        private final String number;
        private PDPageContentStream stream;
        private float y;

        TextPages(PDDocument document, PDType0Font font, String number) {
            this.document = document;
            this.font = font;
            this.number = number;
        }

        void party(InvoiceParty party) throws IOException {
            line(party.name().text(), 10);
            line(party.street().text(), 10);
            line(party.postalCode().text() + " " + party.city().text(), 10);
            line("Deutschland", 10);
        }

        void blank() {
            y -= 9;
        }

        void keep(float height) throws IOException {
            if (stream != null && y - height >= 54) return;
            if (stream != null) stream.close();
            var page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = page.getMediaBox().getHeight() - 54;
            if (document.getNumberOfPages() > 1) {
                stream.beginText();
                stream.setFont(font, 10);
                stream.newLineAtOffset(54, y);
                stream.showText("Fortsetzung " + number);
                stream.endText();
                y -= 28;
            }
        }

        void line(String text, float size) throws IOException {
            var remaining = text;
            while (!remaining.isEmpty()) {
                int end = remaining.length();
                while (end > 0
                        && font.getStringWidth(remaining.substring(0, end)) * size / 1000 > 480)
                    end = remaining.offsetByCodePoints(end, -1);
                if (end == 0) throw new IOException("Unsupported invoice text width");
                if (end < remaining.length()) {
                    int word = remaining.lastIndexOf(' ', end);
                    if (word > 0) end = word;
                }
                keep(size + 5);
                stream.beginText();
                stream.setFont(font, size);
                stream.newLineAtOffset(54, y);
                stream.showText(remaining.substring(0, end));
                stream.endText();
                y -= size + 5;
                remaining = remaining.substring(end).stripLeading();
            }
        }

        public void close() throws IOException {
            if (stream != null) stream.close();
        }
    }

    @Override
    public String toString() {
        return "ZugferdDocuments";
    }
}
