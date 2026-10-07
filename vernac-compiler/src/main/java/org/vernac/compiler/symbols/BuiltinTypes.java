// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.symbols;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.util.*;

/** The approved, import-free scalar types of Vernac domain fields. */
public final class BuiltinTypes {
    private static final Map<String, Class<?>> TYPES;

    static {
        Map<String, Class<?>> types = new TreeMap<>();
        for (Class<?> type : List.of(
                boolean.class, byte.class, short.class, int.class,
                long.class, float.class, double.class, char.class,
                Boolean.class, Byte.class, Short.class, Integer.class,
                Long.class, Float.class, Double.class, Character.class,
                String.class, BigInteger.class, BigDecimal.class, UUID.class,
                Instant.class, LocalDate.class, LocalTime.class, LocalDateTime.class,
                OffsetDateTime.class, OffsetTime.class, ZonedDateTime.class,
                Duration.class, Period.class, Year.class, YearMonth.class,
                MonthDay.class, Month.class, DayOfWeek.class, ZoneId.class,
                ZoneOffset.class, Currency.class)) {
            types.put(type.getSimpleName(), type);
        }
        TYPES = Collections.unmodifiableMap(types);
    }

    private BuiltinTypes() { }

    /** Exact, case-sensitive names reserved against user-defined types. */
    public static Set<String> names() {
        return TYPES.keySet();
    }

    /** No reflection, Java import lookup, or unknown-name fallback. */
    public static Optional<Class<?>> find(String name) {
        return Optional.ofNullable(TYPES.get(Objects.requireNonNull(name)));
    }
}
