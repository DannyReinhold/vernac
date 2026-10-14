// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.pagination;

import org.vernac.runtime.VernacTechnicalException;

/** A loader or projection returned an inconsistent page, rather than rejecting input. */
public final class PaginationContractException extends VernacTechnicalException {
    public PaginationContractException(String message) { super(message); }
}
