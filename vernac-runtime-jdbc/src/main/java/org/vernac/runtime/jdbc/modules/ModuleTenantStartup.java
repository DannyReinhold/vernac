// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.jdbc.modules;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.vernac.runtime.jdbc.tenancy.JdbcTenantCoordinator;
import org.vernac.runtime.persistence.SchemaDescription;
import org.vernac.runtime.tenancy.TenancyException;
import static org.vernac.runtime.tenancy.TenancyException.Code.*;

/** Fail before module components start if tenant storage is unsafe or inaccessible. */
final class ModuleTenantStartup {
    private ModuleTenantStartup() { }

    static void verify(DataSource runtime, SchemaDescription schema) {
        var tables = schema.tables().stream().filter(t -> t.tenantIsolation() != null)
                .map(SchemaDescription.Table::sqlName).toList();
        if (tables.isEmpty()) return;
        new JdbcTenantCoordinator(runtime, tables, true).verifyRuntimeRole();
        try (var connection = runtime.getConnection(); var query = connection.prepareStatement("""
                SELECT has_schema_privilege(current_user, c.relnamespace, 'USAGE')
                   AND has_table_privilege(current_user, c.oid, 'SELECT')
                   AND has_table_privilege(current_user, c.oid, 'INSERT')
                   AND has_table_privilege(current_user, c.oid, 'UPDATE')
                   AND has_table_privilege(current_user, c.oid, 'DELETE')
                FROM pg_class c WHERE c.oid = ?::regclass
                """)) {
            for (String table : tables) {
                query.setString(1, table);
                try (var result = query.executeQuery()) {
                    if (!result.next() || !result.getBoolean(1)) throw new TenancyException(TENANT_RUNTIME_ROLE_INVALID);
                }
            }
        } catch (SQLException failed) {
            throw new TenancyException(TENANT_CONTEXT_CONFIGURATION_ERROR);
        }
    }
}
