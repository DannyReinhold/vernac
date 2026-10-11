// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.jdbc;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import org.jspecify.annotations.NullMarked;
import org.vernac.runtime.VernacTechnicalException;

/** Persistence representation checks; these do not restrict domain construction. */
@NullMarked
final class ScalarStorageLimits {
    private static final Instant MIN_INSTANT = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAX_INSTANT = Instant.parse("9999-12-31T23:59:59.999999999Z");

    private ScalarStorageLimits() { }

    static void validate(String type, Object value) {
        switch (type) {
            case "String" -> text((String) value);
            case "BigInteger" -> decimal(type, new BigDecimal((BigInteger) value));
            case "BigDecimal" -> decimal(type, (BigDecimal) value);
            case "LocalDate" -> date(type, (LocalDate) value);
            case "LocalDateTime" -> date(type, ((LocalDateTime) value).toLocalDate());
            case "Instant" -> instant(type, (Instant) value);
            case "OffsetDateTime" -> {
                var v = (OffsetDateTime) value;
                date(type, v.toLocalDate());
                instant(type, v.toInstant());
            }
            case "ZonedDateTime" -> date(type, ((ZonedDateTime) value).toLocalDate());
            default -> { }
        }
    }

    private static void text(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0) fail("String", "NUL is not supported by PostgreSQL TEXT");
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))) {
                    fail("String", "unpaired UTF-16 surrogate");
                }
            } else if (Character.isLowSurrogate(c)) {
                fail("String", "unpaired UTF-16 surrogate");
            }
        }
    }

    private static void decimal(String type, BigDecimal value) {
        // Check the supplied representation, including trailing zeros. Never expand exponents.
        long integerDigits = Math.max(0L, (long) value.precision() - value.scale());
        if (value.scale() > 16383 || value.scale() < -131071 || integerDigits > 131072) {
            fail(type, "NUMERIC supports at most 131072 integer and 16383 fractional digits; scale must be between -131071 and 16383");
        }
    }

    private static void date(String type, LocalDate value) {
        if (value.getYear() < 1 || value.getYear() > 9999) {
            fail(type, "native date/timestamp storage supports years 0001 through 9999");
        }
    }

    private static void instant(String type, Instant value) {
        if (value.isBefore(MIN_INSTANT)
                || value.isAfter(MAX_INSTANT)) {
            fail(type, "UTC timestamp storage supports years 0001 through 9999");
        }
    }

    private static void fail(String type, String reason) {
        throw new VernacTechnicalException("Cannot persist " + type + ": " + reason);
    }
}
