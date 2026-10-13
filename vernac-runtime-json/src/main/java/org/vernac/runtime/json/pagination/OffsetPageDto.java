// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.json.pagination;

import java.util.List;
import java.util.Optional;
import org.vernac.runtime.json.JsonValues;

/** Materialized immutable transport envelope; no JDBC query or internal request object. */
public record OffsetPageDto<T>(List<T> items, long offset, int pageSize, Optional<Long> pageNumber, boolean hasNext) {
    public OffsetPageDto {
        items=JsonValues.copyList(items,"$.items");
        JsonPages.checkOffsetPage(items, offset, pageSize, pageNumber, hasNext);
    }
}
