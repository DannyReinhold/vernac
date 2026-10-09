// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Required domain values, with a consistent exception and field-qualified diagnostic. */
@NullMarked
public final class DomainChecks {
    private DomainChecks() { }
    public static <T> T requireNonNull(@Nullable T value, String field) {
        if (value == null) throw new DomainValidationException(field + " must not be null");
        return value;
    }
}
