// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.jdbc.tenancy;

import static org.vernac.runtime.tenancy.TenancyException.Code.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
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
    private final Object resourceKey;
    private enum Policy { FLAGS_ONLY, AGGREGATE, EVENT }
    private final Policy policy;

    /** Table names use PostgreSQL regclass syntax, including quotes where required. */
    public JdbcTenantCoordinator(DataSource source, List<String> tenantTables) {
        this(source,tenantTables,false);
    }
    /** Generated stores require the exact owned policy, not merely active RLS flags. */
    public JdbcTenantCoordinator(DataSource source, List<String> tenantTables, boolean requireOwnedPolicy) {
        this(source,tenantTables,requireOwnedPolicy?Policy.AGGREGATE:Policy.FLAGS_ONLY);
    }
    /** Exact mixed-origin payload policy, sharing transaction binding with aggregate stores. */
    public static JdbcTenantCoordinator forEventPayloads(DataSource source, List<String> tables) {
        return new JdbcTenantCoordinator(source,tables,Policy.EVENT);
    }
    private JdbcTenantCoordinator(DataSource source, List<String> tenantTables, Policy policy) {
        this.source = Objects.requireNonNull(source, "source");
        this.tables = List.copyOf(tenantTables);
        this.resourceKey = new SourceKey(source);
        this.policy = policy;
        if (tables.isEmpty() || tables.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Tenant tables must be explicitly configured");
        }
        verifyBaselineConnection();
    }

    private void verifyBaselineConnection() {
        // Standard JDBC pools must hand out autocommit connections before Spring begins a transaction.
        // Otherwise an automatically synchronized connection from an unrelated manager could look transactional.
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new TenancyException(TENANT_CONTEXT_CONFIGURATION_ERROR);
        Connection baseline=connection();
        try {
            if(!baseline.getAutoCommit()) throw new TenancyException(TENANT_CONTEXT_CONFIGURATION_ERROR);
            String selected=databaseTenant(baseline);
            if(selected!=null && !selected.isEmpty()) throw new TenancyException(TENANT_CONTEXT_CONTAMINATED);
        } catch(SQLException failed) {
            throw new TenancyException(TENANT_CONTEXT_CONFIGURATION_ERROR);
        } finally {
            DataSourceUtils.releaseConnection(baseline,source);
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
            bindTenant(connection, tenant);
            return tenant;
        } catch (SQLException failed) {
            throw new TenancyException(TENANT_CONTEXT_CONFIGURATION_ERROR);
        } finally {
            DataSourceUtils.releaseConnection(connection, source);
        }
    }

    private void bindTenant(Connection connection, TenantId tenant) throws SQLException {
        Binding existing = binding();
        String current = databaseTenant(connection);
        if (existing != null) {
            verifyBinding(existing, connection, tenant, current);
            return;
        }
        if (current != null && !current.isEmpty())
            throw new TenancyException(TENANT_CONTEXT_CONTAMINATED);
        verifyTables(connection);
        try (var statement = connection.prepareStatement("SELECT set_config('vernac.tenant_id', ?, true)")) {
            statement.setString(1, tenant.asString());
            statement.execute();
        }
        register(new Binding(tenant, connection, new HashSet<>(verificationKeys())));
    }

    private void verifyBinding(Binding existing, Connection connection, TenantId tenant,
                               @Nullable String current) throws SQLException {
        if (!existing.tenant.equals(tenant) || !existing.connection.equals(connection)
                || !tenant.asString().equals(current))
            throw new TenancyException(TENANT_CONTEXT_MISMATCH);
        Set<String> required = verificationKeys();
        if (!existing.verified.containsAll(required)) {
            verifyTables(connection);
            existing.verified.addAll(required);
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
            verifyTableRole(connection, table);
            if (policy == Policy.AGGREGATE) verifyPolicy(connection, table);
            if (policy == Policy.EVENT) verifyEventPolicy(connection, table);
        }
    }

    private static void verifyTableRole(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT r.rolsuper OR r.rolbypassrls
                    OR pg_has_role(current_user, c.relowner, 'MEMBER')
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

    private Set<String> verificationKeys() {
        Set<String> keys=new HashSet<>();
        tables.forEach(t->keys.add(t+"#policy="+policy.name()));return keys;
    }
    private static void verifyPolicy(Connection connection,String table) throws SQLException {
        String expected="(\"@tenantId\" = (NULLIF(current_setting('vernac.tenant_id'::text, true), ''::text))::uuid)";
        try(var statement=connection.prepareStatement("""
                SELECT polname, polcmd, polpermissive, polroles = ARRAY[0::oid],
                       pg_get_expr(polqual,polrelid), pg_get_expr(polwithcheck,polrelid)
                FROM pg_policy WHERE polrelid = ?::regclass
                """)) {
            statement.setString(1,table);
            try(var result=statement.executeQuery()) {
                if(!result.next() || !result.getString(1).equals("@tenant") || !result.getString(2).equals("*")
                        || !result.getBoolean(3) || !result.getBoolean(4)
                        || !expected.equals(result.getString(5)) || !expected.equals(result.getString(6)) || result.next())
                    throw new TenancyException(TENANT_RUNTIME_ROLE_INVALID);
            }
        }
    }

    private static void verifyEventPolicy(Connection connection,String table) throws SQLException {
        String selected="(NULLIF(current_setting('vernac.tenant_id'::text, true), ''::text))::uuid";
        String read="((\"@tenantId\" IS NULL) OR (\"@tenantId\" = "+selected+"))";
        String write="(NOT (\"@tenantId\" IS DISTINCT FROM "+selected+"))";
        try(var statement=connection.prepareStatement("""
                SELECT p.polname, p.polcmd, p.polpermissive, p.polroles = ARRAY[0::oid],
                       pg_get_expr(p.polqual,p.polrelid), pg_get_expr(p.polwithcheck,p.polrelid),
                       has_schema_privilege(current_user,c.relnamespace,'USAGE')
                        AND has_table_privilege(current_user,c.oid,'SELECT')
                        AND has_table_privilege(current_user,c.oid,'INSERT')
                FROM pg_policy p JOIN pg_class c ON c.oid=p.polrelid WHERE p.polrelid=?::regclass
                """)) {
            statement.setString(1,table);
            try(var result=statement.executeQuery()) {
                if(!result.next() || !result.getString(1).equals("@event-origin") || !result.getString(2).equals("*")
                        || !result.getBoolean(3) || !result.getBoolean(4) || !read.equals(result.getString(5))
                        || !write.equals(result.getString(6)) || !result.getBoolean(7) || result.next())
                    throw new TenancyException(TENANT_RUNTIME_ROLE_INVALID);
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

    private record Binding(TenantId tenant, Connection connection,Set<String> verified) {}
    private static final class SourceKey {
        private final DataSource source;
        SourceKey(DataSource source) {this.source=source;}
        @Override public boolean equals(@Nullable Object other) {return other instanceof SourceKey key && key.source==source;}
        @Override public int hashCode() {return System.identityHashCode(source);}
    }
}
