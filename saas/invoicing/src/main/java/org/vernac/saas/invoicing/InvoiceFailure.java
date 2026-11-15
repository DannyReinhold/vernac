// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.invoicing;

public final class InvoiceFailure extends RuntimeException {
    public enum Code {
        CONFIGURATION,
        ACCESS_DENIED,
        BILLING_PROFILE_REQUIRED,
        INVALID_STATE,
        CONFLICT,
        UNAVAILABLE
    }

    private final Code code;

    public InvoiceFailure(Code code) {
        super(
                "Invoice processing failed: " + java.util.Objects.requireNonNull(code),
                null,
                false,
                false);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
