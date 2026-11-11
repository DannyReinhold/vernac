// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.jdbc;

import java.sql.SQLException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.vernac.runtime.events.EventOrigin;
import org.vernac.runtime.tenancy.TenancyException;
import static org.vernac.runtime.tenancy.TenancyException.Code.*;

/** Guards legacy global event SQL before reading payloads or claiming control-plane work. */
final class JdbcEventOrigin {
    private JdbcEventOrigin() {}

    static void requireGlobal(NamedParameterJdbcTemplate jdbc) {
        EventOrigin.capture().requireGlobal();
        var source = jdbc.getJdbcTemplate().getDataSource();
        if (source == null) throw new TenancyException(TENANT_CONTEXT_CONFIGURATION_ERROR);
        java.sql.Connection connection;
        try { connection = DataSourceUtils.getConnection(source); }
        catch (org.springframework.jdbc.CannotGetJdbcConnectionException failure) {
            throw new TenancyException(TENANT_CONTEXT_CONFIGURATION_ERROR);
        }
        try {
            if (connection.getAutoCommit() || !DataSourceUtils.isConnectionTransactional(connection, source))
                throw new TenancyException(TENANT_TRANSACTION_REQUIRED);
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT current_setting('vernac.tenant_id', true)")) {
                if (!result.next()) throw new TenancyException(TENANT_CONTEXT_CONFIGURATION_ERROR);
                String tenant = result.getString(1);
                if (tenant != null && !tenant.isEmpty()) throw new TenancyException(TENANT_CONTEXT_CONTAMINATED);
            }
        } catch (SQLException failure) {
            // Driver text can contain connection details; retain only the safe classification.
            throw new TenancyException(TENANT_CONTEXT_CONFIGURATION_ERROR);
        } finally {
            DataSourceUtils.releaseConnection(connection, source);
        }
    }
}
