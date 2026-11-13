// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.experiments.mail;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.encrypt.AesGcmBytesEncryptor;
import org.springframework.security.crypto.encrypt.BytesEncryptor;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.crypto.spec.SecretKeySpec;

/** Library compatibility evidence only; not an application envelope/key-ring implementation. */
class SpringMailCryptoTest {
    private static final byte[] PAYLOAD =
            "synthetic-job|synthetic-user|verify|synthetic-recipient|synthetic-secret"
                    .getBytes(StandardCharsets.UTF_8);

    @Test
    void deploymentKeySurvivesNewEncryptorInstanceWithoutPasswordDerivation() {
        byte[] ciphertext = encryptor(1).encrypt(PAYLOAD);
        assertFalse(Arrays.equals(PAYLOAD, ciphertext));
        assertArrayEquals(PAYLOAD, encryptor(1).decrypt(ciphertext));
    }

    @Test
    void libraryGeneratesDifferentCiphertextsForRepeatedPayload() {
        BytesEncryptor library = encryptor(1);
        byte[] first = library.encrypt(PAYLOAD);
        byte[] second = library.encrypt(PAYLOAD);
        assertFalse(Arrays.equals(first, second));
        assertArrayEquals(PAYLOAD, library.decrypt(first));
        assertArrayEquals(PAYLOAD, library.decrypt(second));
    }

    @Test
    void alteredOrTruncatedCiphertextDoesNotReturnPlaintext() {
        byte[] ciphertext = encryptor(1).encrypt(PAYLOAD);
        // Exercise IV, body and authentication tag, not just a single trailing byte.
        for (int position : new int[] {0, ciphertext.length / 2, ciphertext.length - 1}) {
            byte[] changed = ciphertext.clone();
            changed[position] ^= 1;
            assertThrows(RuntimeException.class, () -> encryptor(1).decrypt(changed));
        }
        for (int length : new int[] {0, 1, ciphertext.length - 1}) {
            byte[] truncated = Arrays.copyOf(ciphertext, length);
            assertThrows(RuntimeException.class, () -> encryptor(1).decrypt(truncated));
        }
    }

    @Test
    void rotatedWriterCannotReadOldJobsButRetainedReadKeyCan() {
        byte[] oldJob = encryptor(1).encrypt(PAYLOAD);
        byte[] newJob = encryptor(2).encrypt(PAYLOAD);
        assertThrows(RuntimeException.class, () -> encryptor(2).decrypt(oldJob));
        assertThrows(RuntimeException.class, () -> encryptor(1).decrypt(newJob));
        assertArrayEquals(PAYLOAD, encryptor(1).decrypt(oldJob));
        assertArrayEquals(PAYLOAD, encryptor(2).decrypt(newJob));
    }

    @Test
    void libraryRejectsInvalidAesKeyMaterial() {
        assertThrows(
                RuntimeException.class,
                () ->
                        AesGcmBytesEncryptor.withSecretKey(new SecretKeySpec(new byte[7], "AES"))
                                .build()
                                .encrypt(PAYLOAD));
    }

    private static BytesEncryptor encryptor(int fixture) {
        // Deterministic public test material, NEVER deployment key generation.
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) fixture);
        return AesGcmBytesEncryptor.withSecretKey(new SecretKeySpec(key, "AES")).build();
    }
}
