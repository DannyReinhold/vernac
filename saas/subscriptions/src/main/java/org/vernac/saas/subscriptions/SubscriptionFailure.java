// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.subscriptions;

/** Stable domain failure codes. Never includes account IDs, offers or provider data. */
public final class SubscriptionFailure extends RuntimeException {
    public enum Code { INVALID_TRANSITION, SETTLEMENT_MISMATCH, INVALID_CATALOG, CONFIGURATION, UNAVAILABLE, NOT_FOUND, SETTLEMENT_CONFLICT, NOT_DUE }
    private final Code code;
    public SubscriptionFailure(Code code) {
        super("Subscription operation failed: " + java.util.Objects.requireNonNull(code), null, false, false);
        this.code = code;
    }
    public Code code() { return code; }
}
