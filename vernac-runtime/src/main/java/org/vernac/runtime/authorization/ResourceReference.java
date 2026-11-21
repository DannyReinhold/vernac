// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.authorization;

import java.util.Objects;
import org.jspecify.annotations.NullMarked;

/** Nominal, required ID/VO parameter; not an ownership claim or tenant selection. */
@NullMarked
public record ResourceReference<T>(Class<T> type, T value) {
    public ResourceReference {
        Objects.requireNonNull(type); Objects.requireNonNull(value);
        if (!type.isInstance(value)) throw new IllegalArgumentException("Resource type does not match its value");
    }
    public static <T> ResourceReference<T> of(Class<T> type, T value) { return new ResourceReference<>(type, value); }
    @Override public String toString() { return "ResourceReference[redacted]"; }
}
