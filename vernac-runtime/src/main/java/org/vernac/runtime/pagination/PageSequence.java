// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.pagination;

import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.vernac.runtime.DomainList;

/** Sequential lazy page traversal. Each iterator starts a new traversal, not a snapshot. */
public final class PageSequence<T, C extends DomainList<T>, P extends Page<T, C>> implements Iterable<P> {
    private final Iterable<P> pages;
    private PageSequence(Iterable<P> pages) { this.pages = pages; }
    static <Q, T, C extends DomainList<T>, P extends Page<T, C>> PageSequence<T, C, P> create(
            Q first, Function<Q, P> load, Function<P, Optional<Q>> advance) {
        Objects.requireNonNull(first, "first");
        return new PageSequence<>(() -> new Iterator<>() {
            private Optional<Q> next = Optional.of(first);
            @Override public boolean hasNext() { return next.isPresent(); }
            @Override public P next() {
                Q request = next.orElseThrow(NoSuchElementException::new);
                next = Optional.empty(); // A failed traversal does not retry implicitly.
                P result = PageChecks.required(load.apply(request), "Loaded page");
                PageChecks.matchesRequest(result, request);
                next = PageChecks.required(advance.apply(result), "Next request");
                return result;
            }
        });
    }
    @Override public Iterator<P> iterator() { return pages.iterator(); }
    /** Flatten sequentially, retaining page and entry order without collecting all rows. */
    public Iterable<T> items() {
        return () -> new Iterator<>() {
            private final Iterator<P> source = pages.iterator();
            private Iterator<T> current = Collections.emptyIterator();
            @Override public boolean hasNext() {
                while (!current.hasNext() && source.hasNext()) current = source.next().items().iterator();
                return current.hasNext();
            }
            @Override public T next() {
                if (!hasNext()) throw new NoSuchElementException();
                return current.next();
            }
        };
    }
}
