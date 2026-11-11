// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.runtime;

public interface Entity<ID> {
    ID id();

    /** Technical adapter access, deliberately absent from generated domain views. */
    default org.vernac.runtime.tenancy.TenantOwnership __vernacTenantOwnership() {
        return org.vernac.runtime.tenancy.TenantOwnership.unscoped();
    }
}