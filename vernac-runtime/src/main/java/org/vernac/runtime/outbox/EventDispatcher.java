// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.runtime.outbox;

import org.vernac.runtime.DomainEvent;

import java.util.List;

/**
 * Dispatcher for domain events. Dispatches in-memory events to the Spring ApplicationContext
 * and routes outbox events to persistent storage within the active transaction.
 */
public interface EventDispatcher {

    /**
     * Dispatches all domain events pulled from an aggregate root.
     *
     * @param aggregateType the simple name of the aggregate
     * @param aggregateId   the string representation of the aggregate ID
     * @param events        the list of domain events to dispatch
     */
    void dispatch(String aggregateType, String aggregateId, List<DomainEvent> events);
}