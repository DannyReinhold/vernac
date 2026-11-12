// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security.credentials;

/**
 * Technical policy/storage failure, not incorrect credentials. No secret-bearing cause is retained.
 */
public final class CredentialConfigurationException extends IllegalStateException {
    public enum Code {
        BLOCKLIST_INVALID,
        STORED_CREDENTIAL_INVALID,
        ENCODER_UNAVAILABLE
    }

    private final Code code;

    CredentialConfigurationException(Code code) {
        super("Credential processing unavailable: " + code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
