// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.http;

import java.math.BigDecimal;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;

/** Exact wire-value predicates. Missing optional fields are unknown, not unequal. */
@NullMarked
public final class ErrorValues {
    private ErrorValues() { }
    public static ErrorCondition equal(Optional<?> actual, Object expected, boolean negate) {
        return actual.map(value -> ErrorCondition.of(value.equals(expected) != negate)).orElse(ErrorCondition.UNAVAILABLE);
    }
    public static ErrorCondition number(Optional<? extends Number> actual, String literal, String operator) {
        if (actual.isEmpty()) return ErrorCondition.UNAVAILABLE;
        int comparison = new BigDecimal(actual.get().toString()).compareTo(new BigDecimal(literal));
        return ErrorCondition.of(switch (operator) {
            case "=" -> comparison == 0; case "!=" -> comparison != 0;
            case "<" -> comparison < 0; case "<=" -> comparison <= 0;
            case ">" -> comparison > 0; case ">=" -> comparison >= 0;
            default -> throw new IllegalArgumentException("Unsupported numeric error operator");
        });
    }
}
