// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.pagination;

import java.util.List;
import java.util.function.Function;
import org.vernac.runtime.DomainList;

/** A fully materialized page; no database operation is deferred by this value. */
public sealed interface Page<T, C extends DomainList<T>> permits OffsetResult, CursorPage {
    C items();
    /** Map the entries eagerly through an explicit named-list factory, preserving metadata. */
    <R, D extends DomainList<R>> Page<R, D> mapItems(
            Function<List<R>, D> factory, Function<? super T, ? extends R> mapper);
}
