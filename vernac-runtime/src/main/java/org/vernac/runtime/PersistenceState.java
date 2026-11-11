// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime;

import org.jspecify.annotations.NullMarked;
import org.vernac.runtime.tenancy.TenantId;
import org.vernac.runtime.tenancy.TenantOwnership;

/** Technical adapter state, deliberately absent from domain read/write views.
 * Version changes do not count as domain modifications. Transaction rollback handling
 * remains the persistence adapter's responsibility.
 */
@NullMarked
public final class PersistenceState {
    private long version;
    private final TenantOwnership ownership;

    public PersistenceState(long version) {
        this(version, TenantOwnership.unscoped());
    }

    private PersistenceState(long version, TenantOwnership ownership) {
        version(version);
        this.ownership = ownership;
    }

    /** Additive technical factory; existing generated constructors remain unscoped. */
    public static PersistenceState newTenantAggregate() {
        return new PersistenceState(0, TenantOwnership.newAggregate());
    }

    public static PersistenceState restoredTenant(long version, TenantId tenant) {
        if (version < 1) throw new IllegalArgumentException("Restored persistence version must be positive");
        return new PersistenceState(version, TenantOwnership.restored(tenant));
    }

    public static PersistenceState detachedTenant(long version) {
        return new PersistenceState(version, TenantOwnership.detached());
    }

    public TenantOwnership ownership() { return ownership; }

    public long version() { return version; }

    /** Updates the same aggregate's persisted version; does not replace its identity. */
    public void version(long version) {
        if (version < 0) throw new IllegalArgumentException("Persistence version must not be negative");
        this.version = version;
    }
}
