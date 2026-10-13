// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.pagination;

import org.vernac.runtime.DomainList;

/** Cursor loader; token interpretation belongs to its query-specific implementation. */
@FunctionalInterface
public interface CursorQuery<T, C extends DomainList<T>> {
    CursorPage<T, C> page(CursorRequest request);
    default PageSequence<T, C, CursorPage<T, C>> pages(CursorRequest first) {
        return PageSequence.create(first, this::page, CursorPage::nextRequest);
    }
}
