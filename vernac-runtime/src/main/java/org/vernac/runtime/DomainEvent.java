// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime;
import java.time.Instant;
import org.jspecify.annotations.NullMarked;
@NullMarked
public interface DomainEvent {
    EventId eventId();
    Instant occurredAt();
}
