// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.tenancy;

import static org.vernac.runtime.tenancy.TenancyException.Code.TENANT_OWNERSHIP_MISMATCH;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NullMarked;

/** Technical provenance. Ownership can be assigned once; rollback/version changes do not clear it. */
@NullMarked
public final class TenantOwnership {
    public enum Origin { NEW, RESTORED, DETACHED, UNSCOPED }
    private sealed interface Boundary permits Unassigned, Unscoped, Owned {}
    private enum Unassigned implements Boundary { INSTANCE }
    private enum Unscoped implements Boundary { INSTANCE }
    private record Owned(TenantId tenant) implements Boundary {}

    private final Origin origin;
    private final AtomicReference<Boundary> boundary;

    private TenantOwnership(Origin origin, Boundary boundary) {
        this.origin = origin;
        this.boundary = new AtomicReference<>(boundary);
    }

    /** For a future tenant-marked root factory; captures selection when already available. */
    public static TenantOwnership newAggregate() {
        Boundary selected = TenantContext.currentTenant().<Boundary>map(Owned::new).orElse(Unassigned.INSTANCE);
        return new TenantOwnership(Origin.NEW, selected);
    }

    /** Standalone new entities do not infer a root's tenant from their type or ambient selection. */
    public static TenantOwnership newEntity() {
        return new TenantOwnership(Origin.NEW, Unassigned.INSTANCE);
    }

    /** Trusted persistence adapter restoration, not a user-controlled domain field. */
    public static TenantOwnership restored(TenantId tenant) {
        return new TenantOwnership(Origin.RESTORED, new Owned(Objects.requireNonNull(tenant, "tenant")));
    }

    /** A caller reconstructed state; version zero must not make it an insertable new root. */
    public static TenantOwnership detached() {
        return new TenantOwnership(Origin.DETACHED, Unassigned.INSTANCE);
    }

    /** Existing unmarked aggregates retain this state, even while a tenant context is active. */
    public static TenantOwnership unscoped() {
        return new TenantOwnership(Origin.UNSCOPED, Unscoped.INSTANCE);
    }

    public Origin origin() { return origin; }

    public Optional<TenantId> tenant() {
        Boundary current = boundary.get();
        return current instanceof Owned owned ? Optional.of(owned.tenant) : Optional.empty();
    }

    public boolean isUnscoped() { return boundary.get() == Unscoped.INSTANCE; }

    /** One-way assignment for NEW values; subsequent writes must retain the same owner. */
    public void claimForTenant(TenantId tenant) {
        Objects.requireNonNull(tenant, "tenant");
        while (true) {
            Boundary current = boundary.get();
            if (current instanceof Owned owned) {
                if (owned.tenant.equals(tenant)) return;
                throw new TenancyException(TENANT_OWNERSHIP_MISMATCH);
            }
            if (current != Unassigned.INSTANCE || origin != Origin.NEW) {
                throw new TenancyException(TENANT_OWNERSHIP_MISMATCH);
            }
            if (boundary.compareAndSet(current, new Owned(tenant))) return;
        }
    }

    /** Adoption into an unscoped root must not silently import tenant-restored children. */
    public void claimUnscoped() {
        while (true) {
            Boundary current = boundary.get();
            if (current == Unscoped.INSTANCE) return;
            if (current != Unassigned.INSTANCE || origin != Origin.NEW) {
                throw new TenancyException(TENANT_OWNERSHIP_MISMATCH);
            }
            if (boundary.compareAndSet(current, Unscoped.INSTANCE)) return;
        }
    }

    @Override public String toString() { return "TenantOwnership[" + origin + "; redacted]"; }
}
