// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.tenancy;

import static org.vernac.runtime.tenancy.TenancyException.Code.*;

import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Trusted execution selection and Spring transaction identity pin.
 * This is not authentication, membership verification, JDBC connection binding or SQL filtering.
 */
@NullMarked
public final class TenantContext {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static final Object TRANSACTION_KEY = new Object();

    private TenantContext() {}

    /**
     * Trusted integration entrypoint. The caller must already have authenticated/authorized
     * this selection. Never pass an unverified request/header value directly.
     */
    public static Scope openVerified(TenantId tenant) {
        Objects.requireNonNull(tenant, "tenant");
        Scope previous = CURRENT.get();
        Pin pin = pin();
        if ((pin != null && !pin.tenant.equals(tenant))
                || (TransactionSynchronizationManager.isActualTransactionActive()
                    && previous != null && !previous.tenant.equals(tenant))) {
            throw new TenancyException(TENANT_CONTEXT_MISMATCH);
        }
        Scope scope = new Scope(tenant, previous);
        CURRENT.set(scope);
        try {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                bindToCurrentTransaction();
            }
        } catch (RuntimeException | Error failed) {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            throw failed;
        }
        return scope;
    }

    /** Does not create a context, transaction pin or JDBC connection. */
    public static Optional<TenantId> currentTenant() {
        Scope scope = CURRENT.get();
        return scope == null ? Optional.empty() : Optional.of(scope.tenant);
    }

    public static TenantId requireTenant() {
        TenantId tenant = currentTenant().orElseThrow(() -> new TenancyException(TENANT_CONTEXT_REQUIRED));
        Pin pin = pin();
        if (pin != null && !pin.tenant.equals(tenant)) throw new TenancyException(TENANT_CONTEXT_MISMATCH);
        return tenant;
    }

    /**
     * Pins selection to Spring's current physical transaction lifecycle. A JDBC coordinator
     * must additionally validate the actual DataSource/connection and initialize its SQL state.
     */
    public static TenantId bindToCurrentTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new TenancyException(TENANT_TRANSACTION_REQUIRED);
        }
        TenantId tenant = requireTenant();
        Pin existing = pin();
        if (existing != null) return tenant;
        Pin created = new Pin(tenant);
        TransactionSynchronizationManager.bindResource(TRANSACTION_KEY, created);
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void suspend() {
                    if (pin() != created) throw new TenancyException(TENANT_CONTEXT_MISMATCH);
                    TransactionSynchronizationManager.unbindResource(TRANSACTION_KEY);
                }
                @Override public void resume() {
                    if (pin() != null) throw new TenancyException(TENANT_CONTEXT_MISMATCH);
                    TransactionSynchronizationManager.bindResource(TRANSACTION_KEY, created);
                }
                @Override public void afterCompletion(int status) {
                    if (pin() == created) TransactionSynchronizationManager.unbindResource(TRANSACTION_KEY);
                }
            });
        } catch (RuntimeException | Error failed) {
            TransactionSynchronizationManager.unbindResource(TRANSACTION_KEY);
            throw failed;
        }
        return tenant;
    }

    /** Read-only technical metadata; an existing pin does not replace a live trusted selection. */
    public static Optional<TenantId> transactionTenant() {
        Pin pin = pin();
        return pin == null ? Optional.empty() : Optional.of(pin.tenant);
    }

    private static @Nullable Pin pin() {
        return (Pin) TransactionSynchronizationManager.getResource(TRANSACTION_KEY);
    }

    private record Pin(TenantId tenant) {}

    /** Thread-owned, LIFO scope. Closing does not release a transaction pin prematurely. */
    public static final class Scope implements AutoCloseable {
        private final TenantId tenant;
        private final @Nullable Scope previous;
        private final Thread owner = Thread.currentThread();
        private boolean closed;

        private Scope(TenantId tenant, @Nullable Scope previous) {
            this.tenant = tenant;
            this.previous = previous;
        }

        @Override public void close() {
            if (Thread.currentThread() != owner) throw new TenancyException(TENANT_SCOPE_THREAD_MISMATCH);
            if (closed) return;
            if (CURRENT.get() != this) throw new TenancyException(TENANT_SCOPE_ORDER_INVALID);
            Pin pin = pin();
            if (pin != null && previous != null && !pin.tenant.equals(previous.tenant)) {
                throw new TenancyException(TENANT_CONTEXT_MISMATCH);
            }
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            closed = true;
        }
    }
}
