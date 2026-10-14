// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.pagination;

import org.vernac.runtime.DomainValidationException;

/** One-based page number; distinct from a zero-based row offset. */
public record PageNumber(long value) {
    public PageNumber {
        if (value < 1) throw new DomainValidationException("Page number must be positive");
    }
    public static PageNumber of(long value) { return new PageNumber(value); }
}
