// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.invoicing;

import org.xml.sax.InputSource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/** Bounded lexical contracts. Fiscal and PDF/A validation belongs to the rendering adapter. */
public final class InvoiceRules {
    private InvoiceRules() {}

    public static void requireRetry(boolean allowed) {
        if (!allowed) throw new InvoiceFailure(InvoiceFailure.Code.INVALID_STATE);
    }

    public static boolean text(String value) {
        return value != null
                && !value.isBlank()
                && value.equals(value.strip())
                && value.length() <= 200
                && value.codePoints()
                        .noneMatch(
                                c ->
                                        Character.isISOControl(c)
                                                || Character.getType(c) == Character.FORMAT
                                                || c == 0xfffd);
    }

    public static boolean pdf(String encoded) {
        if (encoded == null || encoded.length() > 5_592_408) return false;
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            return bytes.length >= 8
                    && bytes.length <= 4_194_304
                    && Base64.getEncoder().encodeToString(bytes).equals(encoded)
                    && new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-");
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public static boolean xml(String value) {
        if (value == null
                || value.length() > 262_144
                || value.getBytes(StandardCharsets.UTF_8).length > 262_144) return false;
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            var root = builder.parse(new InputSource(new StringReader(value))).getDocumentElement();
            return "CrossIndustryInvoice".equals(root.getLocalName())
                    && "urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100"
                            .equals(root.getNamespaceURI());
        } catch (Exception invalid) {
            return false;
        }
    }
}
