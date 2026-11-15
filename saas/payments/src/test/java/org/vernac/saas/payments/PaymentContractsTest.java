// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.payments;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.vernac.saas.payments.domain.*;
class PaymentContractsTest {
    @Test void urlsNotificationsAndReferencesAreBoundedAndDoNotRenderSensitiveValues() {
        assertTrue(PaymentValidation.httpsUri("https://example.test/payment"));
        for (String invalid : new String[]{"http://example.test", "https://user:secret@example.test/", "https://example.test/#secret", "https://example.test/\\evil", "//example.test/"}) assertFalse(PaymentValidation.httpsUri(invalid));
        assertFalse(PaymentValidation.notice("x".repeat(65537))); assertFalse(PaymentValidation.notice("\ud800")); assertFalse(PaymentValidation.signature("injected\r\n"));
        assertFalse(RemoteCustomerId.of("cus_sensitive").toString().contains("sensitive"));
        assertFalse(PaymentNoticeBody.of("sensitive").toString().contains("sensitive"));
    }
}
