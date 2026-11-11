// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.experiments.tenancy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiPredicate;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Test-only candidate: trusted selection plus a resource bound to the physical JDBC transaction. */
final class TenantBoundary {
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final Object resourceKey = new Object();
    private final ThreadLocal<UUID> selection = new ThreadLocal<>();
    private final BiPredicate<String, UUID> membership;

    TenantBoundary(DataSource source, BiPredicate<String, UUID> membership) {
        this.source = source;
        this.jdbc = new JdbcTemplate(source);
        this.membership = membership;
    }

    Scope select(UUID requested) {
        Objects.requireNonNull(requested);
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new Failure("AUTHENTICATION_REQUIRED");
        }
        if (!membership.test(authentication.getName(), requested)) {
            throw new Failure("ACCESS_DENIED");
        }
        UUID previous = selection.get();
        var binding = binding();
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && ((previous != null && !previous.equals(requested))
                    || (binding != null && !binding.tenant().equals(requested)))) {
            throw new Failure("TENANT_CONTEXT_MISMATCH");
        }
        selection.set(requested);
        return new Scope(previous);
    }

    UUID requireBound() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new Failure("TRANSACTION_REQUIRED");
        }
        UUID current = selection.get();
        if (current == null) throw new Failure("TENANT_CONTEXT_REQUIRED");
        var existing = binding();
        if (existing != null) {
            if (!existing.tenant().equals(current)) throw new Failure("TENANT_CONTEXT_MISMATCH");
            // Do not release this borrowed connection until the transaction ends.
            Connection connection = DataSourceUtils.getConnection(source);
            try {
                requirePhysicalTransaction(connection);
                if (!Objects.equals(current.toString(), databaseTenant(connection))) {
                    throw new Failure("TENANT_CONTEXT_MISMATCH");
                }
            } catch (SQLException failure) {
                throw new Failure("TENANT_CONTEXT_CONFIGURATION_ERROR");
            } finally {
                DataSourceUtils.releaseConnection(connection, source);
            }
            return current;
        }
        verifyRuntimeRole();
        Connection connection = DataSourceUtils.getConnection(source);
        try {
            requirePhysicalTransaction(connection);
            String baseline = databaseTenant(connection);
            if (baseline != null && !baseline.isEmpty()) throw new Failure("TENANT_CONTEXT_CONTAMINATED");
            try (var statement = connection.prepareStatement("SELECT set_config('vernac.tenant_id', ?, true)")) {
                statement.setString(1, current.toString());
                statement.execute();
            }
        } catch (SQLException failure) {
            throw new Failure("TENANT_CONTEXT_CONFIGURATION_ERROR");
        } finally {
            DataSourceUtils.releaseConnection(connection, source);
        }
        var created = new Binding(current);
        TransactionSynchronizationManager.bindResource(resourceKey, created);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void suspend() {
                if (binding() != created) throw new Failure("TENANT_CONTEXT_MISMATCH");
                TransactionSynchronizationManager.unbindResource(resourceKey);
            }
            @Override public void resume() {
                TransactionSynchronizationManager.bindResource(resourceKey, created);
            }
            @Override public void afterCompletion(int status) {
                if (binding() == created) TransactionSynchronizationManager.unbindResource(resourceKey);
            }
        });
        return current;
    }

    void verifyRuntimeRole() {
        boolean unsafe = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT r.rolsuper OR r.rolbypassrls
                    OR pg_has_role(current_user, c.relowner, 'USAGE')
                    OR NOT c.relrowsecurity OR NOT c.relforcerowsecurity
                FROM pg_roles r CROSS JOIN pg_class c
                WHERE r.rolname = current_user AND c.oid = 'tenant_spike.invoice'::regclass
                """, Boolean.class));
        if (unsafe) throw new Failure("TENANT_RUNTIME_ROLE_INVALID");
    }

    private Binding binding() {
        return (Binding) TransactionSynchronizationManager.getResource(resourceKey);
    }

    private void requirePhysicalTransaction(Connection connection) throws SQLException {
        if (connection.getAutoCommit() || !DataSourceUtils.isConnectionTransactional(connection, source)) {
            throw new Failure("TRANSACTION_REQUIRED");
        }
    }

    private static String databaseTenant(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT current_setting('vernac.tenant_id', true)")) {
            result.next();
            return result.getString(1);
        }
    }

    boolean isBound() { return binding() != null; }
    boolean isSelected() { return selection.get() != null; }
    private record Binding(UUID tenant) {}

    final class Scope implements AutoCloseable {
        private final UUID previous;
        private boolean closed;
        private Scope(UUID previous) { this.previous = previous; }
        @Override public void close() {
            if (!closed) {
                if (previous == null) selection.remove(); else selection.set(previous);
                closed = true;
            }
        }
    }

    static final class Failure extends RuntimeException {
        Failure(String safeCode) { super(safeCode); }
    }
}
