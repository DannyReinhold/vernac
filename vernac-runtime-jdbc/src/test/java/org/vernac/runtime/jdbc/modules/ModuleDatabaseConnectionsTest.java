// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.jdbc.modules;

import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.AbstractDataSource;
import static org.junit.jupiter.api.Assertions.*;

class ModuleDatabaseConnectionsTest {
    @Test void driverFailuresDoNotLeakConnectionDetailsOrCauses() {
        var unavailable = new AbstractDataSource() {
            public Connection getConnection() throws SQLException {
                throw new SQLException("jdbc:postgresql://private-host/customer?password=secret");
            }
            public Connection getConnection(String user, String password) throws SQLException {return getConnection();}
        };
        var other = new org.springframework.jdbc.datasource.SimpleDriverDataSource();
        var error = assertThrows(IllegalStateException.class,
                () -> ModuleDatabaseConnections.verify(unavailable, other));
        assertNull(error.getCause());
        assertFalse(error.getMessage().contains("secret"));
        assertFalse(error.getMessage().contains("private-host"));
        assertTrue(error.getMessage().contains("database identity"));
    }
}
