// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security.credentials;

import static org.junit.jupiter.api.Assertions.*;
import static org.vernac.saas.identity.security.credentials.CredentialInputException.Code.*;

import org.junit.jupiter.api.Test;
import org.vernac.runtime.DomainValidationException;

class PasswordInputTest {
    @Test
    void countsUnicodeScalarsAndAppliesNfcWithoutTrimmingOrCaseFolding() {
        assertEquals("é".repeat(15), PasswordInput.forCreation("e\u0301".repeat(15)));
        assertEquals("😀".repeat(15), PasswordInput.forCreation("😀".repeat(15)));
        assertEquals("😀".repeat(128), PasswordInput.forCreation("😀".repeat(128)));
        String input = " A deliberately spaced secret ";
        assertEquals(input, PasswordInput.forCreation(input));
    }

    @Test
    void rejectsEveryLengthAndUnicodeBoundaryWithoutEchoOrCause() {
        assertCode(PASSWORD_TOO_SHORT, "a".repeat(14));
        assertCode(PASSWORD_TOO_LONG, "😀".repeat(129));
        assertCode(PASSWORD_TOO_LONG, "a".repeat(1_025));
        assertCode(PASSWORD_TOO_LONG, "a".repeat(2_049));
        for (String value :
                new String[] {
                    "secret" + '\ud800', "secret" + '\udc00', "secret" + '\ud800' + 'x'
                }) {
            assertCode(INVALID_PASSWORD_UNICODE, value);
        }
        assertThrows(DomainValidationException.class, () -> PasswordInput.forCreation(null));
    }

    @Test
    void oversizedSequenceIsRejectedBeforeItIsMaterialized() {
        CharSequence hostileSize =
                new CharSequence() {
                    public int length() {
                        return 2_049;
                    }

                    public char charAt(int index) {
                        throw new AssertionError("Must not read input");
                    }

                    public CharSequence subSequence(int start, int end) {
                        throw new AssertionError("Must not slice input");
                    }

                    public String toString() {
                        throw new AssertionError("Must not materialize input");
                    }
                };
        assertEquals(
                PASSWORD_TOO_LONG,
                assertThrows(
                                CredentialInputException.class,
                                () -> PasswordInput.normalize(hostileSize))
                        .code());
    }

    @Test
    void loginNormalizationDoesNotApplyCreationMinimumOrCompositionRules() {
        assertEquals("a", PasswordInput.normalize("a"));
        assertEquals("", PasswordInput.normalize(""));
        assertEquals(" ".repeat(15), PasswordInput.forCreation(" ".repeat(15)));
        assertEquals("a\u0000".repeat(8), PasswordInput.forCreation("a\u0000".repeat(8)));
    }

    private void assertCode(CredentialInputException.Code expected, String input) {
        var failure =
                assertThrows(
                        CredentialInputException.class, () -> PasswordInput.forCreation(input));
        assertEquals(expected, failure.code());
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("secret"));
    }
}
