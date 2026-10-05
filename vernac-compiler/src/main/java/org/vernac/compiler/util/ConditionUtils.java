// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.util;

public final class ConditionUtils {

    private ConditionUtils() {
        // Utility class
    }

    /**
     * Negiert eine fachliche Bedingung sauber für if-Throw-Prüfungen.
     * Aus "!reason.isBlank()" wird "reason.isBlank()".
     * Aus "amount >= 0" wird "!(amount >= 0)".
     */
    public static String negate(String condition) {
        if (condition == null || condition.isBlank()) {
            return "true";
        }
        String trimmed = condition.trim();

        if (trimmed.startsWith("!")) {
            String inner = trimmed.substring(1).trim();
            if (inner.startsWith("(") && inner.endsWith(")")) {
                return inner.substring(1, inner.length() - 1).trim();
            }
            return inner;
        }

        return "!(" + trimmed + ")";
    }
}