// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.jdbc;

import org.jspecify.annotations.NullMarked;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.util.*;

/**
 * Developer-owned parameterized SELECT yielding an `id` UUID column. It selects identities, not
 * aggregate graphs. Outer ordering is explicit root identity order; SQL ORDER BY does not override
 * that contract. No tenant predicate is textually inserted into the supplied SQL.
 */
@NullMarked
public final class JdbcRootQuery {
    private final String sql;
    private final Map<String, ?> parameters;
    private final boolean descending;

    private JdbcRootQuery(String sql, Map<String, ?> parameters, boolean descending) {
        if (sql == null
                || sql.isBlank()
                || sql.length() > 65_536
                || parameters == null
                || parameters.size() > 64)
            throw new RepositoryExtensionException(RepositoryExtensionException.Code.INVALID_QUERY);
        var copy = new LinkedHashMap<String, Object>();
        long[] budget = {0};
        parameters.forEach(
                (name, value) -> {
                    if (name == null
                            || !name.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}")
                            || name.startsWith("__vernac"))
                        throw new RepositoryExtensionException(
                                RepositoryExtensionException.Code.INVALID_QUERY);
                    copy.put(name, freeze(value, budget));
                });
        this.sql = sql;
        this.parameters = Collections.unmodifiableMap(copy);
        this.descending = descending;
    }

    private static Object freeze(Object value, long[] budget) {
        if (value == null) {
            charge(budget, 1);
            return null;
        }
        if (value instanceof List<?> list) {
            if (list.size() > 1000 || list.stream().anyMatch(v -> v instanceof Collection<?>))
                throw invalid();
            var copy = new ArrayList<Object>();
            for (Object item : list) copy.add(freeze(item, budget));
            return Collections.unmodifiableList(copy);
        }
        Object scalar = scalar(value);
        charge(
                budget,
                64 + scalar.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        return scalar;
    }

    private static Object scalar(Object value) {
        if (value instanceof String text) {
            if (text.length() > 65536) throw invalid();
            return text;
        }
        if (value instanceof java.time.Instant instant)
            return instant.atOffset(java.time.ZoneOffset.UTC);
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime();
        if (value instanceof java.sql.Date date) return date.toLocalDate();
        if (value instanceof java.sql.Time time) return time.toLocalTime();
        if (value instanceof java.math.BigInteger integer) return new java.math.BigDecimal(integer);
        if (value instanceof UUID
                || value instanceof Boolean
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double
                || value instanceof java.math.BigDecimal
                || value instanceof java.time.LocalDate
                || value instanceof java.time.LocalTime
                || value instanceof java.time.LocalDateTime
                || value instanceof java.time.OffsetDateTime) return value;
        throw invalid();
    }

    private static void charge(long[] budget, long bytes) {
        budget[0] += bytes;
        if (budget[0] > 1_048_576) throw invalid();
    }

    private static RepositoryExtensionException invalid() {
        return new RepositoryExtensionException(RepositoryExtensionException.Code.INVALID_QUERY);
    }

    public static JdbcRootQuery of(String sql, Map<String, ?> parameters) {
        return new JdbcRootQuery(sql, parameters, false);
    }

    public JdbcRootQuery descending() {
        return new JdbcRootQuery(sql, parameters, true);
    }

    String condition() {
        return "\"id\" IN (SELECT \"id\" FROM (" + sql + ") AS \"__vernacExtensionIds\")";
    }

    boolean reverse() {
        return descending;
    }

    MapSqlParameterSource parameters() {
        return new MapSqlParameterSource(parameters);
    }

    @Override
    public String toString() {
        return "JdbcRootQuery[REDACTED]";
    }
}
