// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security.credentials;

import org.vernac.runtime.DomainValidationException;

/** Safe field-level rejection. Never retains the rejected value or an underlying exception. */
public final class CredentialInputException extends DomainValidationException {
    public enum Code {
        INVALID_ADDRESS,
        UNSUPPORTED_ADDRESS,
        INVALID_PASSWORD_UNICODE,
        PASSWORD_TOO_SHORT,
        PASSWORD_TOO_LONG,
        PASSWORD_BLOCKED
    }

    private final Code code;

    CredentialInputException(Code code) {
        super(
                switch (code) {
                    case INVALID_ADDRESS -> "Enter an email address in the supported format.";
                    case UNSUPPORTED_ADDRESS ->
                            "Use an ASCII email address, with an ASCII/Punycode domain.";
                    case INVALID_PASSWORD_UNICODE -> "The password contains invalid Unicode.";
                    case PASSWORD_TOO_SHORT -> "Use at least 15 characters for the password.";
                    case PASSWORD_TOO_LONG -> "The password exceeds the supported length.";
                    case PASSWORD_BLOCKED ->
                            "Choose a password that is not commonly used or compromised.";
                });
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
