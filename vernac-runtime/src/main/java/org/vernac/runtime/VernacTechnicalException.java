// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Technical failure translated at an infrastructure boundary. */
@NullMarked
public class VernacTechnicalException extends VernacException {
    public VernacTechnicalException(String message) {
        super(message);
    }

    public VernacTechnicalException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
