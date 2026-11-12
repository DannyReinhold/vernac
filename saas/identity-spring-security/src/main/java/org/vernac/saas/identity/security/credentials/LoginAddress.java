// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security.credentials;

import static org.vernac.saas.identity.security.credentials.CredentialInputException.Code.*;

import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Technical ingress representation, not an identity, mailbox-equivalence claim or deliverability
 * proof.
 */
public final class LoginAddress {
    private static final Pattern LOCAL =
            Pattern.compile(
                    "[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*");
    private static final Pattern LABEL =
            Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?");
    private final String deliveryAddress;
    private final String loginKey;

    private LoginAddress(String deliveryAddress) {
        this.deliveryAddress = deliveryAddress;
        this.loginKey = deliveryAddress.toLowerCase(Locale.ROOT);
    }

    public static LoginAddress parse(@Nullable String input) {
        if (input == null || input.length() > 16_384)
            throw new CredentialInputException(INVALID_ADDRESS);
        int start = 0, end = input.length();
        while (start < end && input.charAt(start) == ' ') start++;
        while (end > start && input.charAt(end - 1) == ' ') end--;
        String address = input.substring(start, end);
        for (int i = 0; i < address.length(); i++) {
            if (address.charAt(i) > 127) throw new CredentialInputException(UNSUPPORTED_ADDRESS);
        }
        int at = address.indexOf('@');
        if (address.length() > 254 || at < 1 || at > 64 || at != address.lastIndexOf('@')) {
            throw new CredentialInputException(INVALID_ADDRESS);
        }
        String local = address.substring(0, at), domain = address.substring(at + 1);
        String[] labels = domain.split("\\.", -1);
        if (!LOCAL.matcher(local).matches() || domain.length() > 253 || labels.length < 2) {
            throw new CredentialInputException(INVALID_ADDRESS);
        }
        for (String label : labels) {
            if (!LABEL.matcher(label).matches())
                throw new CredentialInputException(INVALID_ADDRESS);
        }
        return new LoginAddress(address);
    }

    /** Sensitive input: use only for the trusted delivery boundary, never ordinary logging. */
    public String deliveryAddress() {
        return deliveryAddress;
    }

    /** Stable case-insensitive lookup key. Uniqueness still requires a database constraint. */
    public String loginKey() {
        return loginKey;
    }

    @Override
    public String toString() {
        return "LoginAddress[redacted]";
    }
}
