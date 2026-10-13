// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.json.pagination;

import java.util.List;
import java.util.Optional;
import org.vernac.runtime.json.JsonValues;

/** Materialized immutable transport envelope; no JDBC query or internal request object. */
public record CursorPageDto<T>(List<T> items, int pageSize, Optional<String> nextCursor) {
    public CursorPageDto {
        items=JsonValues.copyList(items,"$.items");
        JsonPages.checkCursorPage(items, pageSize, nextCursor);
    }
    @Override public String toString() {
        return "CursorPageDto[items="+items+", pageSize="+pageSize+", nextCursor="+(nextCursor.isPresent()?"present":"absent")+"]";
    }
}
