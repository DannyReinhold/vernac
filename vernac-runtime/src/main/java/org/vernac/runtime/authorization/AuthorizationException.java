// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.authorization;

import java.util.Objects;
import org.jspecify.annotations.NullMarked;

/** Terminal safe authorization outcome, outside domain/integration rejection hierarchies. */
@NullMarked
public final class AuthorizationException extends RuntimeException {
    public enum Code {
        AUTHENTICATION_REQUIRED, ACCESS_DENIED, SCOPE_REQUIRED,
        AUTHORIZATION_CONFIGURATION_ERROR, AUTHORIZATION_UNAVAILABLE
    }
    private final Code code;
    public AuthorizationException(Code code) { super(Objects.requireNonNull(code).name(), null, false, true); this.code = code; }
    public Code code() { return code; }
}
