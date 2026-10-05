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
     * Returns all collected domain events and clears the internal buffer.
     */
    List<DomainEvent> pullDomainEvents();
}