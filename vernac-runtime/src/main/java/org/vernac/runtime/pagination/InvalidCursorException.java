// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.pagination;

import org.vernac.runtime.DomainValidationException;

/** Invalid, modified or incompatible continuation input; restart the traversal. */
public final class InvalidCursorException extends DomainValidationException {
    public InvalidCursorException() {
        super("Invalid or incompatible pagination cursor. Start a new traversal.");
    }
}
