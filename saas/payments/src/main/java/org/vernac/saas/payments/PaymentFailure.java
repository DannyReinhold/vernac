// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.payments;

public final class PaymentFailure extends RuntimeException {
    public enum Code { CONFIGURATION, UNAVAILABLE, OUTCOME_UNKNOWN, INVALID_NOTIFICATION, INVALID_PROVIDER_RESPONSE, OPERATION_CONFLICT, REJECTED }
    private final Code code;
    public PaymentFailure(Code code) {
        super("Payment operation failed: " + java.util.Objects.requireNonNull(code), null, false, false);
        this.code = code;
    }
    public Code code() { return code; }
}
