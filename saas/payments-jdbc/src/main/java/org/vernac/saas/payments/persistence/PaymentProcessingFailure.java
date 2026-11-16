// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.payments.persistence;
public final class PaymentProcessingFailure extends RuntimeException {
    public enum Code { ACCESS_DENIED, CONFIGURATION, INVALID_STATE, UNVERIFIED_RESOURCE, CONFLICT, UNAVAILABLE }
    private final Code code;
    public PaymentProcessingFailure(Code code) {
        super("Payment processing failed: " + java.util.Objects.requireNonNull(code), null, false, false); this.code = code;
    }
    public Code code() { return code; }
}
