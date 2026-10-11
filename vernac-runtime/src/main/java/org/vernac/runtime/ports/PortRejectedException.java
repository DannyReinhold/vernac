// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.ports;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.vernac.runtime.VernacDomainException;

/** Explicit business rejection. The enum type is part of the code's identity. */
@NullMarked
public final class PortRejectedException extends VernacDomainException implements PortFailure {
    private final AdapterInvocation invocation;
    private final Enum<?> code;
    private final Map<String, String> transportDetails;

    public PortRejectedException(AdapterInvocation invocation, Enum<?> code, String message) {
        this(invocation, code, message, Map.of(), null);
    }

    public PortRejectedException(AdapterInvocation invocation, Enum<?> code, String message,
            Map<String, String> transportDetails, @Nullable Throwable cause) {
        super(Objects.requireNonNull(message), cause);
        this.invocation = Objects.requireNonNull(invocation);
        this.code = Objects.requireNonNull(code);
        this.transportDetails = Map.copyOf(transportDetails);
    }

    @Override public AdapterInvocation invocation() { return invocation; }
    public Enum<?> code() { return code; }
    public <E extends Enum<E>> Optional<E> code(Class<E> type) {
        Objects.requireNonNull(type);
        return type.isInstance(code) ? Optional.of(type.cast(code)) : Optional.empty();
    }
    @Override public Map<String, String> transportDetails() { return transportDetails; }
}
