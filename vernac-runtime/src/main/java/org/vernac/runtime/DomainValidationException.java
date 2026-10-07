// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** A value or operation violates a declared domain constraint. */
@NullMarked
public class DomainValidationException extends VernacDomainException {
    public DomainValidationException(String message) {
        super(message);
    }

    public DomainValidationException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
