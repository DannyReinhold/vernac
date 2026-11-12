// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security.credentials;

import static org.junit.jupiter.api.Assertions.*;
import static org.vernac.saas.identity.security.credentials.CredentialConfigurationException.Code.*;

import org.junit.jupiter.api.*;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.vernac.runtime.DomainValidationException;

import java.util.*;
import java.util.concurrent.*;

class CredentialPasswordsTest {
    private static CredentialPasswords passwords;

    @BeforeAll
    static void setup() throws Exception {
        passwords = new CredentialPasswords(CorpusFixture.load());
    }

    @Test
    void writesVersionedSaltedArgon2idCompatibleWithTheRealSpringEncoder() {
        String secret = "A long test-only secret phrase 🦉";
        String first = passwords.encode(secret), second = passwords.encode(secret);
        assertNotEquals(first, second);
        assertFalse(first.contains(secret));
        String prefix = "{" + CredentialPasswords.ENCODING_ID + "}";
        assertTrue(first.startsWith(prefix + "$argon2id$v=19$m=19456,t=2,p=1$"));
        assertTrue(
                new Argon2PasswordEncoder(16, 32, 1, 19_456, 2)
                        .matches(secret, first.substring(prefix.length())));
        assertTrue(passwords.matches(secret, first));
        assertFalse(passwords.matches(secret + "!", first));
        assertEquals("CredentialPasswords[redacted]", passwords.toString());
    }

    @Test
    void normalizesNfcButPreservesCaseAndWhitespaceInRealHashes() {
        String stored = passwords.encode("e\u0301".repeat(15));
        assertTrue(passwords.matches("é".repeat(15), stored));
        assertFalse(passwords.matches("É".repeat(15), stored));
        String spaced = passwords.encode(" A long preserved phrase ");
        assertTrue(passwords.matches(" A long preserved phrase ", spaced));
        assertFalse(passwords.matches("A long preserved phrase", spaced));
        String emoji = passwords.encode("😀".repeat(15));
        assertTrue(passwords.matches("😀".repeat(15), emoji));
    }

    @Test
    void enforcesEnrollmentPolicyButDoesNotApplyTheCurrentCorpusAtLogin() throws Exception {
        var blocked =
                assertThrows(
                        CredentialInputException.class,
                        () -> passwords.encode("TEST-ONLY blocked phrase 1"));
        assertEquals(CredentialInputException.Code.PASSWORD_BLOCKED, blocked.code());
        assertNull(blocked.getCause());
        assertThrows(CredentialInputException.class, () -> passwords.encode("short"));
        String existing =
                "{"
                        + CredentialPasswords.ENCODING_ID
                        + "}"
                        + new Argon2PasswordEncoder(16, 32, 1, 19_456, 2)
                                .encode("TEST-ONLY blocked phrase 1");
        assertTrue(passwords.matches("TEST-ONLY blocked phrase 1", existing));
        assertThrows(DomainValidationException.class, () -> passwords.encode(null));
        assertThrows(DomainValidationException.class, () -> passwords.matches(null, existing));
        assertThrows(DomainValidationException.class, () -> new CredentialPasswords(null));
    }

    @Test
    void rejectsMalformedAndExcessiveStoredWorkBeforeInvokingArgon2() {
        String valid = passwords.encode("A long independent secret");
        for (String value :
                List.of(
                        "{noop}seeded-secret",
                        "seeded-secret",
                        valid.replace("v=19", "v=16"),
                        valid.replace("m=19456", "m=99999"),
                        valid.replace("m=19456", "m=00001"),
                        valid.replace("t=2", "t=9"),
                        valid.replace("p=1", "p=2"),
                        valid + "=",
                        "x".repeat(257))) {
            var failure =
                    assertThrows(
                            CredentialConfigurationException.class,
                            () -> passwords.matches("seeded-secret", value));
            assertEquals(STORED_CREDENTIAL_INVALID, failure.code());
            assertNull(failure.getCause());
            assertFalse(failure.toString().contains("seeded-secret"));
        }
        assertEquals(
                STORED_CREDENTIAL_INVALID,
                assertThrows(
                                CredentialConfigurationException.class,
                                () -> passwords.matches("x", null))
                        .code());
        assertFalse(passwords.matches("x".repeat(2_049), valid));
        assertFalse(passwords.matches("\ud800", valid));
    }

    @Test
    void unknownSubjectWorkloadCannotReturnAnAuthenticationDecision() {
        assertDoesNotThrow(() ->
                passwords.performDummyVerification(
                        "Non-secret bounded dummy verification workload"));
        assertDoesNotThrow(() -> passwords.performDummyVerification("not a match"));
    }

    @Test
    void sharedServiceDoesNotMixConcurrentSecrets() throws Exception {
        String a = "First independently scoped password",
                b = "Second independently scoped password";
        String first = passwords.encode(a), second = passwords.encode(b);
        try (var tasks = Executors.newFixedThreadPool(2)) {
            var results =
                    tasks.invokeAll(
                            List.of(
                                    () ->
                                            passwords.matches(a, first)
                                                    && !passwords.matches(b, first),
                                    () ->
                                            passwords.matches(b, second)
                                                    && !passwords.matches(a, second)));
            for (var result : results) assertEquals(Boolean.TRUE, result.get());
        }
    }
}
