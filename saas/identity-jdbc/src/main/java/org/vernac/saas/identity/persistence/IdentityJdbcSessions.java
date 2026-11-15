// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.session.jdbc.config.annotation.*;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;
import org.springframework.session.web.http.*;
import org.springframework.transaction.PlatformTransactionManager;

/** Explicitly import; no component stereotype, auto-configuration, DDL or in-memory fallback. */
@EnableJdbcHttpSession(maxInactiveIntervalInSeconds=1800, tableName="identity_sessions.spring_session")
public class IdentityJdbcSessions {
    @Bean
    public CookieSerializer identitySessionCookieSerializer() {
        var cookies=new DefaultCookieSerializer();
        cookies.setCookieName("__Host-VERNACSESSION");cookies.setCookiePath("/");
        cookies.setUseSecureCookie(true);cookies.setUseHttpOnlyCookie(true);cookies.setSameSite("Lax");
        return cookies;
    }

    @Bean
    public InitializingBean identitySessionSchemaGuard(@SpringSessionDataSource DataSource dataSource,
            @SpringSessionTransactionManager PlatformTransactionManager manager) {
        return () -> {
            try {
                if (!(manager instanceof DataSourceTransactionManager jdbcManager) || jdbcManager.getDataSource()!=dataSource)
                    throw new IllegalStateException();
                var jdbc=new JdbcTemplate(dataSource);
                if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT has_schema_privilege(current_user,'identity_sessions','USAGE')"
                        + " AND NOT has_schema_privilege(current_user,'identity_sessions','CREATE')"
                        + " AND NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user",Boolean.class)))
                    throw new IllegalStateException();
                for(String table:new String[]{"identity_sessions.spring_session","identity_sessions.spring_session_attributes"}) {
                    for(String operation:new String[]{"SELECT","INSERT","UPDATE","DELETE"})
                        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT has_table_privilege(current_user,?,?)",Boolean.class,table,operation)))
                            throw new IllegalStateException();
                    if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT has_table_privilege(current_user,?,'TRUNCATE')",Boolean.class,table)))
                        throw new IllegalStateException();
                }
                jdbc.queryForList("SELECT primary_id,session_id,creation_time,last_access_time,max_inactive_interval,expiry_time,principal_name"
                        + " FROM identity_sessions.spring_session WHERE false");
                jdbc.queryForList("SELECT session_primary_id,attribute_name,attribute_bytes FROM identity_sessions.spring_session_attributes WHERE false");
            } catch(RuntimeException unavailable) {
                // Never publish database URL, SQL, credentials or driver causes in the startup error.
                throw new IllegalStateException("Identity JDBC session configuration unavailable");
            }
        };
    }
}
