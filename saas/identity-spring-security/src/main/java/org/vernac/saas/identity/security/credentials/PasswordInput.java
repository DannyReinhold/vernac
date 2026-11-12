// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security.credentials;

import static org.vernac.saas.identity.security.credentials.CredentialInputException.Code.*;

import org.jspecify.annotations.Nullable;
import org.vernac.runtime.DomainChecks;

import java.text.Normalizer;

/** Internal normalization; transient Java strings cannot promise complete memory erasure. */
final class PasswordInput {
    private PasswordInput() {}

    static String normalize(@Nullable CharSequence input) {
        DomainChecks.requireNonNull(input, "Credential password");
        if (input.length() > 2_048) throw new CredentialInputException(PASSWORD_TOO_LONG);
        String raw = input.toString();
        int points = 0;
        for (int i = 0; i < raw.length(); i++, points++) {
            char unit = raw.charAt(i);
            if (Character.isHighSurrogate(unit)) {
                if (i + 1 >= raw.length() || !Character.isLowSurrogate(raw.charAt(i + 1))) {
                    throw new CredentialInputException(INVALID_PASSWORD_UNICODE);
                }
                i++;
            } else if (Character.isLowSurrogate(unit)) {
                throw new CredentialInputException(INVALID_PASSWORD_UNICODE);
            }
            if (points >= 1_024) throw new CredentialInputException(PASSWORD_TOO_LONG);
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFC);
        if (normalized.codePointCount(0, normalized.length()) > 128) {
            throw new CredentialInputException(PASSWORD_TOO_LONG);
        }
        return normalized;
    }

    static String forCreation(@Nullable CharSequence input) {
        String normalized = normalize(input);
        if (normalized.codePointCount(0, normalized.length()) < 15) {
            throw new CredentialInputException(PASSWORD_TOO_SHORT);
        }
        return normalized;
    }
}
