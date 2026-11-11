// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.tenancy;

import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.vernac.runtime.VernacTechnicalException;

/** Safe technical classification; never a domain validation or a customer allowance denial. */
@NullMarked
public final class TenancyException extends VernacTechnicalException {
    public enum Code {
        TENANT_CONTEXT_REQUIRED,
        TENANT_CONTEXT_MISMATCH,
        TENANT_TRANSACTION_REQUIRED,
        TENANT_SCOPE_ORDER_INVALID,
        TENANT_SCOPE_THREAD_MISMATCH,
        TENANT_OWNERSHIP_MISMATCH,
        TENANT_CONTEXT_CONFIGURATION_ERROR,
        TENANT_CONTEXT_CONTAMINATED,
        TENANT_RUNTIME_ROLE_INVALID,
        TENANT_EVENT_DELIVERY_UNSUPPORTED,
        TENANT_STORAGE_FAILURE
    }

    private final Code code;

    public TenancyException(Code code) {
        super(Objects.requireNonNull(code, "code").name());
        this.code = code;
    }

    public Code code() { return code; }
}
