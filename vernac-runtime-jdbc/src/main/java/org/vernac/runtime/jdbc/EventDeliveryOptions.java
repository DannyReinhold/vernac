// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.jdbc;

import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;

/** Operational limits, independent of domain event declarations. */
@NullMarked
public record EventDeliveryOptions(int maxAttempts, Duration lease, Duration initialBackoff, Duration maxBackoff) {
    public EventDeliveryOptions {
        if(maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        for(Duration value : new Duration[]{lease, initialBackoff, maxBackoff}) {
            Objects.requireNonNull(value);
            if(value.isNegative() || value.toMillis() < 1) throw new IllegalArgumentException("Durations must be at least one millisecond");
        }
        if(maxBackoff.compareTo(initialBackoff) < 0) throw new IllegalArgumentException("maxBackoff must not be below initialBackoff");
    }
    public static EventDeliveryOptions defaults() {
        return new EventDeliveryOptions(5, Duration.ofMinutes(5), Duration.ofSeconds(1), Duration.ofMinutes(5));
    }
    public Duration backoff(int attempt) {
        long value=initialBackoff.toMillis(), limit=maxBackoff.toMillis();
        for(int i=1;i<attempt && value<limit;i++) value=value>limit/2?limit:value*2;
        return Duration.ofMillis(Math.min(value,limit));
    }
}
