// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.pagination;

import org.vernac.runtime.DomainValidationException;

/** Positive requested number of entries; a repository applies its configured upper limit. */
public record PageSize(int value) {
    public PageSize {
        if (value < 1) throw new DomainValidationException("Page size must be positive");
    }
    public static PageSize of(int value) { return new PageSize(value); }
    /** Reject, rather than clamp, a request beyond the supplied application limit. */
    public void checkMaximum(int maximum) {
        if (maximum < 1) throw new PaginationContractException("Maximum page size must be positive");
        if (value > maximum) throw new DomainValidationException("Page size exceeds the configured maximum");
    }
}
