// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.subscriptions;

import java.time.*;
import java.util.Objects;
import org.vernac.saas.subscriptions.domain.BillingInterval;

/** Explicit UTC calendar policy. A period begins at the previous end; no fixed 30-day months. */
public final class BillingCalendar {
    private BillingCalendar() {}
    public static Instant freeEnd() { return Instant.parse("9999-01-01T00:00:00Z"); }
    public static Instant end(Instant start, BillingInterval interval) {
        Objects.requireNonNull(start); Objects.requireNonNull(interval);
        try {
            var utc = start.atZone(ZoneOffset.UTC);
            return switch (interval) {
                case MONTHLY -> utc.plusMonths(1).toInstant();
                case YEARLY -> utc.plusYears(1).toInstant();
            };
        } catch (DateTimeException | ArithmeticException invalid) {
            throw new SubscriptionFailure(SubscriptionFailure.Code.INVALID_TRANSITION);
        }
    }
}
