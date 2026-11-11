// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.jdbc.tenancy;

import static org.vernac.runtime.tenancy.TenancyException.Code.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.vernac.runtime.tenancy.TenancyException;
import org.vernac.runtime.tenancy.TenantContext;
import org.vernac.runtime.tenancy.TenantId;

/**
 * PostgreSQL transaction-local tenant binding on a Spring-managed JDBC connection.
 * Explicit opt-in infrastructure; does not install policies or modify repository SQL.
 */
@NullMarked
public final class JdbcTenantCoordinator {
    private final DataSource source;
    private final List<String> tables;
    private final Object resourceKey = new Object();

    /** Table names use PostgreSQL regclass syntax, including quotes where required. */
    public JdbcTenantCoordinator(DataSource source, List<String> tenantTables) {
        this.source = Objects.requireNonNull(source, "source");
        this.tables = List.copyOf(tenantTables);
        if (tables.isEmpty() || tables.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Tenant tables must be explicitly configured");
        }
    }

    /** Call at startup after migrations. Missing/unsafe tables fail closed. */
    public void verifyRuntimeRole() {
        Connection connection = connection();
        try {
            verifyTables(connection);
        } catch (SQLException failed) {
            throw new TenancyException(TENANT_CONTEXT_CONFIGURATION_ERROR);
        } finally {
            DataSourceUtils.releaseConnection(connection, source);
        }
    }

    /** Call before every tenant-aware storage operation, before its first SQL statement. */
    public TenantId requireBound() {
        TenantId tenant = TenantContext.bindToCurrentTransaction();
        Connection connection = connection();
        try {
            if (connection.getAutoCommit() || !DataSourceUtils.isConnectionTransactional(connection, source)) {
                throw new TenancyException(TENANT_TRANSACTION_REQUIRED);
            }
            Binding existing = binding();
            String current = databaseTenant(connection);
            if (existing != null) {
                if (!existing.tenant.equals(tenant) || !existing.connection.equals(connection)
                        || !tenant.asString().equals(current)) {
                    throw new TenancyException(TENANT_CONTEXT_MISMATCH);
                }
                return tenant;
            }
            if (current != null && !current.isEmpty()) {
                throw new TenancyException(TENANT_CONTEXT_CONTAMINATED);
            }
            verifyTables(connection);
            try (var statement = connection.prepareStatement("SELECT set_config('vernac.tenant_id', ?, true)")) {
                statement.setString(1, tenant.asString());
                statement.execute();
            }
            register(new Binding(tenant, connection));
            return tenant;
        } catch (SQLException failed) {
            throw new TenancyException(TENANT_CONTEXT_CONFIGURATION_ERROR);
        } finally {
            DataSourceUtils.releaseConnection(connection, source);
        }
    }

    private Connection connection() {
        try {
            return DataSourceUtils.getConnection(source);
        } catch (org.springframework.jdbc.CannotGetJdbcConnectionException failed) {
            // Do not retain connection URLs, credentials or driver exception text.
            throw new TenancyException(TENANT_CONTEXT_CONFIGURATION_ERROR);
        }
    }

    private void verifyTables(Connection connection) throws SQLException {
        for (String table : tables) {
            try (var statement = connection.prepareStatement("""
                    SELECT r.rolsuper OR r.rolbypassrls
                        OR pg_has_role(current_user, c.relowner, 'USAGE')
                        OR NOT c.relrowsecurity OR NOT c.relforcerowsecurity
                        OR has_schema_privilege(current_user, c.relnamespace, 'CREATE')
                        OR has_table_privilege(current_user, c.oid, 'TRUNCATE')
                    FROM pg_roles r CROSS JOIN pg_class c
                    WHERE r.rolname = current_user AND c.oid = ?::regclass
                    """)) {
                statement.setString(1, table);
                try (var result = statement.executeQuery()) {
                    if (!result.next() || result.getBoolean(1)) {
                        throw new TenancyException(TENANT_RUNTIME_ROLE_INVALID);
                    }
                }
            }
        }
    }

    private static @Nullable String databaseTenant(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT current_setting('vernac.tenant_id', true)")) {
            if (!result.next()) throw new TenancyException(TENANT_CONTEXT_CONFIGURATION_ERROR);
            return result.getString(1);
        }
    }

    private @Nullable Binding binding() {
        return (Binding) TransactionSynchronizationManager.getResource(resourceKey);
    }

    private void register(Binding created) {
        TransactionSynchronizationManager.bindResource(resourceKey, created);
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void suspend() {
                    if (binding() != created) throw new TenancyException(TENANT_CONTEXT_MISMATCH);
                    TransactionSynchronizationManager.unbindResource(resourceKey);
                }
                @Override public void resume() {
                    if (binding() != null) throw new TenancyException(TENANT_CONTEXT_MISMATCH);
                    TransactionSynchronizationManager.bindResource(resourceKey, created);
                }
                @Override public void afterCompletion(int status) {
                    if (binding() == created) TransactionSynchronizationManager.unbindResource(resourceKey);
                }
            });
        } catch (RuntimeException | Error failed) {
            TransactionSynchronizationManager.unbindResource(resourceKey);
            throw failed;
        }
    }

    private record Binding(TenantId tenant, Connection connection) {}
}
