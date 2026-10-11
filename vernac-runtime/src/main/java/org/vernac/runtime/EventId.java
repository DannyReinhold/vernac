// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime;
import java.util.UUID;
import org.jspecify.annotations.*;
@NullMarked
public final class EventId {
    private final UUID value;
    private EventId(UUID value) { this.value = DomainChecks.requireNonNull(value, "EventId.value"); }
    public static EventId create() { return of(UUID.randomUUID()); }
    public static EventId of(UUID value) { return new EventId(value); }
    public static EventId of(String value) {
        DomainChecks.requireNonNull(value, "EventId.value");
        try { return of(UUID.fromString(value)); }
        catch (IllegalArgumentException cause) { throw new DomainValidationException("EventId.value must be a valid UUID", cause); }
    }
    public UUID value() { return value; }
    public String asString() { return value.toString(); }
    @Override public boolean equals(@Nullable Object other) { return other instanceof EventId id && value.equals(id.value); }
    @Override public int hashCode() { return value.hashCode(); }
    @Override public String toString() { return "EventId[" + value + "]"; }
}
