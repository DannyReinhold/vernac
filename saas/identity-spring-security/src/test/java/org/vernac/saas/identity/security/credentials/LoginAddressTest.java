// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security.credentials;

import static org.junit.jupiter.api.Assertions.*;
import static org.vernac.saas.identity.security.credentials.CredentialInputException.Code.*;

import org.junit.jupiter.api.Test;
import org.vernac.runtime.DomainValidationException;

import java.util.*;

class LoginAddressTest {
    @Test
    void preservesDeliveryAndMakesOnlyTheDeclaredLoginKeyTransformation() {
        var address = LoginAddress.parse(" Alice+Reports@Example.COM ");
        assertEquals("Alice+Reports@Example.COM", address.deliveryAddress());
        assertEquals("alice+reports@example.com", address.loginKey());
        assertEquals(
                address.loginKey(), LoginAddress.parse("alice+reports@example.com").loginKey());
        assertNotEquals(address.loginKey(), LoginAddress.parse("alice@example.com").loginKey());
        assertNotEquals(
                LoginAddress.parse("a.lice@example.com").loginKey(),
                LoginAddress.parse("alice@example.com").loginKey());
        assertEquals(
                "alice@xn--bcher-kva.example",
                LoginAddress.parse("alice@xn--bcher-kva.example").loginKey());
    }

    @Test
    void localeDoesNotChangeLoginIdentity() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("issue@example.com", LoginAddress.parse("ISSUE@EXAMPLE.COM").loginKey());
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void boundsAddressLocalPartAndDnsLabelsPrecisely() {
        String domain = "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61);
        String maximum = "a".repeat(64) + "@" + domain;
        assertEquals(254, maximum.length());
        assertEquals(maximum, LoginAddress.parse(maximum).deliveryAddress());
        for (String value :
                List.of(
                        maximum + "d",
                        "a".repeat(65) + "@example.com",
                        "a@" + "b".repeat(64) + ".com",
                        "a@-example.com",
                        "a@example-.com",
                        "a@example..com",
                        "a@example.com.",
                        "a@localhost")) {
            assertEquals(
                    INVALID_ADDRESS,
                    assertThrows(CredentialInputException.class, () -> LoginAddress.parse(value))
                            .code());
        }
    }

    @Test
    void rejectsUnsupportedAndHeaderLikeInputWithoutEchoingIt() {
        for (String value :
                List.of(
                        "Alice <alice@example.com>",
                        "a..b@example.com",
                        ".a@example.com",
                        "a.@example.com",
                        "a@@example.com",
                        "a@example.com\r\nBcc: secret@example.com",
                        "\ta@example.com",
                        "\"a\"@example.com",
                        "")) {
            var error =
                    assertThrows(CredentialInputException.class, () -> LoginAddress.parse(value));
            assertEquals(INVALID_ADDRESS, error.code());
            assertNull(error.getCause());
            assertFalse(error.getMessage().contains("example.com"));
        }
        for (String value : List.of("alice@bücher.example", "álîce@example.com")) {
            assertEquals(
                    UNSUPPORTED_ADDRESS,
                    assertThrows(CredentialInputException.class, () -> LoginAddress.parse(value))
                            .code());
        }
        assertThrows(DomainValidationException.class, () -> LoginAddress.parse(null));
        assertThrows(CredentialInputException.class, () -> LoginAddress.parse(" ".repeat(16_385)));
    }

    @Test
    void representationsDoNotExposeTheAddress() {
        var address = LoginAddress.parse("Private.Owner@example.com");
        assertEquals("LoginAddress[redacted]", address.toString());
        assertFalse(address.toString().contains(address.loginKey()));
    }
}
