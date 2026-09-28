package org.vernac.runtime;

import java.time.Instant;
import java.util.UUID;

public interface DomainEvent {
    UUID eventId();

    Instant occurredOn();

    String eventType();
}