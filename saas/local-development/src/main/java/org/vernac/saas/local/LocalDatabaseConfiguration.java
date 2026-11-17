// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.local;
import java.sql.*;
import java.util.*;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
/** Local-only provisioning after Compose readiness and before Flyway; runtime never owns its data. */
@AutoConfiguration(beforeName="org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")
@Profile("local")
@ConditionalOnProperty(name="vernac.local.enabled",havingValue="true")
public class LocalDatabaseConfiguration {
    @Bean InitializingBean localDatabaseRoles(Environment e) {
        return () -> provision(e.getRequiredProperty("spring.flyway.url"),e.getRequiredProperty("spring.flyway.user"),
            e.getRequiredProperty("spring.flyway.password"),e.getRequiredProperty("spring.datasource.password"),
            Arrays.asList(e.getRequiredProperty("vernac.local.schemas").split(",")));
    }
    @Bean @DependsOn("localDatabaseRoles")
    FlywayConfigurationCustomizer localRolesBeforeFlyway() {return configuration -> {};}
    static void provision(String url,String admin,String adminPassword,String password,List<String> schemas) {
        try {
            if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{4,5}/workspace")||!password.matches("[A-Za-z0-9_-]{32,64}")||schemas.isEmpty())throw new IllegalStateException();
            for(String schema:schemas)if(!schema.matches("[A-Za-z_][A-Za-z0-9_.]{0,200}"))throw new IllegalStateException();
            try(var db=DriverManager.getConnection(url,admin,adminPassword)) {
                db.setAutoCommit(false);
                try(var sql=db.createStatement()) {
                    sql.execute("SELECT pg_advisory_xact_lock(601711)");
                    var role=sql.executeQuery("SELECT rolsuper,rolbypassrls,rolcreatedb,rolcreaterole FROM pg_roles WHERE rolname='workspace_runtime'");
                    if(role.next()) {if(role.getBoolean(1)||role.getBoolean(2)||role.getBoolean(3)||role.getBoolean(4))throw new IllegalStateException();}
                    else sql.execute("CREATE ROLE workspace_runtime LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"+password+"'");
                    for(String schema:schemas) {
                        String name="\""+schema+"\"";
                        sql.execute("CREATE SCHEMA IF NOT EXISTS "+name);
                        var owner=sql.executeQuery("SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname='"+schema+"'");
                        if(!owner.next()||owner.getString(1).equals("workspace_runtime"))throw new IllegalStateException();
                        sql.execute("GRANT USAGE ON SCHEMA "+name+" TO workspace_runtime");
                        sql.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA "+name+" GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO workspace_runtime");
                        sql.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA "+name+" TO workspace_runtime");
                    }
                }
                db.commit();
            }
        }catch(Exception unavailable){throw new IllegalStateException("Local runtime role provisioning unavailable.");}
    }
}
