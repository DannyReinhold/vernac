// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import org.vernac.saas.identity.security.credentials.LoginAddress;

import java.io.*;
import java.util.*;

/** Recoverable secrets only through explicit access; never a generic event or log argument. */
final class IdentityMailContent {
    private final String recipient;
    private final IdentityChallengeToken token;

    IdentityMailContent(String recipient, IdentityChallengeToken token) {
        if (!LoginAddress.parse(recipient).deliveryAddress().equals(recipient) || token == null)
            throw unavailable();
        this.recipient = recipient;
        this.token = token;
    }

    String recipient() {
        return recipient;
    }

    String deliveryToken() {
        return token.deliveryValue();
    }

    UUID locator() {
        return token.locator();
    }

    byte[] encode() {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeInt(1);
            out.writeUTF(recipient);
            out.writeUTF(token.deliveryValue());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw unavailable();
        }
    }

    static IdentityMailContent decode(byte[] payload, UUID expectedLocator) {
        try {
            if (payload == null || payload.length > IdentityDeliveryCrypto.MAX_PAYLOAD_BYTES)
                throw unavailable();
            var in = new DataInputStream(new ByteArrayInputStream(payload));
            if (in.readInt() != 1) throw unavailable();
            String recipient = in.readUTF();
            var token = IdentityChallengeToken.parse(in.readUTF());
            if (token == null || !token.locator().equals(expectedLocator) || in.available() != 0)
                throw unavailable();
            return new IdentityMailContent(recipient, token);
        } catch (IOException | RuntimeException invalid) {
            throw unavailable();
        }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("IDENTITY_DELIVERY_UNAVAILABLE");
    }

    @Override
    public String toString() {
        return "IdentityMailContent[REDACTED]";
    }
}
