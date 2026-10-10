// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.events;
import org.vernac.runtime.DomainEvent;
/** Generated synchronous receiver; identity is the qualified Vernac listener name. */
@org.jspecify.annotations.NullMarked
public interface EventHandler {
    String listenerId();
    Class<? extends DomainEvent> eventClass();
    void handle(DomainEvent event);
}
