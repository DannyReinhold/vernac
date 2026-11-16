// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.restrictions;

public final class RestrictionFailure extends RuntimeException {
    public enum Code { EXHAUSTED, INVALID_FACT, INVALID_WINDOW, INVALID_TRANSITION, OPERATION_CONFLICT, CONFIGURATION, UNAVAILABLE }
    private final Code code;
    public RestrictionFailure(Code code) {
        super("Restriction operation failed: " + java.util.Objects.requireNonNull(code), null, false, false);
        this.code = code;
    }
    public Code code() { return code; }
}
