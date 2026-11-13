// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import static org.vernac.saas.identity.persistence.JdbcIdentityAdmission.Code.KEY_UNAVAILABLE;

import org.vernac.saas.identity.persistence.domain.*;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;

/** JCA HMAC only. No key generation, plaintext storage, external key fetch or fallback. */
final class AdmissionDigests {
    private final SecretKey key;

    AdmissionDigests(SecretKey supplied) {
        byte[] bytes = null;
        try {
            if (supplied == null || !"HmacSHA256".equalsIgnoreCase(supplied.getAlgorithm()))
                throw new IllegalArgumentException();
            byte[] suppliedBytes = supplied.getEncoded();
            if (suppliedBytes == null || suppliedBytes.length != 32)
                throw new IllegalArgumentException();
            bytes = suppliedBytes.clone();
            key = new SecretKeySpec(bytes, "HmacSHA256");
        } catch (RuntimeException unavailable) {
            throw new JdbcIdentityAdmission.Failure(KEY_UNAVAILABLE);
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
    }

    IdentityAdmissionDigest digest(String purpose, byte[] value) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            mac.update(
                    ("vernac.identity.admission.v1\0" + purpose + "\0")
                            .getBytes(StandardCharsets.US_ASCII));
            return IdentityAdmissionDigest.of(HexFormat.of().formatHex(mac.doFinal(value)));
        } catch (GeneralSecurityException | RuntimeException unavailable) {
            throw new JdbcIdentityAdmission.Failure(KEY_UNAVAILABLE);
        }
    }

    static IdentityAdmissionId id(IdentityAdmissionDigest digest) {
        var bytes = ByteBuffer.wrap(HexFormat.of().parseHex(digest.encoded()));
        // Technical index locator only. Full 256-bit digest/kind must match before any use.
        return IdentityAdmissionId.of(new UUID(bytes.getLong(), bytes.getLong()));
    }

    static boolean equal(IdentityAdmissionDigest a, IdentityAdmissionDigest b) {
        return MessageDigest.isEqual(
                HexFormat.of().parseHex(a.encoded()), HexFormat.of().parseHex(b.encoded()));
    }

    @Override
    public String toString() {
        return "AdmissionDigests[REDACTED]";
    }
}
