// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.experiments.tenancy;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BiPredicate;
import javax.sql.DataSource;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.vernac.runtime.tenancy.TenantContext;
import org.vernac.runtime.tenancy.TenantId;

/** Test-only authentication adapter around the production context and JDBC coordinator. */
final class TenantBoundary {
    private final org.vernac.runtime.jdbc.tenancy.JdbcTenantCoordinator coordinator;
    private final BiPredicate<String, UUID> membership;

    TenantBoundary(DataSource source, BiPredicate<String, UUID> membership) {
        this.coordinator = new org.vernac.runtime.jdbc.tenancy.JdbcTenantCoordinator(
                source, java.util.List.of("tenant_spike.invoice", "tenant_spike.line"));
        this.membership = membership;
    }

    TenantContext.Scope select(UUID requested) {
        Objects.requireNonNull(requested);
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new Failure("AUTHENTICATION_REQUIRED");
        }
        if (!membership.test(authentication.getName(), requested)) {
            throw new Failure("ACCESS_DENIED");
        }
        return TenantContext.openVerified(TenantId.of(requested));
    }

    UUID requireBound() { return coordinator.requireBound().value(); }

    void verifyRuntimeRole() { coordinator.verifyRuntimeRole(); }

    boolean isBound() { return TenantContext.transactionTenant().isPresent(); }
    boolean isSelected() { return TenantContext.currentTenant().isPresent(); }

    static final class Failure extends RuntimeException {
        Failure(String safeCode) { super(safeCode); }
    }
}
