// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.subscriptions;

/** Shared assertion for generated domain behavior, without transport/persistence dependencies. */
public final class SubscriptionRules {
    private SubscriptionRules() {}
    public static boolean validPrice(long amount) { return amount >= 0 && amount <= 9_000_000_000_000_000L; }
    public static void require(boolean condition, SubscriptionFailure.Code code) {
        if (!condition) throw new SubscriptionFailure(code);
    }
}
