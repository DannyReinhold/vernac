// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.pagination;

import java.util.Objects;
import java.util.Optional;

/** A first request or a continuation, with an independently adjustable positive size. */
public record CursorRequest(PageSize size, Optional<PageCursor> cursor) {
    public CursorRequest { Objects.requireNonNull(size, "size"); Objects.requireNonNull(cursor, "cursor"); }
    public static CursorRequest first(int size) { return first(PageSize.of(size)); }
    public static CursorRequest first(PageSize size) { return new CursorRequest(size, Optional.empty()); }
    public static CursorRequest after(PageCursor cursor, int size) { return after(cursor, PageSize.of(size)); }
    public static CursorRequest after(PageCursor cursor, PageSize size) { return new CursorRequest(size, Optional.of(cursor)); }
    public int pageSize() { return size.value(); }
}
