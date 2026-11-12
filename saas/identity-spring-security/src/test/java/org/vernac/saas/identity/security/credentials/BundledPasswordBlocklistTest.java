// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security.credentials;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BundledPasswordBlocklistTest {
    @Test
    void bundledSnapshotLoadsWithPinnedVersionAndRealEligibleEntries() {
        var corpus = OfflinePasswordBlocklist.bundled();
        assertEquals("seclists-xato-1m-20261112-v1", corpus.version());
        assertEquals(10_908, corpus.entryCount());
        for (String common :
                new String[] {
                    "123456789012345", "passwordpassword", "111111111111111", "qwertyuiopasdfgh"
                }) {
            assertTrue(corpus.contains(common));
        }
        assertFalse(corpus.contains("passwordpassword plus an unrelated suffix"));
        assertFalse(corpus.contains("PASSWORDPASSWORD"));
    }

    @Test
    void standardEncoderRejectsRealWeakSecretsAndStillAcceptsAnIndependentPhrase() {
        var passwords = new CredentialPasswords(OfflinePasswordBlocklist.bundled());
        var failure =
                assertThrows(
                        CredentialInputException.class, () -> passwords.encode("passwordpassword"));
        assertEquals(CredentialInputException.Code.PASSWORD_BLOCKED, failure.code());
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("passwordpassword"));
        String phrase = "Vernac test fixture: three independently selected owls 2026!";
        String stored = passwords.encode(phrase);
        assertTrue(passwords.matches(phrase, stored));
    }

    @Test
    void allPublishedEntriesRespectTheSameJavaNormalizationAndCreationBounds() throws Exception {
        try (var input = getClass().getResourceAsStream("passwords/blocked-v1.txt")) {
            assertNotNull(input);
            String data = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(data.contains("\r"));
            assertTrue(data.endsWith("\n"));
            assertEquals(
                    10_908,
                    data.lines()
                            .peek(value -> assertEquals(value, PasswordInput.forCreation(value)))
                            .count());
        }
    }
}
