// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.tenancy;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Technical isolation identity; not a user, organization or subscription identity. */
@NullMarked
public final class TenantId {
    private final UUID value;

    private TenantId(UUID value) {
        this.value = Objects.requireNonNull(value, "TenantId requires a UUID");
    }

    public static TenantId of(UUID value) { return new TenantId(value); }

    public static TenantId of(String value) {
        Objects.requireNonNull(value, "TenantId requires a UUID");
        try {
            return of(UUID.fromString(value));
        } catch (IllegalArgumentException invalid) {
            // UUID parser exceptions contain their input; do not retain that sensitive message.
            throw new IllegalArgumentException("TenantId requires a UUID");
        }
    }

    public UUID value() { return value; }
    public String asString() { return value.toString(); }

    @Override public boolean equals(@Nullable Object other) {
        return other instanceof TenantId id && value.equals(id.value);
    }
    @Override public int hashCode() { return value.hashCode(); }
    @Override public String toString() { return "TenantId[redacted]"; }
}
