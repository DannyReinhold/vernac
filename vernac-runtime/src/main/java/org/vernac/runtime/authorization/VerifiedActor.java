// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.authorization;

import java.util.Objects;
import org.jspecify.annotations.NullMarked;

/** Trusted subject reference, never credentials or an implicit grant. */
@NullMarked
public record VerifiedActor(String realm, String subject, Kind kind) {
    public enum Kind { HUMAN, TECHNICAL }
    public VerifiedActor {
        if (Objects.requireNonNull(realm).isBlank() || Objects.requireNonNull(subject).isBlank())
            throw new IllegalArgumentException("Actor realm and subject are required");
        Objects.requireNonNull(kind);
    }
    @Override public String toString() { return "VerifiedActor[redacted]"; }
}
