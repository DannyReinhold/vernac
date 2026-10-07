// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Domain-level rejection of an operation or value. */
@NullMarked
public class VernacDomainException extends VernacException {
    public VernacDomainException(String message) {
        super(message);
    }

    public VernacDomainException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
