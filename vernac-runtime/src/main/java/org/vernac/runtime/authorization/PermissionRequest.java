// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.authorization;

import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.vernac.runtime.tenancy.TenantId;

/** Pure policy input. Contains no Authentication, credentials or raw request. */
@NullMarked
public record PermissionRequest(String operation, VerifiedActor actor, TenantId tenant,
                                PermissionKey permission, @Nullable ResourceReference<?> resource) {
    public PermissionRequest {
        Objects.requireNonNull(operation); Objects.requireNonNull(actor);
        Objects.requireNonNull(tenant); Objects.requireNonNull(permission);
    }
    @Override public String toString() { return "PermissionRequest[redacted]"; }
}
