// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.events;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.vernac.runtime.DomainEvent;
import org.vernac.runtime.EventId;
/** Generated relational adapter, discovered through the same scoped handler provider. */
@NullMarked
public interface EventStorage extends EventHandler {
    String eventType();
    void persist(DomainEvent event, List<String> listenerIds);
    DomainEvent load(EventId id);
    @Override default void handle(DomainEvent event) {
        throw new IllegalStateException("Event storage is not a synchronous listener");
    }
}
