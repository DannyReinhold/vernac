// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.runtime;

import java.time.Instant;
import java.util.List;

public interface AggregateRoot<ID> {
    ID id();

    Instant createdAt();

    Instant updatedAt();

    long version();

    /**
     * Liefert alle gesammelten Domain Events zurück und leert den internen Puffer.
     */
    List<DomainEvent> pullDomainEvents();
}