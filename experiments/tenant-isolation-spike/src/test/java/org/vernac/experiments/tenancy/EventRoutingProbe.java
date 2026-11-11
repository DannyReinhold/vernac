// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.experiments.tenancy;

import java.util.*;
import java.util.function.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.vernac.runtime.tenancy.*;
import org.vernac.runtime.jdbc.tenancy.JdbcTenantCoordinator;

/** Test-only mechanism, not the production event store/worker or a new supported API. */
final class EventRoutingProbe {
    record Origin(UUID tenant) {
        static Origin global() { return new Origin(null); }
        String key() { return tenant == null ? "GLOBAL" : "TENANT:" + tenant; }
        @Override public String toString() { return tenant == null ? "GLOBAL" : "TENANT"; }
    }
    record Payload(UUID id, UUID invoice, String text, List<String> values) {
        Payload { values = List.copyOf(values); }
    }
    record Claim(UUID id, UUID event, Origin origin, String listener, UUID token, int attempt) { }
    final JdbcTemplate jdbc;
    final TransactionTemplate transaction;
    private final JdbcTenantCoordinator coordinator;

    EventRoutingProbe(javax.sql.DataSource source) {
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        coordinator = new JdbcTenantCoordinator(source, List.of("tenant_spike.invoice", "tenant_spike.line",
                "tenant_spike.event_payload", "tenant_spike.event_value"));
        coordinator.verifyRuntimeRole();
    }

    <T> T inOrigin(Origin origin, Supplier<T> action) {
        if (origin.tenant() == null) {
            requireUnscopedEntry();
            return transaction.execute(status -> {
                String setting = jdbc.queryForObject("SELECT current_setting('vernac.tenant_id',true)", String.class);
                if (setting != null && !setting.isEmpty()) throw new IllegalStateException("CONTAMINATED_GLOBAL_CONNECTION");
                return action.get();
            });
        }
        try (var selected = TenantContext.openVerified(TenantId.of(origin.tenant()))) {
            return transaction.execute(status -> { coordinator.requireBound(); return action.get(); });
        }
    }

    private static void requireUnscopedEntry() {
        if (TenantContext.currentTenant().isPresent() || TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("WORKER_OR_GLOBAL_ENTRY_REQUIRES_EMPTY_CONTEXT");
    }

    void publish(Origin origin, Payload payload, List<String> listeners) {
        inOrigin(origin, () -> { append(origin, payload, listeners); return null; });
    }

    void append(Origin origin, Payload payload, List<String> listeners) {
        try {
            int inserted = jdbc.update("INSERT INTO tenant_spike.event_payload(id,tenant_id,scope_key,invoice_id,payload) VALUES (?,?,?,?,?) ON CONFLICT(id) DO NOTHING",
                    payload.id(), origin.tenant(), origin.key(), payload.invoice(), payload.text());
            if (inserted == 0) {
                if (!load(origin, payload.id()).equals(payload)) throw new IllegalStateException("EVENT_PAYLOAD_CONFLICT");
                return; // Fixed recipient set, never retroactive subscription.
            }
            for (int position = 0; position < payload.values().size(); position++)
                jdbc.update("INSERT INTO tenant_spike.event_value VALUES (?,?,?,?,?)", origin.tenant(), origin.key(), payload.id(), position, payload.values().get(position));
            for (String listener : listeners)
                jdbc.update("INSERT INTO tenant_spike.event_delivery(id,event_id,tenant_id,listener,status) VALUES (?,?,?,?,'PENDING')",
                        UUID.randomUUID(), payload.id(), origin.tenant(), listener);
        } catch (DataAccessException failed) {
            throw new IllegalStateException("EVENT_STORAGE_FAILURE"); // No driver DETAIL or payload/tenant values.
        }
    }

    Payload load(Origin origin, UUID event) {
        var roots = jdbc.query("SELECT invoice_id,payload FROM tenant_spike.event_payload WHERE id=? AND tenant_id IS NOT DISTINCT FROM ?::uuid",
                (row, n) -> new Payload(event, row.getObject(1, UUID.class), row.getString(2), List.of()), event, origin.tenant());
        if (roots.size() != 1) throw new IllegalStateException("EVENT_ORIGIN_OR_PAYLOAD_MISMATCH");
        var values = jdbc.query("SELECT value FROM tenant_spike.event_value WHERE event_id=? AND tenant_id IS NOT DISTINCT FROM ?::uuid ORDER BY position",
                (row, n) -> row.getString(1), event, origin.tenant());
        return new Payload(event, roots.getFirst().invoice(), roots.getFirst().text(), values);
    }

    Claim claim() {
        requireUnscopedEntry();
        return transaction.execute(status -> {
            jdbc.update("UPDATE tenant_spike.event_delivery SET status='FAILED',token=NULL,lease_until=NULL,last_error='ATTEMPT_LIMIT' "
                    + "WHERE attempts>=3 AND (status='PENDING' OR status='PROCESSING' AND lease_until<=clock_timestamp())");
            UUID token = UUID.randomUUID();
            var rows = jdbc.query("WITH candidate AS (SELECT id FROM tenant_spike.event_delivery "
                    + "WHERE attempts<3 AND (status='PENDING' AND next_attempt_at<=clock_timestamp() OR status='PROCESSING' AND lease_until<=clock_timestamp()) "
                    + "ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED) "
                    + "UPDATE tenant_spike.event_delivery d SET status='PROCESSING',attempts=attempts+1,token=?,lease_until=clock_timestamp()+interval '30 seconds' "
                    + "FROM candidate c WHERE d.id=c.id RETURNING d.id,d.event_id,d.tenant_id,d.listener,d.attempts",
                    (row, n) -> new Claim(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                            new Origin(row.getObject(3, UUID.class)), row.getString(4), token, row.getInt(5)), token);
            return rows.isEmpty() ? null : rows.getFirst();
        });
    }

    boolean process(Claim claim, Consumer<Payload> handler) {
        requireUnscopedEntry();
        try {
            inOrigin(claim.origin(), () -> {
                handler.accept(load(claim.origin(), claim.event()));
                int updated = jdbc.update("UPDATE tenant_spike.event_delivery SET status='SUCCEEDED',token=NULL,lease_until=NULL,last_error=NULL "
                        + "WHERE id=? AND status='PROCESSING' AND token=? AND lease_until>clock_timestamp() AND tenant_id IS NOT DISTINCT FROM ?::uuid",
                        claim.id(), claim.token(), claim.origin().tenant());
                if (updated != 1) throw new IllegalStateException("LOST_CLAIM");
                return null;
            });
            return true;
        } catch (RuntimeException failed) {
            // The scope and processing transaction ended before independent failure bookkeeping.
            transaction.executeWithoutResult(status -> jdbc.update("UPDATE tenant_spike.event_delivery "
                    + "SET status=CASE WHEN attempts>=3 THEN 'FAILED' ELSE 'PENDING' END,token=NULL,lease_until=NULL,"
                    + "next_attempt_at=clock_timestamp()+interval '1 day',last_error=? "
                    + "WHERE id=? AND status='PROCESSING' AND token=? AND tenant_id IS NOT DISTINCT FROM ?::uuid",
                    failed.getClass().getName(), claim.id(), claim.token(), claim.origin().tenant()));
            return false;
        }
    }
}
