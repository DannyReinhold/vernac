// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.ports;

import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.vernac.runtime.VernacTechnicalException;

/** Technical integration failure whose responsibility has not been assigned. */
@NullMarked
public class PortInvocationException extends VernacTechnicalException implements PortFailure {
    private final AdapterInvocation invocation;
    private final IntegrationCategory category;
    private final Map<String, String> transportDetails;

    public PortInvocationException(AdapterInvocation invocation, IntegrationCategory category, String message) {
        this(invocation, category, message, Map.of(), null);
    }

    public PortInvocationException(AdapterInvocation invocation, IntegrationCategory category,
            String message, Map<String, String> transportDetails, @Nullable Throwable cause) {
        super(Objects.requireNonNull(message), cause);
        this.invocation = Objects.requireNonNull(invocation);
        this.category = Objects.requireNonNull(category);
        this.transportDetails = Map.copyOf(transportDetails);
    }

    @Override public final AdapterInvocation invocation() { return invocation; }
    public final IntegrationCategory category() { return category; }
    @Override public final Map<String, String> transportDetails() { return transportDetails; }
}
