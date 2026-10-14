// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.jdbc;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;

/** Compiler-resolved ordering components; boundary equality follows SQL ordering, not domain equality. */
@NullMarked
public record JdbcCursorSort(String scalar, List<String> columns, boolean descending) {
    public JdbcCursorSort {
        Objects.requireNonNull(scalar); columns=List.copyOf(columns);
        if(columns.isEmpty()) throw new IllegalArgumentException("A cursor sort requires storage columns");
    }
    public List<String> orderedColumns() { return scalar.equals("BigDecimal") ? columns.subList(0,1) : columns; }
    public String sql() { return ScalarQuery.order(scalar,columns,descending); }
}
