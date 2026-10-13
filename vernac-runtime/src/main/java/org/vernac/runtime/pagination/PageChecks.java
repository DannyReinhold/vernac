// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.pagination;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.vernac.runtime.DomainList;

final class PageChecks {
    private PageChecks() { }
    static void check(boolean condition, String message) {
        if (!condition) throw new PaginationContractException(message);
    }
    static <T> T required(@Nullable T value, String description) {
        if (value == null) throw new PaginationContractException(description + " must not be null");
        return value;
    }
    static void matchesRequest(Page<?, ?> page, Object request) {
        Object actual = switch (page) {
            case OffsetResult<?, ?> offset -> offset.request();
            case CursorPage<?, ?> cursor -> cursor.request();
        };
        check(actual.equals(request), "Loader returned a page for a different request");
    }
    static <T> int size(DomainList<T> items, int maximum) {
        int count = required(items.asList(), "Page items view").size();
        check(count <= maximum, "Page contains more items than requested");
        for (var item : items) required(item, "Page item");
        return count;
    }
    static <T, R, D extends DomainList<R>> D map(DomainList<T> items,
            Function<List<R>, D> factory, Function<? super T, ? extends R> mapper) {
        Objects.requireNonNull(factory, "factory"); Objects.requireNonNull(mapper, "mapper");
        List<R> values = items.asList().stream().<R>map(item -> required(mapper.apply(item), "Mapped item")).toList();
        D result = required(factory.apply(values), "Mapped collection");
        List<R> actual = required(result.asList(), "Mapped collection view");
        check(actual.size() == values.size(), "Mapping factory changed the number of entries");
        for (int i = 0; i < values.size(); i++)
            check(actual.get(i) == values.get(i), "Mapping factory changed entry order or replaced entries");
        return result;
    }
}
