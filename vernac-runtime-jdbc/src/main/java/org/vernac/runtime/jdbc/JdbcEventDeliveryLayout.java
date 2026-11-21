// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.jdbc;

import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.vernac.runtime.VernacTechnicalException;

/** Control-plane metadata must be upgraded before a new producer or worker writes jobs. */
final class JdbcEventDeliveryLayout {
    private JdbcEventDeliveryLayout() {}
    static void verify(NamedParameterJdbcTemplate jdbc) {
        try {
            var valid=jdbc.queryForList("""
                    SELECT NOT c.relrowsecurity AND NOT c.relforcerowsecurity
                       AND format_type(a.atttypid,a.atttypmod)='uuid' AND NOT a.attnotnull
                    FROM pg_class c JOIN pg_attribute a ON a.attrelid=c.oid
                    WHERE c.oid=to_regclass(:table) AND c.relkind='r'
                      AND a.attname='tenantId' AND a.attnum>0 AND NOT a.attisdropped
                    """,Map.of("table",JdbcEventStore.DELIVERY_TABLE),Boolean.class);
            if(valid.size()!=1 || !valid.getFirst())
                throw new VernacTechnicalException("Event delivery schema requires its reviewed origin migration. Rebuild and migrate the application before starting producers/workers.");
        } catch(DataAccessException failed) {
            throw new VernacTechnicalException("Could not verify event delivery schema. Check database access and migration state.");
        }
    }
}
