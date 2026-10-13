// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.pagination;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Opaque transport token; validity and query binding are checked by the cursor provider. */
public final class PageCursor {
    private final String value;
    private PageCursor(String value) {
        this.value = Objects.requireNonNull(value, "value");
        if (value.isBlank()) throw new InvalidCursorException();
    }
    public static PageCursor of(String value) { return new PageCursor(value); }
    /** The unchanged token, for deliberate transmission to a client. */
    public String value() { return value; }
    @Override public boolean equals(@Nullable Object other) { return other instanceof PageCursor cursor && value.equals(cursor.value); }
    @Override public int hashCode() { return value.hashCode(); }
    /** Do not accidentally copy token contents into logs. */
    @Override public String toString() { return "PageCursor[opaque]"; }
}
