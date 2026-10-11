// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.ports;

import java.util.*;
import org.jspecify.annotations.NullMarked;

/** Immutable diagnostic identity for one adapter operation, independent of transport. */
@NullMarked
public record AdapterInvocation(UUID invocationId, String port, String adapter,
        String operation, Map<String,String> details) {
    public AdapterInvocation {
        Objects.requireNonNull(invocationId);
        Objects.requireNonNull(port);
        Objects.requireNonNull(adapter);
        Objects.requireNonNull(operation);
        details = Map.copyOf(details);
    }
    public static AdapterInvocation create(String port, String adapter, String operation) {
        return new AdapterInvocation(UUID.randomUUID(),port,adapter,operation,Map.of());
    }
    /** Adds an explicitly selected diagnostic value to a new context. */
    public AdapterInvocation withDetail(String name, String value) {
        var copy = new LinkedHashMap<>(details);
        copy.put(Objects.requireNonNull(name),Objects.requireNonNull(value));
        return new AdapterInvocation(invocationId,port,adapter,operation,copy);
    }
}
