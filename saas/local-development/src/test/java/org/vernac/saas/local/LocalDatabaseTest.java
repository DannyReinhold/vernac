// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.local;
import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
class LocalDatabaseTest {
    @Test @EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
    void localProvisioningIsRepeatableAndRuntimeNeverOwnsSchemasOrTables() throws Exception {
        String admin=System.getenv("VERNAC_PG_TEST_URL"),user=System.getenv("VERNAC_PG_TEST_USER"),password=System.getenv("VERNAC_PG_TEST_PASSWORD");
        String url=admin.substring(0,admin.lastIndexOf('/')+1)+"workspace";
        url=url.replace("://localhost:","://127.0.0.1:");
        String runtime=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        try(var connection=DriverManager.getConnection(admin,user,password);var sql=connection.createStatement()) {
            sql.execute("CREATE DATABASE workspace");
            try {
                LocalDatabaseConfiguration.provision(url,user,password,runtime,List.of("local.proof"));
                LocalDatabaseConfiguration.provision(url,user,password,runtime,List.of("local.proof"));
                try(var owner=DriverManager.getConnection(url,user,password);var ddl=owner.createStatement()) {ddl.execute("CREATE TABLE \"local.proof\".sample(id integer)");}
                try(var restricted=DriverManager.getConnection(url,"workspace_runtime",runtime);var query=restricted.createStatement()) {
                    query.execute("INSERT INTO \"local.proof\".sample VALUES (1)");
                    assertThrows(SQLException.class,()->query.execute("TRUNCATE \"local.proof\".sample"));
                    assertThrows(SQLException.class,()->query.execute("CREATE TABLE \"local.proof\".forbidden(id integer)"));
                    var role=query.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user");assertTrue(role.next());assertFalse(role.getBoolean(1));
                    var count=query.executeQuery("SELECT count(*) FROM \"local.proof\".sample");assertTrue(count.next());assertEquals(1,count.getInt(1));
                }
            } finally {sql.execute("DROP DATABASE workspace WITH (FORCE)");sql.execute("DROP ROLE IF EXISTS workspace_runtime");}
        }
    }
}
