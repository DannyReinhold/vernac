// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.pagination;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.vernac.runtime.DomainList;

/** A completed cursor page; a missing continuation marks the end of this traversal. */
public record CursorPage<T, C extends DomainList<T>>(C items, CursorRequest request, Optional<PageCursor> nextCursor)
        implements Page<T, C> {
    public CursorPage {
        Objects.requireNonNull(items, "items"); Objects.requireNonNull(request, "request");
        Objects.requireNonNull(nextCursor, "nextCursor");
        int count = PageChecks.size(items, request.pageSize());
        PageChecks.check(nextCursor.isEmpty() || count == request.pageSize(), "A continuation requires a full page");
        PageChecks.check(nextCursor.isEmpty() || !nextCursor.equals(request.cursor()), "Cursor did not advance");
    }
    public static <T, C extends DomainList<T>> CursorPage<T, C> of(C items, CursorRequest request, Optional<PageCursor> nextCursor) {
        return new CursorPage<>(items, request, nextCursor);
    }
    public int pageSize() { return request.pageSize(); }
    public Optional<CursorRequest> nextRequest() {
        return nextCursor.map(cursor -> CursorRequest.after(cursor, request.size()));
    }
    @Override public <R, D extends DomainList<R>> CursorPage<R, D> mapItems(
            Function<List<R>, D> factory, Function<? super T, ? extends R> mapper) {
        return new CursorPage<>(PageChecks.map(items, factory, mapper), request, nextCursor);
    }
}
