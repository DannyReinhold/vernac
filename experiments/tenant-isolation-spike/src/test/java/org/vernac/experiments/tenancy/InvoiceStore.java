// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.experiments.tenancy;

import java.util.List;
import java.util.UUID;
import org.vernac.runtime.tenancy.TenantId;
import org.vernac.runtime.tenancy.TenantOwnership;
import org.springframework.jdbc.core.JdbcTemplate;

/** Handwritten candidate repository, NOT generated Vernac code or a public runtime API. */
final class InvoiceStore {
    private final JdbcTemplate jdbc;
    private final TenantBoundary boundary;

    InvoiceStore(JdbcTemplate jdbc, TenantBoundary boundary) { this.jdbc = jdbc; this.boundary = boundary; }

    Invoice byId(UUID id) {
        UUID tenant = boundary.requireBound();
        var rows = jdbc.query("SELECT id, title, version FROM tenant_spike.invoice WHERE tenant_id=? AND id=?",
                (r, n) -> Invoice.loaded(r.getObject(1, UUID.class), r.getString(2), r.getLong(3), tenant), tenant, id);
        if (rows.isEmpty()) throw new TenantBoundary.Failure("NOT_FOUND");
        return rows.getFirst();
    }

    List<UUID> find(String first, String second) {
        UUID tenant = boundary.requireBound();
        return jdbc.query("SELECT id FROM tenant_spike.invoice WHERE tenant_id=? AND (title=? OR title=?) ORDER BY title",
                (r, n) -> r.getObject(1, UUID.class), tenant, first, second);
    }

    long visibleCount() {
        UUID tenant = boundary.requireBound();
        return jdbc.queryForObject("SELECT count(*) FROM tenant_spike.invoice WHERE tenant_id=?", Long.class, tenant);
    }

    void save(Invoice invoice) {
        UUID tenant = boundary.requireBound();
        invoice.ownership.claimForTenant(TenantId.of(tenant));
        if (invoice.version == 0 && invoice.origin == Origin.NEW) {
            jdbc.update("INSERT INTO tenant_spike.invoice (id, tenant_id, title, version) VALUES (?,?,?,1)",
                    invoice.id, tenant, invoice.title);
            invoice.version = 1;
        } else {
            int changed = jdbc.update("UPDATE tenant_spike.invoice SET title=?, version=version+1 WHERE tenant_id=? AND id=? AND version=?",
                    invoice.title, tenant, invoice.id, invoice.version);
            if (changed != 1) throw new TenantBoundary.Failure("OPTIMISTIC_CONFLICT");
            invoice.version++;
        }
    }

    void delete(Invoice invoice) {
        UUID tenant = boundary.requireBound();
        invoice.ownership.claimForTenant(TenantId.of(tenant));
        if (jdbc.update("DELETE FROM tenant_spike.invoice WHERE tenant_id=? AND id=? AND version=?",
                tenant, invoice.id, invoice.version) != 1) throw new TenantBoundary.Failure("OPTIMISTIC_CONFLICT");
    }

    enum Origin { NEW, STORED, DETACHED }
    static final class Invoice {
        final UUID id;
        String title;
        long version;
        final Origin origin;
        final TenantOwnership ownership;
        private Invoice(UUID id, String title, long version, UUID tenant, Origin origin) {
            this.id=id; this.title=title; this.version=version; this.ownership = switch (origin) {
                case NEW -> TenantOwnership.newAggregate();
                case STORED -> TenantOwnership.restored(TenantId.of(tenant));
                case DETACHED -> TenantOwnership.detached();
            }; this.origin=origin;
        }
        static Invoice create(String title) { return new Invoice(UUID.randomUUID(), title, 0, null, Origin.NEW); }
        static Invoice detached(UUID id, String title) { return new Invoice(id, title, 0, null, Origin.DETACHED); }
        static Invoice loaded(UUID id, String title, long version, UUID tenant) { return new Invoice(id, title, version, tenant, Origin.STORED); }
    }
}
