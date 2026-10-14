// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.pagination;

import java.util.Optional;
import java.util.OptionalLong;
import org.vernac.runtime.DomainList;

/** Shared navigation for offset pages with and without an exact count. */
public sealed interface OffsetResult<T, C extends DomainList<T>> extends Page<T, C>
        permits OffsetPage, CountedOffsetPage {
    OffsetRequest request();
    boolean hasNext();
    default long offset() { return request().offset(); }
    default int pageSize() { return request().pageSize(); }
    default OptionalLong pageNumber() { return request().pageNumber(); }
    default Optional<OffsetRequest> nextRequest() {
        return hasNext() ? Optional.of(request().next()) : Optional.empty();
    }
}
