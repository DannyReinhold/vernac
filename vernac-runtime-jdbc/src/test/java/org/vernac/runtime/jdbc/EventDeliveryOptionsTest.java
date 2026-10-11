// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.jdbc;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class EventDeliveryOptionsTest {
    @Test void backoffDoublesAndCapsWithoutOverflow() {
        var options=EventDeliveryOptions.defaults();
        assertEquals(Duration.ofSeconds(1),options.backoff(1));
        assertEquals(Duration.ofSeconds(2),options.backoff(2));
        assertEquals(Duration.ofMinutes(5),options.backoff(Integer.MAX_VALUE));
        var large=new EventDeliveryOptions(2,Duration.ofSeconds(1),Duration.ofMillis(Long.MAX_VALUE/2+1),Duration.ofMillis(Long.MAX_VALUE));
        assertEquals(Duration.ofMillis(Long.MAX_VALUE),large.backoff(2));
    }
    @Test void invalidLimitsFailBeforePolling() {
        assertThrows(IllegalArgumentException.class,()->new EventDeliveryOptions(0,Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(2)));
        assertThrows(IllegalArgumentException.class,()->new EventDeliveryOptions(2,Duration.ZERO,Duration.ofSeconds(1),Duration.ofSeconds(2)));
        assertThrows(IllegalArgumentException.class,()->new EventDeliveryOptions(2,Duration.ofSeconds(1),Duration.ofSeconds(2),Duration.ofSeconds(1)));
    }
}
