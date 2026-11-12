// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security.credentials;

import static org.vernac.saas.identity.security.credentials.CredentialConfigurationException.Code.*;

import org.jspecify.annotations.Nullable;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.vernac.runtime.DomainChecks;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Explicit bounded credential operations, not an AuthenticationProvider. Callers must implement
 * admission limits before invoking these expensive operations. No Spring bean is registered.
 */
public final class CredentialPasswords {
    public static final String ENCODING_ID = "vernac-argon2id-v1";
    private static final Pattern STORED =
            Pattern.compile(
                    "\\{"
                            + ENCODING_ID
                            + "}\\$argon2id\\$v=19\\$m=([0-9]{1,5}),t=([0-9]),p=1\\$([A-Za-z0-9+/]{22})\\$([A-Za-z0-9+/]{43})");
    private final OfflinePasswordBlocklist blocklist;
    private final PasswordEncoder encoder;
    private final String dummy;

    public CredentialPasswords(OfflinePasswordBlocklist blocklist) {
        this.blocklist = DomainChecks.requireNonNull(blocklist, "Credential password blocklist");
        try {
            encoder =
                    new DelegatingPasswordEncoder(
                            ENCODING_ID,
                            Map.of(ENCODING_ID, new Argon2PasswordEncoder(16, 32, 1, 19_456, 2)));
            dummy = encoder.encode("Non-secret bounded dummy verification workload");
            validateStored(dummy);
        } catch (RuntimeException failed) {
            throw new CredentialConfigurationException(ENCODER_UNAVAILABLE);
        }
    }

    /** Enrollment/change only: applies current creation policy and the loaded corpus. */
    public String encode(@Nullable CharSequence password) {
        String normalized = PasswordInput.forCreation(password);
        if (blocklist.contains(normalized)) {
            throw new CredentialInputException(CredentialInputException.Code.PASSWORD_BLOCKED);
        }
        try {
            String encoded = encoder.encode(normalized);
            validateStored(encoded);
            return encoded;
        } catch (RuntimeException failed) {
            throw new CredentialConfigurationException(ENCODER_UNAVAILABLE);
        }
    }

    /**
     * Login: does not reapply the current blocklist/minimum length to an existing credential.
     * Invalid/oversized Unicode input returns false; missing required arguments fail explicitly.
     * Unknown/corrupt stored formats fail technically, never through a no-op fallback.
     */
    public boolean matches(@Nullable CharSequence password, String stored) {
        DomainChecks.requireNonNull(password, "Credential password");
        validateStored(stored);
        String normalized;
        try {
            normalized = PasswordInput.normalize(password);
        } catch (CredentialInputException invalidInput) {
            return false;
        }
        try {
            return encoder.matches(normalized, stored);
        } catch (RuntimeException failed) {
            throw new CredentialConfigurationException(ENCODER_UNAVAILABLE);
        }
    }

    /**
     * Comparable work for an admitted unknown-subject login; cannot return an authentication grant.
     */
    public void performDummyVerification(@Nullable CharSequence password) {
        matches(password, dummy);
    }

    private static void validateStored(@Nullable String stored) {
        if (stored == null || stored.length() > 256) throw invalidStored();
        var match = STORED.matcher(stored);
        if (!match.matches()) throw invalidStored();
        int memory = Integer.parseInt(match.group(1)),
                iterations = Integer.parseInt(match.group(2));
        // Bound untrusted PHC work before invoking the underlying decoder/KDF.
        if (memory < 19_456
                || memory > 65_536
                || iterations < 2
                || iterations > 4
                || !match.group(1).equals(Integer.toString(memory))) throw invalidStored();
        var decoder = Base64.getDecoder();
        var encoder = Base64.getEncoder().withoutPadding();
        for (int group : new int[] {3, 4}) {
            String value = match.group(group);
            byte[] bytes = decoder.decode(value);
            if (!encoder.encodeToString(bytes).equals(value)) throw invalidStored();
        }
    }

    private static CredentialConfigurationException invalidStored() {
        return new CredentialConfigurationException(STORED_CREDENTIAL_INVALID);
    }

    @Override
    public String toString() {
        return "CredentialPasswords[redacted]";
    }
}
