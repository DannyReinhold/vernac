// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.http;

/** Three-valued error-rule truth. Unavailable body data must never turn into a match through NOT. */
public enum ErrorCondition {
    TRUE, FALSE, UNAVAILABLE;

    public ErrorCondition not() {
        return switch (this) { case TRUE -> FALSE; case FALSE -> TRUE; case UNAVAILABLE -> UNAVAILABLE; };
    }
    public ErrorCondition and(ErrorCondition other) {
        java.util.Objects.requireNonNull(other);
        if (this == FALSE || other == FALSE) return FALSE;
        return this == TRUE && other == TRUE ? TRUE : UNAVAILABLE;
    }
    public ErrorCondition or(ErrorCondition other) {
        java.util.Objects.requireNonNull(other);
        if (this == TRUE || other == TRUE) return TRUE;
        return this == FALSE && other == FALSE ? FALSE : UNAVAILABLE;
    }
    public boolean matches() { return this == TRUE; }
    public static ErrorCondition of(boolean value) { return value ? TRUE : FALSE; }
}
