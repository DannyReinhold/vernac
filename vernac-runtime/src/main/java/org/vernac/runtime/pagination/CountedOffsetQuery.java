// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.pagination;

import org.vernac.runtime.DomainList;

/** Offset loader with a guaranteed exact count for every materialized page. */
@FunctionalInterface
public interface CountedOffsetQuery<T, C extends DomainList<T>> {
    CountedOffsetPage<T, C> page(OffsetRequest request);
    default PageSequence<T, C, CountedOffsetPage<T, C>> pages(OffsetRequest first) {
        return PageSequence.create(first, this::page, CountedOffsetPage::nextRequest);
    }
}
