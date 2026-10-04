package org.vernac.runtime.outbox;

import org.vernac.runtime.DomainEvent;

import java.time.Instant;
import java.util.UUID;

public interface OutboxDomainEvent extends DomainEvent {

    UUID eventId();

    Instant occurredOn();

    String eventType();
}