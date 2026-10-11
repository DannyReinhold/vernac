// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Base exception for failures explicitly represented by the Vernac runtime. */
@NullMarked
public class VernacException extends RuntimeException {
    public VernacException(String message) {
        super(message);
    }

    public VernacException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
