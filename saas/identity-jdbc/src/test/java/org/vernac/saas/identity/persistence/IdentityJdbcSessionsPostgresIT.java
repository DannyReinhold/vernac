// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.session.jdbc.config.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.vernac.saas.identity.domain.UserId;
import org.vernac.saas.identity.security.*;

@EnabledIfEnvironmentVariable(named="VERNAC_PG_TEST_URL",matches=".+")
class IdentityJdbcSessionsPostgresIT {
    @TempDir Path root;
    @Configuration(proxyBeanMethods=false) @EnableWebSecurity @Import(IdentityJdbcSessions.class)
    static class Application {
        @Bean @SpringSessionDataSource DataSource dataSource(Environment env) {
            return new DriverManagerDataSource(env.getRequiredProperty("session.url"),env.getRequiredProperty("session.user"),env.getRequiredProperty("session.password"));
        }
        @Bean @SpringSessionTransactionManager PlatformTransactionManager transactionManager(@SpringSessionDataSource DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }
        @Bean AuthenticationManager authentication() {
            return input->{
                if(!"secret".equals(input.getCredentials()))throw new BadCredentialsException("invalid");
                var result=UsernamePasswordAuthenticationToken.authenticated(new IdentityPrincipal(UserId.create()),null,List.of());
                result.setDetails(new IdentitySessionStamp(0,Instant.now()));return result;
            };
        }
        @Bean SecurityFilterChain security(HttpSecurity http,AuthenticationManager authentication) throws Exception {
            return IdentityBrowserSecurity.build(http,authentication,
                    new IdentitySessionTrust(id->OptionalLong.of(0),Clock.systemUTC()),new IdentityLocalReturnTarget("/workspace"));
        }
    }
    private AnnotationConfigWebApplicationContext application(IdentityJdbcTestEnvironment.Database db) {
        var context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("session-test",Map.of(
                "session.url",db.url,"session.user",db.role,"session.password",db.password)));
        context.register(Application.class);context.refresh();return context;
    }
    private void schema(IdentityJdbcTestEnvironment.Database db) throws Exception {
        try(var connection=DriverManager.getConnection(db.url,db.credentials);var sql=connection.createStatement()) {
            sql.execute("CREATE SCHEMA identity_sessions");connection.setSchema("identity_sessions");
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("org/springframework/session/jdbc/schema-postgresql.sql"));
            sql.execute("GRANT USAGE ON SCHEMA identity_sessions TO "+db.role);
            sql.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA identity_sessions TO "+db.role);
        }
    }
    private static String csrfToken(MockHttpServletResponse response) throws Exception {
        var matcher=java.util.regex.Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(response.getContentAsString());
        assertTrue(matcher.find(),"Missing CSRF form token");return matcher.group(1);
    }
    private MockHttpServletRequest request(String method,String path,Cookie cookie) {
        var request=new MockHttpServletRequest(method,path);request.setServletPath(path);request.setSecure(true);
        request.setScheme("https");request.setServerPort(443);request.setRemoteAddr("192.0.2.5");
        if(cookie!=null)request.setCookies(cookie);return request;
    }
    private MockHttpServletResponse dispatch(AnnotationConfigWebApplicationContext context,MockHttpServletRequest request) throws Exception {
        var response=new MockHttpServletResponse();
        context.getBean("springSessionRepositoryFilter",Filter.class).doFilter(request,response,(sessionRequest,sessionResponse)->
            context.getBean("springSecurityFilterChain",Filter.class).doFilter(sessionRequest,sessionResponse,(raw,out)->{
                if(request.getServletPath().equals("/identity/login") && request.getMethod().equals("GET"))
                    out.getWriter().write(((CsrfToken)raw.getAttribute(CsrfToken.class.getName())).getToken());
                else out.getWriter().write("protected");
            }));
        return response;
    }
    private void form(MockHttpServletRequest request,String body) {
        request.setContentType("application/x-www-form-urlencoded");request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private Cookie login(AnnotationConfigWebApplicationContext context) throws Exception {
        var page=dispatch(context,request("GET","/identity/login",null));Cookie anonymous=page.getCookie("__Host-VERNACSESSION");
        assertNotNull(anonymous);assertTrue(anonymous.getSecure());assertTrue(anonymous.isHttpOnly());
        assertEquals("/",anonymous.getPath());assertNull(anonymous.getDomain());assertEquals("Lax",anonymous.getAttribute("SameSite"));
        var login=request("POST","/identity/login",anonymous);form(login,"address=a%40example.test&password=secret&_csrf="+csrfToken(page));
        var response=dispatch(context,login);assertEquals(303,response.getStatus());Cookie authenticated=response.getCookie("__Host-VERNACSESSION");
        assertNotNull(authenticated);assertNotEquals(anonymous.getValue(),authenticated.getValue());return authenticated;
    }

    @Test void realSessionFiltersShareAuthenticationAcrossInstancesAndRestartWithThirtyMinuteIdleExpiry() throws Exception {
        new IdentityJdbcTestEnvironment(root).withDatabase(db->{
            schema(db);Cookie cookie;
            try(var first=application(db);var second=application(db)) {
                cookie=login(first);
                assertEquals("protected",dispatch(second,request("GET","/workspace",cookie)).getContentAsString());
                var jdbc=new JdbcTemplate(second.getBean(DataSource.class));
                assertEquals(1800,jdbc.queryForObject("SELECT max_inactive_interval FROM identity_sessions.spring_session",Integer.class));
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM identity_sessions.spring_session",Integer.class));
            }
            try(var restarted=application(db)) {
                assertEquals("protected",dispatch(restarted,request("GET","/workspace",cookie)).getContentAsString());
                var jdbc=new JdbcTemplate(restarted.getBean(DataSource.class));
                jdbc.update("UPDATE identity_sessions.spring_session SET last_access_time=?,expiry_time=?",0L,1L);
                assertEquals(401,dispatch(restarted,request("GET","/workspace",cookie)).getStatus());
            }
        });
    }

    @Test void csrfProtectedLogoutDeletesTheSharedSessionAndOldCookieCannotAuthenticate() throws Exception {
        new IdentityJdbcTestEnvironment(root).withDatabase(db->{
            schema(db);
            try(var first=application(db);var second=application(db)) {
                Cookie cookie=login(first);
                var page=dispatch(second,request("GET","/identity/login",cookie));
                var logout=request("POST","/identity/logout",cookie);form(logout,"_csrf="+csrfToken(page));
                assertEquals(204,dispatch(second,logout).getStatus());
                assertEquals(0,new JdbcTemplate(first.getBean(DataSource.class)).queryForObject("SELECT count(*) FROM identity_sessions.spring_session",Integer.class));
                assertEquals(401,dispatch(first,request("GET","/workspace",cookie)).getStatus());
            }
        });
    }

    @Test void missingSchemaOrDdlPrivilegesFailStartupInsteadOfFallingBack() throws Exception {
        new IdentityJdbcTestEnvironment(root).withDatabase(db->{
            assertThrows(RuntimeException.class,()->{try(var ignored=application(db)) {fail("missing schema accepted");}});
            schema(db);
            try(var admin=DriverManager.getConnection(db.url,db.credentials);var sql=admin.createStatement()) {
                sql.execute("GRANT CREATE ON SCHEMA identity_sessions TO "+db.role);
                assertThrows(RuntimeException.class,()->{try(var ignored=application(db)) {fail("DDL privileges accepted");}});
                sql.execute("REVOKE CREATE ON SCHEMA identity_sessions FROM "+db.role);
                sql.execute("GRANT TRUNCATE ON identity_sessions.spring_session TO "+db.role);
                assertThrows(RuntimeException.class,()->{try(var ignored=application(db)) {fail("TRUNCATE accepted");}});
                sql.execute("REVOKE TRUNCATE ON identity_sessions.spring_session FROM "+db.role);
            }
            try(var working=application(db)) {assertNotNull(working.getBean("springSessionRepositoryFilter"));}
        });
    }
}
