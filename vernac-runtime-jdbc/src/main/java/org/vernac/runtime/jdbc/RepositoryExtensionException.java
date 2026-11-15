// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.jdbc;

import org.vernac.runtime.VernacTechnicalException;

/** Safe classification, without SQL, parameter values, tenant IDs or driver causes. */
public final class RepositoryExtensionException extends VernacTechnicalException {
    public enum Code {
        INVALID_QUERY,
        INVALID_RESULT,
        RESULT_LIMIT_EXCEEDED,
        UNTRACKED_RESULT,
        MODIFIED_READ_RESULT,
        SCOPE_EXPIRED,
        SCOPE_CHANGED,
        STORAGE_FAILURE
    }

    private final Code code;

    public RepositoryExtensionException(Code code) {
        super(java.util.Objects.requireNonNull(code).name());
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
