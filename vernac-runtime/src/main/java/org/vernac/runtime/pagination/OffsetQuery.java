// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.pagination;

import org.vernac.runtime.DomainList;

/** Request-to-page loader; generated JDBC query objects will implement this boundary. */
@FunctionalInterface
public interface OffsetQuery<T, C extends DomainList<T>> {
    OffsetPage<T, C> page(OffsetRequest request);
    default PageSequence<T, C, OffsetPage<T, C>> pages(OffsetRequest first) {
        return PageSequence.create(first, this::page, OffsetPage::nextRequest);
    }
}
