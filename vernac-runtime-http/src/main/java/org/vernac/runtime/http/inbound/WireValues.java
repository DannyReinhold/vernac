// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.http.inbound;
import java.math.*;
import java.time.*;
import java.util.*;
import org.jspecify.annotations.NullMarked;

/** Exact, locale-independent transport formats. No permissive boolean/UUID guessing. */
@NullMarked
public final class WireValues {
    private WireValues() { }
    public static Object parse(String type,String value) {
        Objects.requireNonNull(value);
        try {
            return switch(type) {
                case "String" -> value;
                case "UUID" -> {
                    if(!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw new IllegalArgumentException();
                    yield UUID.fromString(value);
                }
                case "boolean", "Boolean" -> {if(!Set.of("true","false").contains(value))throw new IllegalArgumentException();yield Boolean.valueOf(value);}
                case "byte", "Byte" -> Byte.valueOf(value);
                case "short", "Short" -> Short.valueOf(value);
                case "int", "Integer" -> Integer.valueOf(value);
                case "long", "Long" -> Long.valueOf(value);
                case "float", "Float" -> Float.valueOf(value);
                case "double", "Double" -> Double.valueOf(value);
                case "char", "Character" -> {if(value.length()!=1)throw new IllegalArgumentException();yield value.charAt(0);}
                case "BigDecimal" -> new BigDecimal(value);
                case "BigInteger" -> new BigInteger(value);
                case "Currency" -> Currency.getInstance(value);
                case "Instant" -> Instant.parse(value);
                case "LocalDate" -> LocalDate.parse(value);
                case "LocalTime" -> LocalTime.parse(value);
                case "LocalDateTime" -> LocalDateTime.parse(value);
                case "OffsetDateTime" -> OffsetDateTime.parse(value);
                case "OffsetTime" -> OffsetTime.parse(value);
                case "ZonedDateTime" -> ZonedDateTime.parse(value);
                case "Duration" -> Duration.parse(value);
                case "Period" -> Period.parse(value);
                case "Year" -> Year.parse(value);
                case "YearMonth" -> YearMonth.parse(value);
                case "MonthDay" -> MonthDay.parse(value);
                case "Month" -> Month.valueOf(value);
                case "DayOfWeek" -> DayOfWeek.valueOf(value);
                case "ZoneId" -> ZoneId.of(value);
                case "ZoneOffset" -> ZoneOffset.of(value);
                default -> throw new IllegalArgumentException("Unsupported wire type");
            };
        } catch(IllegalArgumentException | DateTimeException error) {throw new WireValueException(type);}
    }
}
