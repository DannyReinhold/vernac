// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.pagination;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.vernac.runtime.DomainList;

/** A completed offset page without a total count. */
public record OffsetPage<T, C extends DomainList<T>>(C items, OffsetRequest request, boolean hasNext)
        implements OffsetResult<T, C> {
    public OffsetPage {
        Objects.requireNonNull(items, "items"); Objects.requireNonNull(request, "request");
        int count = PageChecks.size(items, request.pageSize());
        PageChecks.check(!hasNext || count == request.pageSize(), "A continuation requires a full page");
    }
    public static <T, C extends DomainList<T>> OffsetPage<T, C> of(C items, OffsetRequest request, boolean hasNext) {
        return new OffsetPage<>(items, request, hasNext);
    }
    @Override public <R, D extends DomainList<R>> OffsetPage<R, D> mapItems(
            Function<List<R>, D> factory, Function<? super T, ? extends R> mapper) {
        return new OffsetPage<>(PageChecks.map(items, factory, mapper), request, hasNext);
    }
}
