// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.jdbc;

import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.vernac.runtime.VernacTechnicalException;

/** Reject mismatched old/new storage artifacts before payload SQL, even for an owner connection. */
final class JdbcEventLayout {
    private JdbcEventLayout() {}
    private record Column(String name, String type, boolean required) {}
    private record Observed(boolean rls, boolean forced, Column column) {}
    private static final Set<Column> ORIGIN = Set.of(new Column("@tenantId", "uuid", false), new Column("@scopeKey", "text", true));

    static void verify(NamedParameterJdbcTemplate jdbc, List<JdbcMapping.Table> tables, boolean originSchema) {
        try {
            for (var table : tables) verify(jdbc, table.name(), originSchema);
        } catch (DataAccessException unavailable) {
            throw new VernacTechnicalException("Could not verify event storage schema layout. Check database access and migration state before using this adapter.");
        }
    }
    private static void verify(NamedParameterJdbcTemplate jdbc, String table, boolean originSchema) {
        var observed = jdbc.query("""
                SELECT c.relrowsecurity, c.relforcerowsecurity, a.attname,
                       format_type(a.atttypid, a.atttypmod), a.attnotnull
                FROM pg_class c LEFT JOIN pg_attribute a ON a.attrelid=c.oid AND NOT a.attisdropped
                    AND a.attname IN ('@tenantId', '@scopeKey') AND a.attnum>0
                WHERE c.oid=to_regclass(:table) AND c.relkind='r'
                """, Map.of("table", table), (rs,n) -> new Observed(rs.getBoolean(1), rs.getBoolean(2),
                        rs.getString(3) == null ? null : new Column(rs.getString(3), rs.getString(4), rs.getBoolean(5))));
        if (observed.isEmpty()) throw incompatible();
        Set<Column> columns = new HashSet<>();
        for (var row : observed) {
            if (row.column() != null) columns.add(row.column());
            if (originSchema && (!row.rls() || !row.forced())) throw incompatible();
        }
        if (originSchema ? !columns.equals(ORIGIN) : !columns.isEmpty()) throw incompatible();
    }
    private static VernacTechnicalException incompatible() {
        return new VernacTechnicalException("Event storage schema capability mismatch. Rebuild the application/modules and apply their reviewed event migrations before using this adapter.");
    }
}
