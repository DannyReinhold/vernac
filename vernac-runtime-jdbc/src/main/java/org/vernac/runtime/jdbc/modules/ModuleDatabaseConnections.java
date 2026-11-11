// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.jdbc.modules;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** Verify separate credentials reach the same PostgreSQL writer before migration SQL. */
final class ModuleDatabaseConnections {
    private ModuleDatabaseConnections() { }

    static void verify(DataSource runtime, DataSource migrations) {
        if (unwrap(runtime) == unwrap(migrations)) return;
        try {
            if (!identity(runtime).equals(identity(migrations)))
                throw new IllegalStateException("Module migration and runtime DataSources must target the same PostgreSQL database instance.");
        } catch (SQLException failed) {
            // Driver causes may contain connection URLs, login names or credentials.
            throw new IllegalStateException("Could not verify module migration/runtime database identity. Check both configured connections.");
        }
    }

    private static Identity identity(DataSource source) throws SQLException {
        try (var connection = source.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("""
                     SELECT current_database(), d.oid::bigint, pg_postmaster_start_time(),
                            inet_server_addr()::text, inet_server_port()
                     FROM pg_database d WHERE d.datname = current_database()
                     """)) {
            if (!rows.next()) throw new SQLException("No database identity");
            return new Identity(rows.getString(1), rows.getLong(2), rows.getTimestamp(3).toInstant(),
                    rows.getString(4), rows.getObject(5, Integer.class));
        }
    }

    private record Identity(String database, long oid, Instant startup, String address, Integer port) { }

    private static DataSource unwrap(DataSource source) {
        Objects.requireNonNull(source, "DataSource");
        while (source instanceof DelegatingDataSource proxy && proxy.getTargetDataSource() != null)
            source = proxy.getTargetDataSource();
        return source;
    }
}
