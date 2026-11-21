// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.tenancy;

import static org.junit.jupiter.api.Assertions.*;
import static org.vernac.runtime.tenancy.TenancyException.Code.TENANT_OWNERSHIP_MISMATCH;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.vernac.runtime.PersistenceState;

class TenantOwnershipTest {
    private final TenantId a = TenantId.of(UUID.randomUUID());
    private final TenantId b = TenantId.of(UUID.randomUUID());

    private static void mismatch(Runnable operation) {
        assertEquals(TENANT_OWNERSHIP_MISMATCH, assertThrows(TenancyException.class, operation::run).code());
    }
    @AfterEach void noScopeLeak() { assertTrue(TenantContext.currentTenant().isEmpty()); }

    @Test void legacyPersistenceStateStaysUnscopedEvenInsideTenantSelection() {
        try (var scope = TenantContext.openVerified(a)) {
            var old = new PersistenceState(0);
            assertTrue(old.ownership().isUnscoped());
            old.version(4); old.version(0);
            assertTrue(old.ownership().isUnscoped());
            mismatch(() -> old.ownership().claimForTenant(a));
        }
    }

    @Test void newRootCapturesSelectionButNewEntityWaitsForItsRoot() {
        try (var scope = TenantContext.openVerified(a)) {
            var root = PersistenceState.newTenantAggregate();
            assertEquals(a, root.ownership().tenant().orElseThrow());
            mismatch(() -> root.ownership().claimForTenant(b));
            var entity = TenantOwnership.newEntity();
            assertTrue(entity.tenant().isEmpty());
            entity.claimUnscoped();
            mismatch(() -> entity.claimForTenant(a));
        }
    }

    @Test void firstAssignmentIsOneWayAndVersionResetDoesNotRemoveOwnership() {
        var state = PersistenceState.newTenantAggregate();
        assertEquals(TenantOwnership.Origin.NEW, state.ownership().origin());
        state.ownership().claimForTenant(a); state.ownership().claimForTenant(a);
        state.version(1); state.version(0);
        assertEquals(a, state.ownership().tenant().orElseThrow());
        mismatch(() -> state.ownership().claimForTenant(b));
        mismatch(state.ownership()::claimUnscoped);
    }

    @Test void restoredAndDetachedStateCannotBeConvertedIntoNewOrForeignOwnership() {
        var restored = PersistenceState.restoredTenant(4, a);
        assertEquals(TenantOwnership.Origin.RESTORED, restored.ownership().origin());
        restored.ownership().claimForTenant(a);
        mismatch(() -> restored.ownership().claimForTenant(b));
        assertThrows(IllegalArgumentException.class, () -> PersistenceState.restoredTenant(0, a));
        var detached = PersistenceState.detachedTenant(0);
        mismatch(() -> detached.ownership().claimForTenant(a));
        mismatch(detached.ownership()::claimUnscoped);
        assertEquals(TenantOwnership.Origin.DETACHED, detached.ownership().origin());
    }

    @Test void competingFirstOwnersCannotBothClaimTheSameObject() throws Exception {
        var entity = TenantOwnership.newEntity();
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var claims = List.of(a,b).stream().map(tenant -> executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try { entity.claimForTenant(tenant); return true; }
                catch (TenancyException denied) { assertEquals(TENANT_OWNERSHIP_MISMATCH, denied.code()); return false; }
            })).toList();
            assertNotEquals(claims.get(0).get(10, TimeUnit.SECONDS), claims.get(1).get(10, TimeUnit.SECONDS));
            assertTrue(entity.tenant().isPresent());
        }
    }

    @Test void diagnosticStringsAndTechnicalFailuresDoNotExposeTenantValues() {
        var owned = TenantOwnership.restored(a);
        assertFalse(owned.toString().contains(a.asString()));
        var error = assertThrows(TenancyException.class, () -> owned.claimForTenant(b));
        assertFalse(error.toString().contains(a.asString()));
        assertFalse(error.toString().contains(b.asString()));
        assertInstanceOf(org.vernac.runtime.VernacTechnicalException.class, error);
        assertFalse(org.vernac.runtime.VernacDomainException.class.isAssignableFrom(error.getClass()));
    }
}
