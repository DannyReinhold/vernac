// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security.credentials;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class StoredEncodingPolicyTest {
    @Test
    void persistenceChecksReuseTheApprovedEncodingFormatAndWorkBounds() {
        var passwords = new CredentialPasswords(OfflinePasswordBlocklist.bundled());
        String encoded = passwords.encode("an independent strong phrase for this fixture 9481");
        assertTrue(CredentialPasswords.supportsEncoding(encoded));
        for (String invalid :
                java.util.List.of(
                        "",
                        "plaintext-password",
                        "{noop}unprotected",
                        "a".repeat(10000),
                        encoded.replace("vernac-argon2id-v1", "unknown"),
                        encoded.replace("m=19456", "m=99999"),
                        encoded.replace("t=2", "t=9"),
                        encoded + "=")) assertFalse(CredentialPasswords.supportsEncoding(invalid));
        assertFalse(CredentialPasswords.supportsEncoding(null));
        assertTrue(
                passwords.matches("an independent strong phrase for this fixture 9481", encoded));
    }
}
