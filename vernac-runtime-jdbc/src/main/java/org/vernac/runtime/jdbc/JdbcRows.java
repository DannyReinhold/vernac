// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.jdbc;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.jspecify.annotations.*;
import org.springframework.jdbc.core.namedparam.*;
import static org.vernac.runtime.jdbc.JdbcMapping.*;
/** Shared SQL component conversion for aggregate and immutable event storage. */
@NullMarked
final class JdbcRows {
    private JdbcRows() { }
    static String q(String identifier) { return "\""+identifier.replace("\"","\"\"")+"\""; }
    static Map<String,@Nullable Object> readRow(Table table,ResultSet rs) throws SQLException {
        Map<String,@Nullable Object> row=new LinkedHashMap<>();
        for(Column c:table.columns()) {
            Object value=switch(c.sqlType()) {
                case "DATE" -> rs.getObject(c.name(),LocalDate.class);
                case "TIME(6)" -> rs.getObject(c.name(),LocalTime.class);
                case "TIMESTAMP(6)" -> rs.getObject(c.name(),LocalDateTime.class);
                case "TIMESTAMPTZ(6)" -> rs.getObject(c.name(),OffsetDateTime.class);
                default -> rs.getObject(c.name());
            };
            row.put(c.name(),value);
        }
        return row;
    }
    static MapSqlParameterSource params(Table t,Map<String,@Nullable Object> row) {
        var p=new MapSqlParameterSource();
        for(int i=0;i<t.columns().size();i++) {
            var c=t.columns().get(i); Object value=row.get(c.name());
            // Non-null JDBC 4.2 values retain their native Java time type. NULL needs a declared SQL type.
            if(value==null) p.addValue("p"+i,null,jdbcType(c.sqlType())); else p.addValue("p"+i,value);
        }
        return p;
    }
    static int jdbcType(String type) {
        return switch(type) {
            case "UUID" -> Types.OTHER; case "TEXT" -> Types.VARCHAR; case "BOOLEAN" -> Types.BOOLEAN;
            case "SMALLINT" -> Types.SMALLINT; case "INTEGER" -> Types.INTEGER; case "BIGINT" -> Types.BIGINT;
            case "NUMERIC" -> Types.NUMERIC; case "REAL" -> Types.REAL; case "DOUBLE PRECISION" -> Types.DOUBLE;
            case "DATE" -> Types.DATE; case "TIME(6)" -> Types.TIME; case "TIMESTAMP(6)" -> Types.TIMESTAMP;
            case "TIMESTAMPTZ(6)" -> Types.TIMESTAMP_WITH_TIMEZONE;
            default -> throw new IllegalArgumentException("Unsupported JDBC column type "+type);
        };
    }
}
