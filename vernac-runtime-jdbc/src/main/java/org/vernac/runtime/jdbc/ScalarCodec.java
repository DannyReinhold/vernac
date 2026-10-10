// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.jdbc;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Lossless Java scalar components in the order defined by the compiler's mapping. */
@NullMarked
public final class ScalarCodec {
    private ScalarCodec() { }
    public static Object[] encode(String type, Object value) {
        ScalarStorageLimits.validate(type, value);
        return switch (type) {
            case "BigDecimal" -> new Object[]{value, ((BigDecimal)value).scale()};
            case "BigInteger" -> new Object[]{new BigDecimal((BigInteger)value)};
            case "char", "Character" -> new Object[]{(int)(Character)value};
            case "Currency" -> new Object[]{((Currency)value).getCurrencyCode()};
            case "ZoneId" -> new Object[]{((ZoneId)value).getId()};
            case "Year" -> new Object[]{((Year)value).getValue()};
            case "Month" -> new Object[]{((Month)value).getValue()};
            case "DayOfWeek" -> new Object[]{((DayOfWeek)value).getValue()};
            case "ZoneOffset" -> new Object[]{((ZoneOffset)value).getTotalSeconds()};
            case "LocalTime" -> { var v=(LocalTime)value; yield new Object[]{v.truncatedTo(ChronoUnit.MICROS), v.getNano()%1000}; }
            case "LocalDateTime" -> { var v=(LocalDateTime)value; yield new Object[]{v.truncatedTo(ChronoUnit.MICROS), v.getNano()%1000}; }
            case "Instant" -> { var v=(Instant)value; yield new Object[]{v.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC),v.getNano()%1000}; }
            case "OffsetDateTime" -> { var v=(OffsetDateTime)value; yield new Object[]{v.truncatedTo(ChronoUnit.MICROS),v.getNano()%1000,v.getOffset().getTotalSeconds()}; }
            case "ZonedDateTime" -> { var v=(ZonedDateTime)value; yield new Object[]{v.toLocalDateTime().truncatedTo(ChronoUnit.MICROS),v.getNano()%1000,v.getOffset().getTotalSeconds(),v.getZone().getId()}; }
            case "OffsetTime" -> { var v=(OffsetTime)value; yield new Object[]{v.toLocalTime().truncatedTo(ChronoUnit.MICROS),v.getNano()%1000,v.getOffset().getTotalSeconds()}; }
            case "Duration" -> { var v=(Duration)value; yield new Object[]{v.getSeconds(),v.getNano()}; }
            case "Period" -> { var v=(Period)value; yield new Object[]{v.getYears(),v.getMonths(),v.getDays()}; }
            case "YearMonth" -> { var v=(YearMonth)value; yield new Object[]{v.getYear(),v.getMonthValue()}; }
            case "MonthDay" -> { var v=(MonthDay)value; yield new Object[]{v.getMonthValue(),v.getDayOfMonth()}; }
            case "boolean", "Boolean", "byte", "Byte", "short", "Short", "int", "Integer", "long", "Long",
                 "float", "Float", "double", "Double", "String", "UUID", "LocalDate" -> new Object[]{value};
            default -> throw new IllegalArgumentException("Unsupported scalar: " + type);
        };
    }
    public static Object decode(String type, Object[] p) {
        Object v=p[0];
        return switch (type) {
            case "byte", "Byte" -> ((Number)v).byteValue();
            case "short", "Short" -> ((Number)v).shortValue();
            case "int", "Integer" -> ((Number)v).intValue();
            case "long", "Long" -> ((Number)v).longValue();
            case "float", "Float" -> ((Number)v).floatValue();
            case "double", "Double" -> ((Number)v).doubleValue();
            case "char", "Character" -> (char)((Number)v).intValue();
            case "BigDecimal" -> ((BigDecimal)v).setScale(n(p,1),RoundingMode.UNNECESSARY);
            case "BigInteger" -> ((BigDecimal)v).toBigIntegerExact();
            case "Currency" -> Currency.getInstance((String)v);
            case "ZoneId" -> ZoneId.of((String)v);
            case "Year" -> Year.of(n(p,0));
            case "Month" -> Month.of(n(p,0));
            case "DayOfWeek" -> DayOfWeek.of(n(p,0));
            case "ZoneOffset" -> ZoneOffset.ofTotalSeconds(n(p,0));
            case "LocalTime" -> ((LocalTime)v).plusNanos(n(p,1));
            case "LocalDateTime" -> ((LocalDateTime)v).plusNanos(n(p,1));
            case "Instant" -> ((OffsetDateTime)v).toInstant().plusNanos(n(p,1));
            case "OffsetDateTime" -> ((OffsetDateTime)v).withOffsetSameInstant(ZoneOffset.ofTotalSeconds(n(p,2))).plusNanos(n(p,1));
            case "ZonedDateTime" -> ZonedDateTime.ofStrict(((LocalDateTime)v).plusNanos(n(p,1)),ZoneOffset.ofTotalSeconds(n(p,2)),ZoneId.of((String)p[3]));
            case "OffsetTime" -> ((LocalTime)v).plusNanos(n(p,1)).atOffset(ZoneOffset.ofTotalSeconds(n(p,2)));
            case "Duration" -> Duration.ofSeconds(((Number)v).longValue(),n(p,1));
            case "Period" -> Period.of(n(p,0),n(p,1),n(p,2));
            case "YearMonth" -> YearMonth.of(n(p,0),n(p,1));
            case "MonthDay" -> MonthDay.of(n(p,0),n(p,1));
            case "boolean", "Boolean", "String", "UUID", "LocalDate" -> v;
            default -> throw new IllegalArgumentException("Unsupported scalar: " + type);
        };
    }
    private static int n(Object[] p,int i) { return ((Number)p[i]).intValue(); }
}
