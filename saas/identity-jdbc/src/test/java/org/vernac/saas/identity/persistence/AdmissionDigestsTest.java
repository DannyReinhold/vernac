// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.vernac.saas.identity.persistence.domain.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;

class AdmissionDigestsTest {
    @Test
    void jcaKeyedPseudonymsAreStableSeparatedAndDoNotMutateCallerMaterial() {
        byte[] original = new byte[32];
        Arrays.fill(original, (byte) 3);
        SecretKey mutable =
                new SecretKey() {
                    public String getAlgorithm() {
                        return "HmacSHA256";
                    }

                    public String getFormat() {
                        return "RAW";
                    }

                    public byte[] getEncoded() {
                        return original;
                    }
                };
        var digests = new AdmissionDigests(mutable);
        assertEquals(3, original[0]);
        var input = "test-login@example.invalid".getBytes(StandardCharsets.US_ASCII);
        var first = digests.digest("LOGIN_KEY", input);
        assertTrue(AdmissionDigests.equal(first, digests.digest("LOGIN_KEY", input)));
        assertFalse(AdmissionDigests.equal(first, digests.digest("MAIL_KEY", input)));
        Arrays.fill(original, (byte) 9);
        assertTrue(AdmissionDigests.equal(first, digests.digest("LOGIN_KEY", input)));
        assertFalse(
                AdmissionDigests.equal(
                        first,
                        new AdmissionDigests(new SecretKeySpec(original, "HmacSHA256"))
                                .digest("LOGIN_KEY", input)));
        assertFalse(first.toString().contains(first.encoded()));
        assertFalse(digests.toString().contains("example.invalid"));
        var collisionA = IdentityAdmissionDigest.of("a".repeat(32) + "b".repeat(32));
        var collisionB = IdentityAdmissionDigest.of("a".repeat(32) + "c".repeat(32));
        assertEquals(AdmissionDigests.id(collisionA), AdmissionDigests.id(collisionB));
        assertFalse(AdmissionDigests.equal(collisionA, collisionB));
    }

    @Test
    void missingInvalidAndUnavailableKeysFailWithoutSecretCauses() {
        assertEquals(
                JdbcIdentityAdmission.Code.KEY_UNAVAILABLE,
                assertThrows(JdbcIdentityAdmission.Failure.class, () -> new AdmissionDigests(null))
                        .code());
        for (var key :
                List.of(
                        new SecretKeySpec(new byte[16], "HmacSHA256"),
                        new SecretKeySpec(new byte[32], "AES")))
            assertEquals(
                    JdbcIdentityAdmission.Code.KEY_UNAVAILABLE,
                    assertThrows(
                                    JdbcIdentityAdmission.Failure.class,
                                    () -> new AdmissionDigests(key))
                            .code());
        var unavailable =
                new SecretKey() {
                    public String getAlgorithm() {
                        return "HmacSHA256";
                    }

                    public String getFormat() {
                        return "RAW";
                    }

                    public byte[] getEncoded() {
                        throw new IllegalStateException("seeded-secret-key-material");
                    }
                };
        var error =
                assertThrows(
                        JdbcIdentityAdmission.Failure.class,
                        () -> new AdmissionDigests(unavailable));
        assertNull(error.getCause());
        assertFalse(error.toString().contains("seeded-secret-key-material"));
    }
}
