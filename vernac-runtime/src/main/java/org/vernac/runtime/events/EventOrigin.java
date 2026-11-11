// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.events;

import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.vernac.runtime.tenancy.TenantContext;
import org.vernac.runtime.tenancy.TenantId;
import org.vernac.runtime.tenancy.TenancyException;
import static org.vernac.runtime.tenancy.TenancyException.Code.*;

/** Internal durable-publication origin, independent of business payload and actor permissions. */
@NullMarked
public final class EventOrigin {
    private static final EventOrigin GLOBAL = new EventOrigin(null);
    private final @Nullable TenantId tenant;

    private EventOrigin(@Nullable TenantId tenant) { this.tenant = tenant; }

    /** Capture only in the publishing/processing transaction; never infer GLOBAL from a stale pin. */
    public static EventOrigin capture() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new TenancyException(TENANT_TRANSACTION_REQUIRED);
        if (TenantContext.currentTenant().isPresent())
            return new EventOrigin(TenantContext.bindToCurrentTransaction());
        if (TenantContext.transactionTenant().isPresent())
            throw new TenancyException(TENANT_CONTEXT_MISMATCH);
        return GLOBAL;
    }

    public Optional<TenantId> tenant() { return Optional.ofNullable(tenant); }
    public boolean isGlobal() { return tenant == null; }

    /** Recheck at storage boundaries; a captured origin is not a context or authorization grant. */
    public void requireCurrent() {
        if (!equals(capture())) throw new TenancyException(TENANT_CONTEXT_MISMATCH);
    }

    /** Compatibility boundary for adapters built before scoped event storage. */
    public void requireGlobal() {
        requireCurrent();
        if (!isGlobal()) throw new TenancyException(TENANT_EVENT_DELIVERY_UNSUPPORTED);
    }

    @Override public boolean equals(@Nullable Object other) {
        return other instanceof EventOrigin origin && java.util.Objects.equals(tenant, origin.tenant);
    }
    @Override public int hashCode() { return java.util.Objects.hashCode(tenant); }
    @Override public String toString() { return isGlobal() ? "EventOrigin[GLOBAL]" : "EventOrigin[TENANT]"; }
}
