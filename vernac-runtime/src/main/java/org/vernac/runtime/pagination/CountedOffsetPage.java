// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.pagination;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.vernac.runtime.DomainList;

/** A completed offset page whose entries and exact count describe one data snapshot. */
public record CountedOffsetPage<T, C extends DomainList<T>>(C items, OffsetRequest request, long totalElements)
        implements OffsetResult<T, C> {
    public CountedOffsetPage {
        Objects.requireNonNull(items, "items"); Objects.requireNonNull(request, "request");
        PageChecks.check(totalElements >= 0, "Total count must not be negative");
        int count = PageChecks.size(items, request.pageSize());
        long remaining = request.offset() >= totalElements ? 0 : totalElements - request.offset();
        PageChecks.check(count == Math.min(request.pageSize(), remaining), "Page entries and total count disagree");
    }
    public static <T, C extends DomainList<T>> CountedOffsetPage<T, C> of(C items, OffsetRequest request, long totalElements) {
        return new CountedOffsetPage<>(items, request, totalElements);
    }
    @Override public boolean hasNext() {
        return request.offset() < totalElements && totalElements - request.offset() > request.pageSize();
    }
    /** Zero for no matches; ceiling division without overflowing a long. */
    public long totalPages() {
        return totalElements / pageSize() + (totalElements % pageSize() == 0 ? 0 : 1);
    }
    @Override public <R, D extends DomainList<R>> CountedOffsetPage<R, D> mapItems(
            Function<List<R>, D> factory, Function<? super T, ? extends R> mapper) {
        return new CountedOffsetPage<>(PageChecks.map(items, factory, mapper), request, totalElements);
    }
}
