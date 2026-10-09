// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.persistence;

import java.util.List;

/** Storage layout only. Runtime codecs must honor these explicit component contracts. */
public final class ScalarMappings {
    private ScalarMappings() { }
    public record Part(String component, String sqlType, String check) { }
    private static Part p(String type) { return new Part("", type, ""); }
    private static Part p(String name, String type) { return new Part(name, type, ""); }
    private static Part range(String name, String type, String check) { return new Part(name, type, check); }
    public static List<Part> of(String type) {
        return switch (type) {
            case "boolean", "Boolean" -> List.of(p("BOOLEAN"));
            case "byte", "Byte" -> List.of(range("", "SMALLINT", "%s BETWEEN -128 AND 127"));
            case "short", "Short" -> List.of(p("SMALLINT"));
            case "int", "Integer", "Year" -> List.of(p("INTEGER"));
            case "long", "Long" -> List.of(p("BIGINT"));
            case "float", "Float" -> List.of(p("REAL"));
            case "double", "Double" -> List.of(p("DOUBLE PRECISION"));
            case "char", "Character" -> List.of(range("", "INTEGER", "%s BETWEEN 0 AND 65535"));
            case "String", "Currency", "ZoneId" -> List.of(p("TEXT"));
            case "UUID" -> List.of(p("UUID"));
            case "BigInteger" -> List.of(range("", "NUMERIC", "%1$s = trunc(%1$s) AND %1$s NOT IN ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)"));
            case "BigDecimal" -> List.of(range("", "NUMERIC", "%s NOT IN ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)"), p("scale", "INTEGER"));
            case "LocalDate" -> List.of(p("DATE"));
            case "LocalTime" -> List.of(p("TIME(6)"), remainder());
            case "LocalDateTime" -> List.of(p("TIMESTAMP(6)"), remainder());
            case "Instant" -> List.of(p("TIMESTAMPTZ(6)"), remainder());
            case "OffsetDateTime" -> List.of(p("TIMESTAMPTZ(6)"), remainder(), offset());
            case "ZonedDateTime" -> List.of(p("TIMESTAMP(6)"), remainder(), offset(), p("zone", "TEXT"));
            case "OffsetTime" -> List.of(p("TIME(6)"), remainder(), offset());
            case "Duration" -> List.of(p("BIGINT"), range("nano", "INTEGER", "%s BETWEEN 0 AND 999999999"));
            case "Period" -> List.of(p("years", "INTEGER"), p("months", "INTEGER"), p("days", "INTEGER"));
            case "YearMonth" -> List.of(p("year", "INTEGER"), range("month", "SMALLINT", "%s BETWEEN 1 AND 12"));
            case "MonthDay" -> List.of(range("month", "SMALLINT", "%s BETWEEN 1 AND 12"), range("day", "SMALLINT", "%s BETWEEN 1 AND 31"));
            case "Month" -> List.of(range("", "SMALLINT", "%s BETWEEN 1 AND 12"));
            case "DayOfWeek" -> List.of(range("", "SMALLINT", "%s BETWEEN 1 AND 7"));
            case "ZoneOffset" -> List.of(range("", "INTEGER", "%s BETWEEN -64800 AND 64800"));
            default -> throw new IllegalArgumentException("No PostgreSQL mapping for " + type);
        };
    }
    private static Part remainder() { return range("nanoRemainder", "SMALLINT", "%s BETWEEN 0 AND 999"); }
    private static Part offset() { return range("offsetSeconds", "INTEGER", "%s BETWEEN -64800 AND 64800"); }
}
