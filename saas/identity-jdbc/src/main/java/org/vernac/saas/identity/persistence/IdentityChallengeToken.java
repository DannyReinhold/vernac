// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import org.jspecify.annotations.Nullable;

import java.security.*;
import java.util.*;

/** Private capability. Only the explicit delivery accessor reveals its canonical transport form. */
final class IdentityChallengeToken {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final UUID locator;
    private final byte[] secret;

    private IdentityChallengeToken(UUID locator, byte[] secret) {
        this.locator = locator;
        this.secret = secret.clone();
    }

    static IdentityChallengeToken create() {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        return new IdentityChallengeToken(UUID.randomUUID(), secret);
    }

    static @Nullable IdentityChallengeToken parse(@Nullable String input) {
        if (input == null
                || input.length() != 80
                || !input.matches(
                        "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.[A-Za-z0-9_-]{43}"))
            return null;
        try {
            byte[] secret = Base64.getUrlDecoder().decode(input.substring(37));
            var token = new IdentityChallengeToken(UUID.fromString(input.substring(0, 36)), secret);
            return token.deliveryValue().equals(input) ? token : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    UUID locator() {
        return locator;
    }

    String deliveryValue() {
        return locator + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    String verifier() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("Identity challenge digest unavailable");
        }
    }

    boolean matches(String stored) {
        if (stored == null || !stored.matches("[0-9a-f]{64}"))
            throw new IllegalStateException("Identity challenge storage invalid");
        return MessageDigest.isEqual(
                HexFormat.of().parseHex(verifier()), HexFormat.of().parseHex(stored));
    }

    @Override
    public String toString() {
        return "IdentityChallengeToken[REDACTED]";
    }
}
